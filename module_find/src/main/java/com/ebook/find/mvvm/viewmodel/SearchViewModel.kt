package com.ebook.find.mvvm.viewmodel

import androidx.lifecycle.viewModelScope
import com.ebook.common.analyze.source.BookSourceManager
import com.ebook.common.manager.BookShelfManager
import com.ebook.common.repository.BookRepository
import com.ebook.common.repository.BookShelfEvent
import com.ebook.common.util.reportFailure
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.db.entity.SearchHistoryEntity
import com.ebook.find.repository.SearchHistoryRepository
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
 * 每个第三方站点的快慢与死活各自独立，所以本 VM 消费 [BookSourceManager.searchAcross] 的事件流，
 * 边收边把结果按 `noteUrl` 并进 [list]（基类的 `StateFlow`），进度经 [searchProgress] 供 UI 渲染。
 *
 * - "输入框是否有内容/是否已搜索"等纯 View 状态由 Activity 自持，不进 VM
 * - 书架快照与书架事件同步收敛在 VM 内部，View 只消费 [list] 与 [successEvent]
 *
 * ## 翻页：每源独立游标，只对未结束的源继续翻
 *
 * [AggregationBookkeeping.pageBySource] 记「这一源下次该请求第几页」，
 * [AggregationBookkeeping.finishedSources] 记「哪些源已经不会再有新结果」
 * （到底、失败、或去重后没带来新条目）。下一轮把后者经 `skipSourceUrls` 交给 `searchAcross`，
 * 于是第 N 轮只对还活着的源发请求。为什么判「到底」不能只看空页：越界页会以 HTTP 200
 * **重复返回首页书目**（软 404），那种页有结果却不新，所以 [SourceResult] 去重后
 * 「零新条目」同样把该源判结束（AGENTS.md「列表分页」那条铁律）。
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
    private val bookSourceManager: BookSourceManager,
    private val bookShelfManager: BookShelfManager,
    bookRepository: BookRepository
) : BaseRefreshViewModel<SearchBookEntity, SearchHistoryRepository>(searchHistoryRepository) {

    /** 当前书架快照（用于给搜索结果标记"已加书架"状态），仅 VM 内部维护 */
    private val bookShelves = mutableListOf<BookShelfEntity>()

    /** 当前搜索关键词（重新搜索与加载更多都复用它） */
    private var durSearchKey: String = ""

    /**
     * 聚合搜索的全部簿记（按源记的那几份状态收在一个值类型里，见 [AggregationBookkeeping]）。
     *
     * 重置靠**整块换新**而不是逐项 `.clear()`：换关键词重搜时整个换掉，起一轮时由
     * [AggregationBookkeeping.beginRound] 换掉其中的本轮簿记。旧写法把这几份状态摊成六个 VM 字段、
     * 靠六处手写清空维持「新一轮开始时它们是干净的」这条不变量——漏一处既不报编译错、也不闪退，
     * 只会让上一轮的进度与游标混进新一轮（事故形态见 [loadMore] 的 KDoc）。
     */
    private var aggregation = AggregationBookkeeping()

    /**
     * 当前聚合流。
     *
     * 重新搜索（换关键词或同词重搜）时必须先取消上一轮：两轮流同时在跑时，旧轮的
     * [AggregateSearchEvent.SourceResult] 会往已被清空的新列表里灌旧关键词的书，
     * 进度与 [AggregationBookkeeping.finishedSources] 也会被两轮流交错改写。取消是唯一的收敛手段——
     * `searchAcross` 里的单源 catch 刻意不咽 [CancellationException]，取消才能真的落地。
     */
    private var aggregateJob: Job? = null

    private val _searchProgress = MutableStateFlow(SearchProgress())

    /**
     * 书源聚合进度，供 UI 渲染「已收到 X/Y 书源结果」与进度条。
     *
     * 每轮开始即清零（[toSearchBooks] / [loadMore] 都会重置本轮集合），全部源结束后
     * `finished == total`，UI 据此让进度条消失。
     */
    val searchProgress: StateFlow<SearchProgress> = _searchProgress.asStateFlow()

    /** 搜索历史查询结果（供历史标签渲染） */
    private val _successEvent = MutableSharedFlow<List<SearchHistoryEntity>>(extraBufferCapacity = 1)
    val successEvent: SharedFlow<List<SearchHistoryEntity>> = _successEvent.asSharedFlow()

    init {
        viewModelScope.launch {
            try {
                bookShelves.addAll(bookShelfManager.loadBookShelves())
            } catch (e: Throwable) {
                Logger.e(TAG, "loadBookShelves onError: ", e)
            }
        }
        // 书架事件同步：书架增删时更新快照与列表项状态，替代原 Activity 侧的重复收集逻辑
        viewModelScope.launch {
            bookRepository.bookShelfEvents.collect { event ->
                when (event) {
                    is BookShelfEvent.Added -> {
                        bookShelves.add(event.bookShelf)
                        updateBookAddState(event.bookShelf, true)
                    }
                    is BookShelfEvent.Removed -> {
                        bookShelves.remove(event.bookShelf)
                        updateBookAddState(event.bookShelf, false)
                    }
                    is BookShelfEvent.ProgressUpdated -> Unit // 阅读进度与搜索页无关
                }
            }
        }
    }

    /**
     * 触底加载更多：只对**未结束**的源翻下一页（[AggregationBookkeeping.finishedSources] 里的源经
     * `skipSourceUrls` 排除，不再发请求），并把进度清零重走一遍。
     *
     * 所有源都已结束时不发任何请求，直接报「没有更多」——否则空转一轮，footer 会闪一下加载动画。
     *
     * **上一轮还在跑时这一次直接忽略**（[aggregateJob] 仍 active）。基类的刷新状态机挡不住这种交错：
     * 首轮由搜索按钮的 [toSearchBooks] 发起、不经状态机，机器此刻还在 Idle，于是 `beginLoadMore`
     * 那道「已在 Loading 就 return」的闸门形同不存在，而 `RefreshableList` 的触底判据只看
     * `!isLoadingMore` —— 首轮结果正一条条往外蹦、列表刚长过一屏时用户停在底部，就会真的走到这里。
     * 并起第二轮的后果是**共享集合被两轮流交错改写**（[AggregationBookkeeping.round] 里的源集合、
     * 结束集合与计数表都是本轮共享状态，[startRound] 一上来就把整块簿记换新），旧轮随后到达的
     * Finished 于是记在新一轮的账上，「已收到 X/Y」算重、每源游标被两轮互相覆写。由
     * [SearchViewModelTest] 的 `首轮搜索在途时加载更多不并起第二轮聚合` 锁住。
     *
     * 为什么是「忽略」而不是「取消旧轮再开新轮」：旧轮已经到手的结果不该被丢，而且它的收尾本身就会发
     * [updateStopLoadMore] 把机器从 Loading 放出来——界面仍停在底部时触底会再触发一次，
     * 那时带的就是正确游标。反过来取消旧轮会让 [AggregationBookkeeping.pageBySource] 停在旧值、
     * 新轮重问一遍已问过的页，那一页去重后「零新条目」会把每条源都判「到底」，
     * footer 直接亮出假的「没有更多」。
     *
     * 忽略不会把 footer 永久卡在加载态：一轮无论怎么结束（AllFinished、整流出问题）都会经
     * [finishRound] 发一次 stopLoadMore；[toSearchBooks] 另起的新一轮同样会走到那里。
     */
    override fun loadMore() {
        if (durSearchKey.isEmpty()) return
        if (aggregateJob?.isActive == true) {
            Logger.d(TAG, "loadMore 跳过：上一轮聚合还在途中")
            return
        }
        if (activeSources().isEmpty()) {
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
        durSearchKey = content
        // 换关键词重搜：整块簿记换新（游标与跨轮结束集一并归零），不逐项清
        aggregation = AggregationBookkeeping()
        // 列表清空必须发生在第一轮 SourceResult 之前，否则旧关键词的书会被当作「已加载」参与去重，
        // 新结果反被滤掉
        updateList(emptyList())
        updateOverlay(Overlay.Loading)
        startRound()
    }

    /**
     * 将搜索结果中的书籍加入书架，失败时提示。
     *
     * 失败一律经 [reportAddBookFailure] 上报（共享的 `reportFailure` 收口「会话过期只记日志、
     * 不重复提示」，书源失效换资源文案的理由见它）。
     *
     * **传进去的是列表里那条书本身**：它的 `tag` 是**该书所属书源**的 URL（解析器写入），
     * 不是当前默认源——聚合结果混着多个站的条目，加错源会让这本书从此解析不出内容。
     */
    fun addBookToShelf(searchBook: SearchBookEntity) {
        updateOverlay(Overlay.Loading)
        viewModelScope.launch {
            bookShelfManager.addFromSearch(searchBook)
                .onFailure { e -> reportAddBookFailure(e) }
            updateOverlay(Overlay.None)
        }
    }

    /**
     * 书架事件同步：更新列表中对应书籍的"是否已加入书架"状态。
     */
    private fun updateBookAddState(bookShelf: BookShelfEntity, isAdd: Boolean) {
        val currentList = list.value.toMutableList()
        val index = currentList.indexOfFirst { it.noteUrl == bookShelf.noteUrl }
        if (index != -1) {
            val updatedBook = currentList[index].copy(add = isAdd)
            currentList[index] = updatedBook
            updateList(currentList)
        }
    }

    // region 聚合搜索的一轮

    /** 起一轮聚合搜索（首轮或加载更多），收集 [BookSourceManager.searchAcross] 的事件 */
    private fun startRound() {
        // 页码与排除集都按**上一轮**的存活源现算（此时本轮簿记还没换），换新必须在它们之后
        val page = roundPage()
        val skip = aggregation.finishedSources.toSet()
        // 本轮簿记整块换新：进度、失败集、新增计数一并归零，结构上不可能只清一半
        aggregation.beginRound()
        publishProgress()
        aggregateJob = viewModelScope.launch {
            try {
                bookSourceManager.searchAcross(durSearchKey, page, skip).collect { event ->
                    onSearchEvent(event, page)
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
     * 单个事件 → 状态更新。每个分支都只动自己那一份集合，交错到达的多个源不会互相覆盖。
     */
    private fun onSearchEvent(event: AggregateSearchEvent, page: Int) {
        when (event) {
            is AggregateSearchEvent.SourceStarted -> {
                aggregation.round.sources += event.sourceUrl
                // 首次见到该源就登记它的游标（本轮请求的就是这一页）
                aggregation.pageBySource.putIfAbsent(event.sourceUrl, page)
                publishProgress()
            }

            is AggregateSearchEvent.SourceResult -> mergeSourceResult(event)

            is AggregateSearchEvent.SourceFailed -> {
                // 只记不报：进度靠 Finished 收，提示只在「全部源都失败」时才给
                aggregation.round.failed += event.sourceUrl
                Logger.w(TAG, "聚合搜索某书源失败: ${event.sourceUrl}", event.error)
            }

            is AggregateSearchEvent.SourceFinished -> {
                // hasMore = 该源该页有结果 **且** 去重后确实带来新条目（软 404 会给出整页重复）
                val hasMore = event.hasMore && (aggregation.round.fresh[event.sourceUrl] ?: 0) > 0
                if (hasMore) {
                    aggregation.pageBySource[event.sourceUrl] = page + 1
                    aggregation.finishedSources -= event.sourceUrl
                } else {
                    aggregation.finishedSources += event.sourceUrl
                }
                aggregation.round.done += event.sourceUrl
                publishProgress()
            }

            AggregateSearchEvent.AllFinished -> finishRound(fatal = null)
        }
    }

    /**
     * 并入某源这一页的结果：标书架状态 → 按 `noteUrl` 全局去重 → 追加。
     *
     * 去重是**跨源**的：同一本书可能同时被两个站收录（noteUrl 相同才叫同一本，
     * 那种情况下两条归属其实指向同一个站，留一条就够）。同名不同源的 noteUrl 不同，不会被并掉。
     */
    private fun mergeSourceResult(event: AggregateSearchEvent.SourceResult) {
        if (event.books.isEmpty()) {
            aggregation.round.fresh[event.sourceUrl] = 0
            return
        }
        bookShelfManager.markShelfStatus(event.books, bookShelves)
        val loaded = list.value.mapTo(mutableSetOf()) { it.noteUrl }
        val fresh = event.books.filterNot { it.noteUrl in loaded }.distinctBy { it.noteUrl }
        aggregation.round.fresh[event.sourceUrl] = fresh.size
        if (fresh.isEmpty()) return
        updateList(list.value + fresh)
        // 第一条结果到手就别再用整屏加载态盖住：让用户看着结果一条条长出来，进度由进度条表达
        if (uiState.value.overlay == Overlay.Loading) updateOverlay(Overlay.None)
    }

    /**
     * 一轮收尾：收覆盖层、报「还有没有更多」、必要时进错误态。
     *
     * [fatal] 非空表示整条流出问题（没有任何源给出可归因的事件）。错误态的判据是
     * 「本轮参与的源**全部**失败」：部分失败时已有结果照常展示，用户不该因为一个站点挂了
     * 就看到整屏错误页。全部失败时走 [reportFailure]（会话过期只记日志的不变量由它收口），
     * 并置 [Overlay.NetworkError]——停在空列表等于假装搜索成功。
     */
    private fun finishRound(fatal: Throwable?) {
        // **AllFinished 才是「本轮结束」的判据**，不是「Finished 计数 == Started 计数」：某一路协程被
        // 取消时（真实现里 flatMapMerge 把整路丢弃，见 BookSourceManagerImplTest 的取消用例），该源
        // 连 SourceFinished 都不会发，只数 Finished 会让进度永远差一格、UI 的进度行（isRunning）不消失。
        // 收到 AllFinished 即意味着本轮不再有任何事件到达，进度在这里一次落定。
        aggregation.round.done += aggregation.round.sources
        publishProgress()
        val allFailed = fatal != null ||
            (aggregation.round.sources.isNotEmpty() &&
                aggregation.round.failed.size == aggregation.round.sources.size)
        if (allFailed) {
            updateOverlay(Overlay.NetworkError)
            updateHasMoreData(false)
        } else {
            updateOverlay(Overlay.None)
            // 「还有更多」= 还有没结束的源可翻；所有源都到底时置 false，footer 显示「没有更多」
            updateHasMoreData(activeSources().isNotEmpty())
        }
        updateStopLoadMore(success = !allFailed)
        if (allFailed) {
            // 整条流出问题时报它的根因；否则报「所有源都没结果」这句可行动的归因
            reportFailure(fatal ?: AllSourcesFailedException(durSearchKey))
        }
    }

    /** 还能继续翻页的源：本轮参与过、且尚未结束 */
    private fun activeSources(): Set<String> =
        aggregation.round.sources - aggregation.finishedSources

    /**
     * 本轮该请求的页码：未结束源的游标同号（见 [AggregationBookkeeping.pageBySource]），
     * 取最小值兜住理论上的分叉；首轮还没有任何游标时按第 1 页。
     */
    private fun roundPage(): Int =
        activeSources().minOfOrNull { aggregation.pageBySource[it] ?: 1 } ?: 1

    /** 进度 = 本轮已结束源数 / 本轮参与源数（清零与递增都只在这一处出口，UI 只读不猜） */
    private fun publishProgress() {
        _searchProgress.value = SearchProgress(
            finished = aggregation.round.done.size,
            total = aggregation.round.sources.size,
        )
    }

    /**
     * 聚合搜索的簿记：把「按源记的六份状态」收进一个值类型，重置因此变成一次赋值。
     *
     * 里面是**两层生命周期不同**的状态：
     * - **会话级**（[pageBySource] / [finishedSources]）：跨轮存活，只在换关键词重搜时随整个簿记一起换掉；
     * - **轮级**（[round]）：每次起一轮（首轮或加载更多）都由 [beginRound] 整块换新。
     *
     * 为什么收成一个类：这六份状态原先各是 VM 的一个字段，靠六处手写 `.clear()` 维持
     * 「新一轮开始时它们是干净的」这条不变量——漏一处既不报编译错、也不闪退，只是让上一轮的
     * 进度与游标混进新一轮（事故形态见 [loadMore] 的 KDoc）。换成值类型后，重置就是
     * `aggregation = AggregationBookkeeping()` 或 [beginRound] 一次调用，**不可能只清一半**。
     */
    private class AggregationBookkeeping {

        /**
         * 每源独立分页游标：值为该源「下一次该请求的页码」。
         *
         * 各源此刻其实同号（一条源只在「该页有结果」时才活到下一轮，见类注释），
         * 但游标仍按源存：`hasMore` 的判据是每源各自的观测，把状态存成「每源一份」才不必在
         * 未来某源提前到底/支持不同分页模板时回头改结构。
         */
        val pageBySource = mutableMapOf<String, Int>()

        /** 跨轮已结束的源（到底 / 失败 / 去重后没带来新条目）：下一轮经 `skipSourceUrls` 排除 */
        val finishedSources = mutableSetOf<String>()

        /** 本轮簿记；[beginRound] 换新之后，上一轮的任何登记与计数都不再可能被读到 */
        var round = RoundBookkeeping()

        /**
         * 起一轮：把 [round] 整块换成新的一份。
         *
         * **这个赋值本身就是「清零」**——调用方没有第二处需要记得清的东西；调用前若还要读上一轮的
         * 存活源（页码与排除集），必须在调用之前读完（见 [startRound]）。
         */
        fun beginRound() {
            round = RoundBookkeeping()
        }
    }

    /**
     * 单轮簿记（一轮 = 一次 `searchAcross` 流的收集）：
     * 本轮参与的源（进度分母）、本轮已结束的源（进度分子）、本轮失败过的源、本轮各源新增的条目数。
     *
     * 与游标（[AggregationBookkeeping.pageBySource]）分开是**生命周期不同**：游标跨轮存活，
     * 这一坨每轮归零——加载更多只重跑未结束的源，进度要重新走一遍（[done] 因此不能与
     * [AggregationBookkeeping.finishedSources] 合成一份）。
     */
    private class RoundBookkeeping {

        /** 本轮参与聚合的源（收到过 SourceStarted）：进度分母 */
        val sources = mutableSetOf<String>()

        /**
         * 本轮已结束的源：进度分子。与 [AggregationBookkeeping.finishedSources] 分开是因为
         * 加载更多只重跑未结束的源，进度要重新走一遍。
         */
        val done = mutableSetOf<String>()

        /** 本轮失败过的源：判「全部源都失败」才用得上，部分失败照常展示已有结果 */
        val failed = mutableSetOf<String>()

        /** 本轮各源去重后**新增**的条目数：软 404 那种「有结果但全是重复」靠它判到底 */
        val fresh = mutableMapOf<String, Int>()
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

/**
 * 聚合搜索的书源进度：[total] 为本轮参与聚合的书源数，[finished] 为其中已结束的数量。
 *
 * 收尾有两处出口，缺一不可：
 * - [AggregateSearchEvent.SourceFinished] 到达时逐源递增——成功、失败与空页都走这里，
 *   所以进度不会因某个站点挂掉而永远差一格；
 * - [finishRound] 收到 [AggregateSearchEvent.AllFinished] 时把本轮一次收满——兜住「某一路协程
 *   被取消、连 Finished 都没发出去」这一种（真实现里 flatMapMerge 会丢弃整路，见
 *   `BookSourceManagerImplTest` 的取消用例），否则进度行的 `isRunning` 永不为假、在页面上不消失。
 */
data class SearchProgress(val finished: Int = 0, val total: Int = 0) {

    /** 是否还在跑：已开始（有源登记）且还有源没结束 */
    val isRunning: Boolean get() = total > 0 && finished < total

    /** 进度条比例。[total] 为 0 时按 0 收，避免除零 */
    val fraction: Float get() = if (total == 0) 0f else finished.toFloat() / total.toFloat()
}
