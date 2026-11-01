package com.ebook.find.mvvm.viewmodel

import androidx.lifecycle.viewModelScope
import com.ebook.common.analyze.source.SourceResolver
import com.ebook.common.manager.BookShelfManager
import com.ebook.common.manager.applying
import com.ebook.common.repository.ShelfRepository
import com.ebook.common.util.reportFailure
import com.ebook.db.entity.SearchBookEntity
import com.ebook.db.entity.SearchHistoryEntity
import com.ebook.find.repository.SearchHistoryRepository
import com.ebook.find.search.AggregationSession
import com.ebook.find.search.SearchProgress
import com.ebook.source.analyze.AggregateSearchEvent
import com.xrn1997.common.mvvm.viewmodel.BaseRefreshViewModel
import com.xrn1997.common.mvvm.viewmodel.Overlay
import com.xrn1997.common.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 搜索页 VM：一次搜全站的**聚合搜索**（ADR-0016 P3-b）。
 *
 * 与改造前的差别不只是「多调几个源」，而是**结果不再是一次拿全的数组**：
 * 每个第三方站点的快慢与死活各自独立，所以本 VM 消费 [SourceResolver.searchAcross] 的事件流，
 * 边收边把结果按 `noteUrl` 并进 [list]（基类的 `StateFlow`），进度经 [searchProgress] 供 UI 渲染。
 *
 * 聚合的簿记（关键词、每源游标、结束集、已见条目、单轮进度与成败）归 [AggregationSession]：
 * 它只算协议、不认识列表与覆盖层；本 VM 把事件翻译成会话调用，再按会话给出的请求计划与进度渲染页面。
 * 「几时算一轮」「哪条源还能翻」这类状态因此只有一处主人。
 *
 * - "输入框是否有内容/是否已搜索"等纯 View 状态由 Activity 自持，不进 VM
 * - 书架快照与书架事件的应用收在 `BookShelfManager`，本页只把自己的结果列表按事件对齐
 *
 * ## 翻页：每源独立游标，只对未结束的源继续翻
 *
 * [AggregationSession] 记「这一源下次该请求第几页」与「哪些源已经不会再有新结果」（到底、失败、
 * 或去重后没带来新条目）。下一轮把后者经 `skipSourceUrls` 交给 `searchAcross`，
 * 于是第 N 轮只对还活着的源发请求。为什么判「到底」不能只看空页：越界页会以 HTTP 200
 * **重复返回首页书目**（软 404），那种页有结果却不新，所以 [AggregateSearchEvent.SourceResult] 去重后
 * 「零新条目」同样把该源判结束（docs/architecture.md「分页与正文跟进」那条铁律）。
 *
 * ## 全局按 noteUrl 去重，但**不按** name+author 去重
 *
 * 同名同作者、来自两个源的两条书**故意都留着**：那正是「这本读完了/这个源挂了换哪个源」的备选，
 * 阅读器内的跨源换源（P3-d）就以它为入口。按书名去重会让第二个源的条目在用户第一次搜索时就永久消失，
 * 而 `noteUrl` 全局唯一（列表以它作 item key，重复 key 会让 Compose 直接抛异常），
 * 用它去重既解决了崩溃风险又保留了换源余地。
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchHistoryRepository: SearchHistoryRepository,
    private val sourceResolver: SourceResolver,
    private val bookShelfManager: BookShelfManager,
    bookShelfRepository: ShelfRepository
) : BaseRefreshViewModel<SearchBookEntity, SearchHistoryRepository>(searchHistoryRepository) {

    /**
     * 聚合搜索的全部簿记（关键词、每源游标、结束集、已见条目、单轮进度与成败）的唯一主人。
     *
     * 这些状态不再摊成多个 VM 字段：会话把「跨轮存活」与「每轮归零」两层收在值类型里，
     * 换关键词与起一轮都是整块换新，结构上不可能只清一半——漏清一处不会报编译错，
     * 只会让上一轮的进度与游标混进新一轮（事故形态见 [loadMore] 的 KDoc）。
     */
    private val aggregation = AggregationSession()

    /**
     * 当前聚合流。
     *
     * 重新搜索（换关键词或同词重搜）时必须先取消上一轮：两轮流同时在跑时，旧轮的
     * [AggregateSearchEvent.SourceResult] 会往已被清空的新列表里灌旧关键词的书，
     * 会话里的进度与结束集也会被两轮流交错改写。取消是唯一的收敛手段——
     * `searchAcross` 里的单源 catch 刻意不咽 [CancellationException]，取消才能真的落地。
     */
    private var aggregateJob: Job? = null

    private val _searchProgress = MutableStateFlow(SearchProgress())

    /**
     * 书源聚合进度，供 UI 渲染「已收到 X/Y 书源结果」与进度条。
     *
     * 每轮开始即清零（[toSearchBooks] / [loadMore] 都会起新一轮），全部源结束后
     * `finished == total`，UI 据此让进度条消失。
     */
    val searchProgress: StateFlow<SearchProgress> = _searchProgress.asStateFlow()

    /** 搜索历史查询结果（供历史标签渲染） */
    private val _successEvent = MutableSharedFlow<List<SearchHistoryEntity>>(extraBufferCapacity = 1)
    val successEvent: SharedFlow<List<SearchHistoryEntity>> = _successEvent.asSharedFlow()

    /** 加书架失败的一次性结论（分类口径与分类页共用 [FindNoticeSlot]，文案归 `SearchActivity`） */
    private val findNotices = FindNoticeSlot()

    /** 待页面消费的结论；页面展示后经 [consumeFindNotice] 清零 */
    internal val findNotice: StateFlow<FindNotice?> = findNotices.notice

    /** 结论已被页面取走并展示 */
    fun consumeFindNotice() = findNotices.consume()

    init {
        viewModelScope.launch {
            try {
                bookShelfManager.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.e(TAG, "refresh shelves onError: ", e)
            }
        }
        // 书架事件同步：快照由 BookShelfManager 单点应用，本页只把结果列表的标记对齐
        viewModelScope.launch {
            bookShelfRepository.bookShelfEvents.collect { event ->
                bookShelfManager.applyEvent(event)
                val next = list.value.applying(event)
                if (next !== list.value) updateList(next)
            }
        }
    }

    /**
     * 触底加载更多：只对**未结束**的源翻下一页（[AggregationSession] 的结束集经
     * `skipSourceUrls` 排除，不再发请求），并把进度清零重走一遍。
     *
     * 所有源都已结束时不发任何请求，直接报「没有更多」——否则空转一轮，footer 会闪一下加载动画。
     *
     * **上一轮还在跑时这一次直接忽略**（[aggregateJob] 仍 active）。基类的刷新状态机挡不住这种交错：
     * 首轮由搜索按钮的 [toSearchBooks] 发起、不经状态机，机器此刻还在 Idle，于是 `beginLoadMore`
     * 那道「已在 Loading 就 return」的闸门形同不存在，而 `RefreshableList` 的触底判据只看
     * `!isLoadingMore` —— 首轮结果正一条条往外蹦、列表刚长过一屏时用户停在底部，就会真的走到这里。
     * 并起第二轮的后果是**共享状态被两轮流交错改写**（本轮簿记由 [AggregationSession] 持有，
     * [startRound] 一上来就把它整块换新），旧轮随后到达的 Finished 于是记在新一轮的账上，
     * 「已收到 X/Y」算重、每源游标被两轮互相覆写。由
     * `SearchViewModelTest` 的 `首轮搜索在途时加载更多不并起第二轮聚合` 锁住。
     *
     * 为什么是「忽略」而不是「取消旧轮再开新轮」：旧轮已经到手的结果不该被丢，而且它的收尾本身就会发
     * [updateStopLoadMore] 把机器从 Loading 放出来——界面仍停在底部时触底会再触发一次，
     * 那时带的就是正确游标。反过来取消旧轮会让会话里该源的游标停在旧值、
     * 新轮重问一遍已问过的页，那一页去重后「零新条目」会把每条源都判「到底」，
     * footer 直接亮出假的「没有更多」。
     *
     * 忽略不会把 footer 永久卡在加载态：一轮无论怎么结束（AllFinished、整流出问题）都会经
     * [finishRound] 发一次 stopLoadMore；[toSearchBooks] 另起的新一轮同样会走到那里。
     */
    override fun loadMore() {
        if (aggregation.keyword.isEmpty()) return
        if (aggregateJob?.isActive == true) {
            Logger.d(TAG, "loadMore 跳过：上一轮聚合还在途中")
            return
        }
        if (!aggregation.hasActiveSources) {
            Logger.d(TAG, "loadMore 跳过：本轮没有还能翻页的书源")
            updateHasMoreData(false)
            updateStopLoadMore(true)
            return
        }
        startRound()
    }

    /** 插入搜索历史（upsert：同一词条仅更新时间戳），插入后自动刷新历史列表。 */
    fun insertSearchHistory(content: String) {
        viewModelScope.launch {
            try {
                searchHistoryRepository.insertSearchHistory(BOOK, content)
                // 插入后刷新全量历史（同一词条重复搜索仅更新时间戳，不影响展示集合）
                querySearchHistory()
            } catch (e: Throwable) {
                Logger.e(TAG, "onError: ", e)
            }
        }
    }

    /** 清除 BOOK 类型全部搜索历史，成功后向 [successEvent] 发射空列表。 */
    fun cleanSearchHistory() {
        viewModelScope.launch {
            try {
                val value = searchHistoryRepository.cleanSearchHistory(BOOK)
                if (value > 0) {
                    _successEvent.tryEmit(listOf())
                }
            } catch (e: Throwable) {
                Logger.e(TAG, "onError: ", e)
            }
        }
    }

    /** 查询 BOOK 类型全部搜索历史，结果通过 [successEvent] 发射。 */
    fun querySearchHistory() {
        viewModelScope.launch {
            try {
                val entities = searchHistoryRepository.querySearchHistory(BOOK)
                _successEvent.tryEmit(entities)
            } catch (e: Throwable) {
                Logger.e(TAG, "onError: ", e)
            }
        }
    }

    /**
     * 发起搜索：清空上一轮的全部聚合态、显示加载态，再从第 1 页并发搜所有启用书源。
     * 空内容直接返回（由 Activity 侧拦截并触发抖动）。
     */
    fun toSearchBooks(content: String) {
        if (content.isEmpty()) {
            return
        }
        // 掐掉上一轮：旧流的 SourceResult 不许再往新结果里灌（理由见 aggregateJob）
        aggregateJob?.cancel()
        // 换关键词重搜：会话整块重置（游标、结束集与已见条目一并归零），不逐项清
        aggregation.startSearch(content)
        // 列表清空必须发生在第一轮 SourceResult 之前，否则新关键词的结果会追加在旧结果后面
        updateList(emptyList())
        updateOverlay(Overlay.Loading)
        startRound()
    }

    /**
     * 将搜索结果中的书籍加入书架，失败时发结论、不选文案。
     *
     * 失败经共享槽 [findNotices] 分类成 [FindNotice]（分类口径与 `ChoiceBookViewModel` 同一处，
     * 见 [FindNoticeSlot]）；**文案与上报出口都在页面**：`SearchActivity` 取走结论后仍走
     * `reportFailure`，所以「会话过期只记日志、不重复提示」那条不变量没被这次改动换掉。
     *
     * **传进去的是列表里那条书本身**：它的 `tag` 是**该书所属书源**的 URL（解析器写入），
     * 不是当前默认源——聚合结果混着多个站的条目，加错源会让这本书从此解析不出内容。
     */
    fun addBookToShelf(searchBook: SearchBookEntity) {
        updateOverlay(Overlay.Loading)
        viewModelScope.launch {
            bookShelfManager.addFromSearch(searchBook)
                .onFailure { e -> findNotices.reportAddBookFailure(e) }
            updateOverlay(Overlay.None)
        }
    }

    // region 聚合搜索的一轮

    /**
     * 起一轮聚合搜索（首轮或加载更多），收集 [SourceResolver.searchAcross] 的事件。
     *
     * 请求计划由 [AggregationSession.beginRound] 从**上一轮**的观测算出（页码、排除集），
     * 同时把本轮簿记整块换新；此后到达的结束信号都记在这一轮账上。
     */
    private fun startRound() {
        val plan = aggregation.beginRound()
        publishProgress()
        aggregateJob = viewModelScope.launch {
            try {
                sourceResolver.searchAcross(plan.keyword, plan.page, plan.skipSourceUrls).collect { event ->
                    onSearchEvent(event)
                }
            } catch (e: CancellationException) {
                // 取消来自新一轮搜索或 VM 销毁：既不是失败也不该收尾，原样上抛让本协程干净退出
                throw e
            } catch (e: Throwable) {
                // searchAcross 已把单源异常收敛成事件，走到这里说明整条流出问题（如查清单失败）。
                // 按「本轮全部源都失败」处置，不能让覆盖层永远转下去。
                Logger.e(TAG, "聚合搜索流异常终止: ", e)
                finishRound(fatal = e)
            }
        }
    }

    /**
     * 单个事件 → 会话状态与列表更新。每个分支都只动自己那一份状态，交错到达的多个源不会互相覆盖。
     */
    private fun onSearchEvent(event: AggregateSearchEvent) {
        when (event) {
            is AggregateSearchEvent.SourceStarted -> {
                aggregation.onSourceStarted(event.sourceUrl)
                publishProgress()
            }

            is AggregateSearchEvent.SourceResult ->
                mergeFresh(aggregation.onSourceResult(event.sourceUrl, event.books))

            is AggregateSearchEvent.SourceFailed -> {
                // 只记不报：进度靠结束信号收，提示只在「全部源都失败」时才给
                aggregation.onSourceFailed(event.sourceUrl)
                Logger.w(TAG, "聚合搜索某书源失败: ${event.sourceUrl}", event.error)
            }

            is AggregateSearchEvent.SourceFinished -> {
                // hasMore = 该源该页有结果 **且** 去重后确实带来新条目（软 404 会给出整页重复）
                aggregation.onSourceFinished(event.sourceUrl, event.hasMore)
                publishProgress()
            }

            AggregateSearchEvent.AllFinished -> finishRound(fatal = null)
        }
    }

    /**
     * 追加某源这一页里**全局未见过**的条目：标书架状态 → 追加。
     *
     * 去重由 [AggregationSession.onSourceResult] 完成：按 `noteUrl` 跨源去重（同一本书可能被两个站
     * 收录，noteUrl 相同才叫同一本，留一条就够）；同名不同源的 noteUrl 不同，不会被并掉。
     */
    private fun mergeFresh(fresh: List<SearchBookEntity>) {
        if (fresh.isEmpty()) return
        bookShelfManager.markShelfStatus(fresh)
        updateList(list.value + fresh)
        // 第一条结果到手就别再用整屏加载态盖住：让用户看着结果一条条长出来，进度由进度条表达
        if (uiState.value.overlay == Overlay.Loading) updateOverlay(Overlay.None)
    }

    /**
     * 一轮收尾：收覆盖层、报「还有没有更多」、必要时进错误态。
     *
     * [fatal] 非空表示整条流出问题（没有任何源给出可归因的事件）。错误态的判据由
     * [AggregationSession.finishRound] 给出：「本轮参与的源**全部**失败」。部分失败时已有结果照常展示，
     * 用户不该因为一个站点挂了就看到整屏错误页。全部失败时走 [reportFailure]（会话过期只记日志的
     * 不变量由它收口），并置 [Overlay.NetworkError]——停在空列表等于假装搜索成功。
     */
    private fun finishRound(fatal: Throwable?) {
        // **AllFinished 才是「本轮结束」的判据**，不是「Finished 计数 == Started 计数」：某一路协程被
        // 取消时（真实现里 flatMapMerge 把整路丢弃，见 BookSourceManagerImplTest 的取消用例），该源
        // 连 SourceFinished 都不会发，只数 Finished 会让进度永远差一格、UI 的进度行（isRunning）不消失。
        // 收到 AllFinished 即意味着本轮不再有任何事件到达，进度在这里一次落定。
        val outcome = aggregation.finishRound(fatal)
        _searchProgress.value = outcome.progress
        if (outcome.allFailed) {
            updateOverlay(Overlay.NetworkError)
            updateHasMoreData(false)
        } else {
            updateOverlay(Overlay.None)
            // 「还有更多」= 还有没结束的源可翻；所有源都到底时置 false，footer 显示「没有更多」
            updateHasMoreData(outcome.hasMore)
        }
        updateStopLoadMore(success = !outcome.allFailed)
        if (outcome.allFailed) {
            // 整条流出问题时报它的根因；否则报「所有源都没结果」这句可行动的归因
            reportFailure(fatal ?: AllSourcesFailedException(aggregation.keyword))
        }
    }

    /**
     * 进度 = 本轮已结束源数 / 本轮参与源数，UI 只读不猜。
     *
     * 源开始/结束与起轮走这里；收尾那一次由 [finishRound] 直接用 `outcome.progress` 落定。
     */
    private fun publishProgress() {
        _searchProgress.value = aggregation.progress
    }

    /**
     * 「所有书源都没给出结果」的聚合异常：只在页面需要一句可行动的文案时使用。
     *
     * 消息写成可指导行动的话，因为此刻用户看到的就是「搜不出东西」，而真实原因分散在各源的日志里，
     * 页面只能给一句总的归因（单源原因见 `Logger` 的聚合搜索日志）。
     */
    private class AllSourcesFailedException(keyword: String) :
        Exception("所有书源都没有搜索到「$keyword」，可换个关键词或到书源管理页检查书源")

    // endregion

    // 搜索页面无下拉刷新入口（刷新即重新搜索，由搜索按钮触发），基类抽象方法的空实现
    override fun refreshData() {}

    companion object {
        /** 搜索历史类型：书籍搜索（对齐 SearchHistoryEntity.type 字段） */
        const val BOOK: Int = 2
    }
}
