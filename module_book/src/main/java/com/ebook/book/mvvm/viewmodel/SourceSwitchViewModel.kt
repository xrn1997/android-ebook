package com.ebook.book.mvvm.viewmodel

import androidx.lifecycle.viewModelScope
import com.ebook.common.analyze.source.BookSourceManager
import com.ebook.common.domain.CommentKey
import com.ebook.common.repository.BookRepository
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.source.analyze.AggregateSearchEvent
import com.xrn1997.common.mvvm.model.NoOpModel
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import com.xrn1997.common.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 阅读中换源的候选页 ViewModel（ADR-0016 决策 8，P3-d）。
 *
 * 只做两件事：**找候选**（拿当前书的书名+作者跨源聚合搜索，按匹配度排序）与**执行换源**
 * （把决定交给 [BookRepository.switchSource] 那条事务）。界面怎么摆、点完关不关弹层都不归它管。
 *
 * Model 位用 [NoOpModel] 占位（无一次性命令门面需求，见 AGENTS.md MVVM 约定）；
 * 状态流命名避开基类的 `uiState`（那是覆盖层专用），沿用「页面名 + State」的口径。
 *
 * ## 为什么候选不是一次拿全的数组
 * [BookSourceManager.searchAcross] 是**按书源分批到达**的增量流：每个第三方站点的快慢与死活都独立，
 * 等「全部源都返回」就把一条挂掉的源变成了整页的阻塞。本 VM 于是边收边并（[mergeCandidates]），
 * 进度经 [progress] 表达「已收到 X/Y 书源结果」。
 *
 * ## 排除当前书源，而不是排除当前那本书
 * `excludeSourceUrl` 经 `skipSourceUrls` 交给 `searchAcross`：当前这本书所属的站点根本不该被请求。
 * 少传这一步，用户就会在同一页候选里看到自己正在读的那条（点了等于原地换一次源）。
 *
 * ## 失败一律经状态出，不走 `reportFailure` / `sendToast`
 * **本 VM 的命令通道没有任何 binder 在收集**：宿主 `ReadBookActivity` 是
 * `BaseMvvmActivity<BookReadViewModel>`，`MvvmBinder.bind` 只绑页面自己那一个 ViewModel；
 * 本类是换源弹层用 `hiltViewModel()` 起的**第二个** VM，它的 `sendToast` 只会堆在
 * `Channel` 里随 VM 销毁一起丢弃——不报错、不闪退，只是永远不响（AGENTS.md「持有 ViewModel 的页面
 * 必须继承 `BaseMvvmActivity`」那条讲的正是这个静默失效陷阱，`DownloadManageViewModel` 也踩过）。
 * 所以这里**不得**再调 `reportFailure`/`sendToast`：失败原因经 [searchFailure] 与
 * `switchSource` 的 `Result` 交出去，由 [com.ebook.book.reader.SourceSwitchSheet] 在面板内联渲染。
 * 反过来说，若哪天真的给它接上 binder，**必须同时删掉面板里的内联提示**，否则同一件事响两遍。
 */
@HiltViewModel
class SourceSwitchViewModel @Inject constructor(
    private val bookSourceManager: BookSourceManager,
    private val bookRepository: BookRepository,
) : BaseViewModel<NoOpModel>(NoOpModel()) {

    /**
     * 匹配上的候选，**已按匹配度降序**排好。
     *
     * 全局按 `noteUrl` 去重（与聚合搜索同口径：列表以它作 item key，重复 key 会让 Compose 直接抛异常），
     * 但**不按书名+作者去重**——同名同作者来自两个源的两条正是「换哪个源」的备选。
     */
    private val _candidates = MutableStateFlow<List<SearchBookEntity>>(emptyList())
    val candidates: StateFlow<List<SearchBookEntity>> = _candidates.asStateFlow()

    /**
     * 书源进度（「已收到 X/Y 书源结果」）。
     *
     * 每轮开始清零；某条源收到 [AggregateSearchEvent.SourceFinished] 即递增一格，
     * 本轮结束（[AggregateSearchEvent.AllFinished]）时一次收满——收尾判据的理由见 [finishRound]。
     */
    private val _progress = MutableStateFlow(CandidateProgress())
    val progress: StateFlow<CandidateProgress> = _progress.asStateFlow()

    /**
     * 本轮是否还在跑。
     *
     * 与 [CandidateProgress.isRunning] 不重复：后者要等第一条 [AggregateSearchEvent.SourceStarted]
     * 登记到源之后才可能为真，而发起搜索到那一刻之间（查库、拿清单）页面也需要一个加载态。
     */
    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    /**
     * 本轮候选搜索的失败原因（`null` = 没有失败），每轮开始清零。
     *
     * 这是「整条聚合流出问题」（如查书源清单失败）唯一的出口：`searchAcross` 已把**单源**异常收敛成
     * [AggregateSearchEvent.SourceFailed]，走到整流出问题的只剩这一种，而它既没有候选可画、
     * 也不该被画成「没找到其他书源的同一本书」那句空态（那是成功且确实为空）。
     * 交异常而不是交文案：本 VM 不持资源、也不该决定上屏哪句话（选取规则是 UI 侧的纯函数），
     * 面板据此内联渲染一句人话，原因本身留在异常里供日志与将来分流。
     */
    private val _searchFailure = MutableStateFlow<Throwable?>(null)
    val searchFailure: StateFlow<Throwable?> = _searchFailure.asStateFlow()

    /**
     * 当前这一轮聚合搜索。
     *
     * 重开一轮（用户改口再搜、面板重开）时必须先取消上一轮：两轮流同时在跑时，旧轮的
     * [AggregateSearchEvent.SourceResult] 会往已清空的新列表里灌旧关键词的书，进度也会被交错改写。
     * `searchAcross` 里的单源 catch 刻意不咽 [CancellationException]，取消才真的落得下去。
     */
    private var searchJob: Job? = null

    /** 本轮参与聚合的源（收到过 SourceStarted）：进度分母 */
    private val roundSources = mutableSetOf<String>()

    /** 本轮已结束的源：进度分子 */
    private val doneSources = mutableSetOf<String>()

    /**
     * 发起候选搜索：用当前书的 [name] + [author] 搜一遍所有启用书源，排除所属源 [excludeSourceUrl]。
     *
     * 只搜第 1 页：换源面板要的是「最可能对的几条」，翻页会让候选越深越偏，还会成倍放大对第三方
     * 站点的请求量（`searchAcross` 的并发上限是为礼貌度设的，见其 KDoc）。
     * 空结果不构成错误：面板按「没找到其他源有这本书」的空态呈现。
     */
    fun searchCandidates(name: String, author: String, excludeSourceUrl: String) {
        searchJob?.cancel()
        roundSources.clear()
        doneSources.clear()
        _candidates.value = emptyList()
        _progress.value = CandidateProgress()
        _searchFailure.value = null
        _isSearching.value = true
        // 关键词空就没有可比对的对象，直接收工：省掉一轮全站请求
        if (name.isBlank() && author.isBlank()) {
            _isSearching.value = false
            return
        }
        val skip = if (excludeSourceUrl.isBlank()) emptySet() else setOf(excludeSourceUrl)
        searchJob = viewModelScope.launch {
            try {
                bookSourceManager.searchAcross(name, page = 1, skipSourceUrls = skip)
                    .collect { onEvent(it, name, author) }
            } catch (e: CancellationException) {
                // 取消来自新一轮搜索或 VM 销毁：既不是失败也不该收尾，原样上抛让本协程干净退出
                throw e
            } catch (e: Throwable) {
                // searchAcross 已把单源异常收敛成事件，走到这里说明整条流出问题（如查书源清单失败）。
                // 加载态必须收掉，否则面板永远转圈；失败原因留在 searchFailure 里由面板内联渲染
                // （本 VM 的命令通道无人收集，见类 KDoc，不要在这里补 reportFailure）。
                Logger.e(TAG, "换源候选的聚合流异常终止: ", e)
                finishRound()
                _searchFailure.value = e
            }
        }
    }

    /**
     * 执行换源：把 [oldShelf] 换成候选 [newBook]，结果原样交回 [onResult]。
     *
     * **提示出口只有一个：面板**。失败一律经 [onResult] 的 `Result` 交出去，由
     * [com.ebook.book.reader.SourceSwitchSheet] 内联渲染一句并保留弹层（用户手上的处置就是另选一本）。
     * 本方法**不**调 `reportFailure`——那条通道在本 VM 上无人收集（见类 KDoc），
     * 留在这里只会让人以为「失败已经提示过了」而面板其实什么都没显示。
     * 成功时把新条目连同「旧进度在哪、新目录多长」一起包进 [SourceSwitchOutcome] 交回——
     * 「已跳到第 X 章」与「新源章节较少，已落到最后一章」这两句话都需要后者才能说出来，
     * 而那是仓库里才有、UI 不该自己推算的事实。
     *
     * @param oldShelf 阅读器当前正在读的书架条目（换源后它就不存在了）
     * @param newBook 用户在候选里点中的那本
     * @param onResult 成功为 [SourceSwitchOutcome]，失败为异常（本地书 / 目标源失效 / 网络与解析失败）
     */
    fun switchSource(
        oldShelf: BookShelfEntity,
        newBook: SearchBookEntity,
        onResult: (Result<SourceSwitchOutcome>) -> Unit,
    ) {
        viewModelScope.launch {
            val result = bookRepository.switchSource(oldShelf, newBook)
            onResult(
                result.mapCatching { newShelf ->
                    SourceSwitchOutcome(newShelf = newShelf, previousChapter = oldShelf.durChapter)
                }
            )
        }
    }

    /**
     * 单个事件 → 状态更新。每个分支只动自己那一份集合，交错到达的多个源不会互相覆盖。
     */
    private fun onEvent(event: AggregateSearchEvent, name: String, author: String) {
        when (event) {
            is AggregateSearchEvent.SourceStarted -> {
                roundSources += event.sourceUrl
                publishProgress()
            }

            is AggregateSearchEvent.SourceResult -> mergeCandidates(event, name, author)

            is AggregateSearchEvent.SourceFailed ->
                // 只记日志：进度由 Finished 收，面板只在「一条候选都没有」时给空态
                Logger.w(TAG, "换源候选某书源失败: ${event.sourceUrl}", event.error)

            is AggregateSearchEvent.SourceFinished -> {
                doneSources += event.sourceUrl
                publishProgress()
            }

            AggregateSearchEvent.AllFinished -> finishRound()
        }
    }

    /**
     * 并入某源这一页的候选：滤掉不匹配的 → 按 `noteUrl` 去重 → 重排。
     *
     * 排序每次都按整份列表重算（而不是只把新到的一条插进去）：新到的高分候选必须能插到队首，
     * 否则「最像的那本」会永远沉在它所属源到达的位置上。`sortedByDescending` 是稳定排序，
     * 所以**同分者的次序就是它们的到达次序**——确定、可复现，不会每次刷新换一个排法。
     */
    private fun mergeCandidates(event: AggregateSearchEvent.SourceResult, name: String, author: String) {
        val loaded = _candidates.value.mapTo(mutableSetOf()) { it.noteUrl }
        val fresh = event.books
            .filterNot { it.noteUrl in loaded }
            .distinctBy { it.noteUrl }
            .filter { matchScore(it, name, author) > 0 }
        if (fresh.isEmpty()) return
        _candidates.value = (_candidates.value + fresh).sortedByDescending { matchScore(it, name, author) }
    }

    /**
     * 一轮结束：进度一次收满、加载态收掉。
     *
     * **`AllFinished` 才是「本轮结束」的判据**，不是「Finished 计数 == Started 计数」（P3-b 已确立的
     * 契约）：某一路源的内部协程被取消时该源连 `SourceFinished` 都不发，只数 Finished 会让进度
     * 永远差一格、面板上的进度行不消失。
     */
    private fun finishRound() {
        doneSources += roundSources
        publishProgress()
        _isSearching.value = false
    }

    /** 进度 = 本轮已结束源数 / 本轮参与源数（清零与递增都只在这一处出口，UI 只读不猜） */
    private fun publishProgress() {
        _progress.value = CandidateProgress(finished = doneSources.size, total = roundSources.size)
    }

    /**
     * 候选与当前书的匹配度打分（ADR-0016 决策 8 的排序依据）。
     *
     * 五档：书名+作者完全相同 **100** > 书名相同 **80** > 书名互相包含 **50** > 仅作者相同 **20** >
     * 其他 **0**（0 分不入候选）。
     *
     * 比的是 [CommentKey.normalize] 之后的形态，不是原文：第三方站点的书名常带《》、全角空格与
     * 繁简混排，逐字比会让最像的那本掉到 50 分甚至 0 分。归一化与评论键同源（同一套「是不是同一
     * 作品」的判据），不另立一套。
     * 作者一档要求两边归一化后**非空**：两本站点都把未知作者写成空串时，「作者相同」不成立——
     * 那只是「谁都不知道作者是谁」，给它 20 分会让一堆不相干的书挤进候选。
     *
     * `internal`：换源面板要按分数画「完全匹配 / 部分匹配」标签，UI 与本类同模块，不外露。
     */
    internal fun matchScore(book: SearchBookEntity, name: String, author: String): Int {
        val candidateName = CommentKey.normalize(book.name)
        val candidateAuthor = CommentKey.normalize(book.author)
        val wantedName = CommentKey.normalize(name)
        val wantedAuthor = CommentKey.normalize(author)
        val nameEquals = wantedName.isNotEmpty() && candidateName == wantedName
        val authorEquals = wantedAuthor.isNotEmpty() && candidateAuthor == wantedAuthor
        val nameContains = wantedName.isNotEmpty() && candidateName.isNotEmpty() &&
            (candidateName.contains(wantedName) || wantedName.contains(candidateName))
        return when {
            nameEquals && authorEquals -> 100
            nameEquals -> 80
            nameContains -> 50
            authorEquals -> 20
            else -> 0
        }
    }
}

/**
 * 换源候选的聚合进度：[total] 为本轮参与聚合的书源数，[finished] 为其中已结束的数量。
 *
 * 与 `module_find` 的 `SearchProgress` 同形但不复用：功能模块之间不互相依赖（AGENTS.md 模块架构），
 * 跨模块 import 会直接编译不过。真出现第三处需求时应把它上浮到 `lib_book_common`
 * （共享件上浮两步走的口径），而不是让某模块去依赖另一模块。
 */
data class CandidateProgress(val finished: Int = 0, val total: Int = 0) {

    /** 是否还在跑：已开始（有源登记）且还有源没结束 */
    val isRunning: Boolean get() = total > 0 && finished < total

    /** 进度条比例。[total] 为 0 时按 0 收，避免除零 */
    val fraction: Float get() = if (total == 0) 0f else finished.toFloat() / total.toFloat()
}

/**
 * 一次换源成功的现场：把 UI 要说清那两句话所需的事实一次给全。
 *
 * - 「已跳到第 [targetChapter] 章」→ [targetChapter] 是**0 基**章序号（与 `chapter_list.dur_chapter_index`
 *   同口径），上屏要 +1；
 * - 「新书源章节较少，已落到最后一章」→ [clamped] 为真时用这句替换上一句。它成立当且仅当旧进度
 *   超出了新目录长度（映射 `min(旧, 新总数-1)` 因此被截断）。
 *
 * [chapterCount] 取自新条目的内存字段：换源事务刚用它写库，仓库把同一个实例交回来，
 * 所以这里读到的是**本次新抓的目录**长度，不必也不该再查一次库（查得到的是落库后的行，
 * 而 `@Ignore chapterList` 本就不落库，重查只会拿到空表）。
 * `chapterCount == 0` 表示新源这一本的目录压根没解出来——换源在库内已经成功，但阅读器翻不出内容，
 * UI 该把它当成一次可疑的结果提示出来（用户可回到面板另选一本），而不是假装换源正常。
 *
 * @param newShelf 换好后落在 book_shelf 上的条目（`durChapter` 已是映射结果）
 * @param previousChapter 换源前正在读的章序号（旧源口径，0 基）
 */
data class SourceSwitchOutcome(
    val newShelf: BookShelfEntity,
    val previousChapter: Int,
) {

    /** 新源目录总章数 */
    val chapterCount: Int get() = newShelf.chapterList.size

    /** 映射后的目标章序号（0 基，上屏 +1） */
    val targetChapter: Int get() = newShelf.durChapter

    /** 旧进度是否被新目录截断，即本次是否落到了新源的最后一章 */
    val clamped: Boolean get() = chapterCount > 0 && previousChapter > chapterCount - 1
}
