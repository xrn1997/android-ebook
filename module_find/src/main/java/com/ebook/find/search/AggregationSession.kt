package com.ebook.find.search

import com.ebook.db.entity.SearchBookEntity

/**
 * 一轮聚合搜索的请求计划：[page] 是本轮所有活跃源请求的页码，[skipSourceUrls] 是本轮不再发请求的已结束源。
 *
 * 页码与排除集都取自**上一轮**的观测（见 [AggregationSession.beginRound]），计划一旦返回就不再随本轮
 * 事件变化：本轮晚到的结束信号按 [page] 推进游标，不会因为别的源先结束而把页码改掉。
 */
internal data class RoundPlan(
    val keyword: String,
    val page: Int,
    val skipSourceUrls: Set<String>,
)

/**
 * [AggregationSession.finishRound] 的结局。
 *
 * [allFailed] 决定宿主进错误态还是照常展示已有结果；[hasMore] 只看「是否还有没结束的源」。
 * 空源集合不算失败（没有源可失败），但也没有更多。
 */
internal data class RoundOutcome(
    val allFailed: Boolean,
    val hasMore: Boolean,
    val progress: SearchProgress,
)

/**
 * 聚合搜索的书源进度：[total] 为本轮参与聚合的书源数，[finished] 为其中已结束的数量。
 *
 * 收尾有两处出口，缺一不可：
 * - [AggregationSession.onSourceFinished] 到达时逐源递增——成功、失败与空页都走这里，
 *   所以进度不会因某个站点挂掉而永远差一格；
 * - [AggregationSession.finishRound] 收到整轮结束信号时把本轮一次收满——兜住「某一路协程
 *   被取消、连结束信号都没发出去」这一种，否则进度行的 [isRunning] 永不为假、在页面上不消失。
 *
 * 公开是因为宿主的 `searchProgress` 以它作公开 `StateFlow` 的元素类型；会话本身仍是模块内部实现。
 */
data class SearchProgress(val finished: Int = 0, val total: Int = 0) {

    /** 是否还在跑：已开始（有源登记）且还有源没结束 */
    val isRunning: Boolean get() = total > 0 && finished < total

    /** 进度条比例。[total] 为 0 时按 0 收，避免除零 */
    val fraction: Float get() = if (total == 0) 0f else finished.toFloat() / total.toFloat()
}

/**
 * 一次聚合搜索会话：每源游标、结束集、已见条目与单轮簿记的唯一所有者。
 *
 * 它只算协议，不碰列表、书架状态与覆盖层——宿主把聚合搜索事件翻译成这里的调用，再按
 * [RoundPlan]/[RoundOutcome] 发请求、渲染页面。因此没有任何 Android 或协程依赖，纯 JVM 可测
 * （见同包的 `AggregationSessionTest`）。
 *
 * 两层生命周期：
 * - **会话级**（[keyword]、游标、结束集、已见 noteUrl）：跨轮存活，只在 [startSearch] 换关键词时整块重置；
 * - **轮级**（本轮簿记）：[beginRound] 每轮整块换新，上一轮的参与源、失败集与新增计数不可能漏清。
 *
 * 结束一个源的判据是 `hasMore && fresh > 0`：越界页会以 HTTP 200 重复返回首页书目（软 404），
 * 那种页有结果却零新增，只看 `hasMore` 会永远翻不完。已见条目记的是**所有返回过的** noteUrl
 * （含当页重复），这是「每条见过的条目都记账」的直接表达，不是额外防线：`distinctBy` 已按
 * noteUrl 去重，记 fresh 与记全部对已见集合等价。
 *
 * 非线程安全：由单一收集协程驱动（当前为 ViewModel 的主调度器收集）。
 */
internal class AggregationSession {

    /** 当前搜索关键词；[beginRound] 原样带进 [RoundPlan] */
    var keyword: String = ""
        private set

    /** 每源「下一次该请求的页码」。按源存而不只存一个全局值：`hasMore` 是每源各自的观测 */
    private var pageBySource = mutableMapOf<String, Int>()

    /** 跨轮已结束的源（到底 / 失败 / 去重后没带来新条目）；下一轮经 `skipSourceUrls` 排除 */
    private var finishedSources = mutableSetOf<String>()

    /** 本会话返回过的全部 noteUrl；当页重复的条目也逐条录入（集合自然去重）。换关键词即清空，同一本书换词后可以重新出现 */
    private var seenNoteUrls = mutableSetOf<String>()

    /** 本轮簿记；[beginRound] 换新之后，上一轮的任何登记与计数都不可能再被读到 */
    private var round = RoundBookkeeping(page = FIRST_PAGE)

    /** 本轮进度 = 本轮已结束源数 / 本轮参与源数；宿主只读不猜 */
    val progress: SearchProgress
        get() = SearchProgress(finished = round.done.size, total = round.sources.size)

    /** 是否还有可继续翻页的源；宿主据此决定「加载更多」发不发请求 */
    val hasActiveSources: Boolean
        get() = activeSources().isNotEmpty()

    /**
     * 换关键词：整块重置会话级状态与本轮簿记。
     *
     * 四处都是整份换新而不是逐项 `.clear()`——漏清一处不会报编译错，只会让上一个关键词的游标、
     * 结束源或已见条目混进新搜索；赋值式重置让「只清一半」在结构上不可能。
     */
    fun startSearch(keyword: String) {
        this.keyword = keyword
        pageBySource = mutableMapOf()
        finishedSources = mutableSetOf()
        seenNoteUrls = mutableSetOf()
        round = RoundBookkeeping(page = FIRST_PAGE)
    }

    /**
     * 起一轮：按**上一轮**的存活源现算页码与排除集，然后换新本轮簿记。
     *
     * 顺序不能反：换新之后再读上一轮簿记只会读到一份空的，页码退化成 1、排除集也不再反映任何
     * 已结束的源。页码取活跃源游标的最小值兜住理论上的分叉；还没有任何活跃源时按第一页。
     */
    fun beginRound(): RoundPlan {
        val active = activeSources()
        val page = active.minOfOrNull { pageBySource[it] ?: FIRST_PAGE } ?: FIRST_PAGE
        val skip = finishedSources.toSet()
        round = RoundBookkeeping(page = page)
        return RoundPlan(keyword = keyword, page = page, skipSourceUrls = skip)
    }

    /** 登记本轮参与源；首次见到的源把本轮页码记成它的游标 */
    fun onSourceStarted(sourceUrl: String) {
        round.sources += sourceUrl
        // 加载更多时该源已有游标（本轮请求的就是那一页），保留原值
        pageBySource.putIfAbsent(sourceUrl, round.page)
    }

    /**
     * 收下某源这一页的结果，返回其中**全局未见过的**条目（页内按 noteUrl 去重）。
     *
     * 已见集合录入所有返回条目的 noteUrl（含页内重复），是「每条见过的条目都记账」的直接表达；
     * `fresh` 已按 noteUrl 去重，记 fresh 与记全部对已见集合等价。
     * 空列表也显式记 0，否则 [onSourceFinished] 会把「没发结果」与「没发新结果」混为一谈。
     */
    fun onSourceResult(sourceUrl: String, books: List<SearchBookEntity>): List<SearchBookEntity> {
        val fresh = books.filterNot { it.noteUrl in seenNoteUrls }.distinctBy { it.noteUrl }
        books.mapTo(seenNoteUrls) { it.noteUrl }
        round.fresh[sourceUrl] = fresh.size
        return fresh
    }

    /** 记本轮失败：只用于「全部源都失败」的判定，不结束本轮，也不影响其他源的结果 */
    fun onSourceFailed(sourceUrl: String) {
        round.failed += sourceUrl
    }

    /**
     * 收下某源的结束信号，决定它还能不能翻页。
     *
     * 只有 `hasMore && fresh > 0` 才推进游标（软 404 的整页重复在这里判结束）；其余情况——到底、
     * 失败、空页、零新增——都进结束集。无论哪种都记入本轮 `done`，进度因此不会差分。
     */
    fun onSourceFinished(sourceUrl: String, hasMore: Boolean) {
        val hasFresh = (round.fresh[sourceUrl] ?: 0) > 0
        if (hasMore && hasFresh) {
            pageBySource[sourceUrl] = round.page + 1
            finishedSources -= sourceUrl
        } else {
            finishedSources += sourceUrl
        }
        round.done += sourceUrl
    }

    /**
     * 一轮收尾：把本轮已登记却没等到结束信号的源补进 `done`（取消路径没有结束信号），
     * 再给出「是否全部失败」与「还有没有更多」。
     *
     * [fatal] 非空表示整条流出问题（没有任何源给出可归因的事件），按全部失败处置。
     * 空源集合既不算失败也没有更多：没有源可失败，也没有可翻的源。
     */
    fun finishRound(fatal: Throwable?): RoundOutcome {
        round.done += round.sources
        val allFailed = fatal != null ||
            (round.sources.isNotEmpty() && round.failed.size == round.sources.size)
        return RoundOutcome(
            allFailed = allFailed,
            hasMore = !allFailed && activeSources().isNotEmpty(),
            progress = progress,
        )
    }

    /** 还能继续翻页的源：本轮参与过、且尚未结束 */
    private fun activeSources(): Set<String> = round.sources - finishedSources

    /**
     * 单轮簿记：本轮参与的源（进度分母）、已结束的源（进度分子）、失败源、各源新增条目数与本轮页码。
     *
     * 与游标分开是**生命周期不同**：游标跨轮存活，这一坨每轮由 [beginRound] 整块换新。
     */
    private class RoundBookkeeping(
        val page: Int,
        val sources: MutableSet<String> = mutableSetOf(),
        val done: MutableSet<String> = mutableSetOf(),
        val failed: MutableSet<String> = mutableSetOf(),
        val fresh: MutableMap<String, Int> = mutableMapOf(),
    )

    private companion object {
        /** 首轮，或没有活跃源可参考游标时的页码 */
        const val FIRST_PAGE = 1
    }
}
