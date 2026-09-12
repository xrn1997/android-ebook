package com.ebook.source.analyze

import com.ebook.db.entity.SearchBookEntity

/**
 * 聚合搜索（一次搜全站，见 ADR-0016）的进度事件。
 *
 * 存在的理由：聚合搜索不是一份「结果数组」，而是一串**按书源分批到达**的增量——每个第三方站点的
 * 快慢与死活都独立，用 `suspend fun …: List<…>` 表达就只能等最慢的那条源，一条挂掉的源还会把
 * 其它源已经拿到的结果一起吞掉。事件流让调用方可以边收边渲染，并知道「谁还在跑」。
 *
 * **一条源的事件恒为** [SourceStarted] → （[SourceResult] 或 [SourceFailed]）→ [SourceFinished]。
 * 收尾的 [SourceFinished] 在失败路径上**同样会发**，这不是冗余而是本契约的核心：
 * UI 的进度（「已收到 X/Y 书源结果」）靠 Finished 递增，若失败时改发 Failed 就没了 Finished，
 * 进度条会永远差一格，用户看到的是一直通不完的对冲。Failed 只负责「为什么少了这一格」的归因与日志，
 * **不承担收进度的职责**（调用方据此实现「部分失败照常展示、全部失败才进错误态」）。
 *
 * 事件按书源分组到达，但**不同源的条目会交错**（并发上限内的多路流共用一个下游），
 * 故调用方不能假定「一条源发完才轮到下一条」。
 */
sealed class AggregateSearchEvent {

    /**
     * 某条书源开始解析。
     *
     * 它是本轮「参与聚合的源」的登记点：调用方据此知道总共有几条源在跑（[sourceUrl] 即该源的归属，
     * 与 [SearchBookEntity.tag] 同值），也据此把 [sourceName] 显示给用户。
     */
    data class SourceStarted(val sourceUrl: String, val sourceName: String) : AggregateSearchEvent()

    /**
     * 某条书源返回该页结果。
     *
     * 每条 [SearchBookEntity] 的 `tag`/`origin` 已由解析器写成该源的 URL 与名称，调用方**不要覆写**：
     * 多源结果汇成一份列表后，那两个字段就是「这条书该按哪套规则继续解析」的唯一依据。
     * 空列表也是合法值（该源这一页没结果），它配合 [SourceFinished] 表达「该源到底」。
     */
    data class SourceResult(val sourceUrl: String, val books: List<SearchBookEntity>) : AggregateSearchEvent()

    /**
     * 某条书源本轮失败。
     *
     * 只影响该源，**不会终止聚合流**——一条源挂了不能把其它源的结果一起带走。
     * 紧随其后的必有该源的 [SourceFinished]（`hasMore = false`）。
     *
     * 注意别把它当成「该源不可用」的唯一信号：书源解析器（`JsoupBookParser.searchBook`）
     * 内部已把网络异常吞成空列表，因此远端 500、超时、DNS 失败这类问题在这里表现为
     * [SourceResult] 带空列表 + [SourceFinished]`（hasMore = false）`，而不是本事件。
     * 本事件覆盖的是「取不到该源的 parser」与解析器真的把异常抛出来的情形。
     */
    data class SourceFailed(val sourceUrl: String, val error: Throwable) : AggregateSearchEvent()

    /**
     * 某条书源本轮结束（成功、空页或失败都会发，见类注释）。
     *
     * [hasMore] 只是**本源视角**的「该页有结果，下一页可能还有」：空页为 false。
     * 它不足以判「到底」——笔趣阁式站点对越界页返回 **HTTP 200 + 首页书目**（软 404），
     * 这类页有结果、`hasMore` 会是 true，真到底要由调用方按 [SearchBookEntity.noteUrl] 去重后
     * 看「这一页有没有带来新条目」来定（AGENTS.md「列表分页」那条铁律），据此把该源从翻页集合里摘掉。
     */
    data class SourceFinished(val sourceUrl: String, val hasMore: Boolean) : AggregateSearchEvent()

    /**
     * 本轮聚合到此结束，流随之下游完成。常规路径下每条参与的源都已发过 [SourceFinished]。
     *
     * **一个必须知道的例外**：某一条源的内部协程被取消时（单源 catch 刻意原样上抛
     * [kotlinx.coroutines.CancellationException]，`flatMapMerge` 因此把整路丢弃），该源**连
     * [SourceFinished] 都不会发**，而本事件仍会照常到达。所以「本轮是否真的结束了」只能以本事件为准，
     * **不要拿「Finished 计数 == Started 计数」当判据**——那样进度会永远差一格。
     *
     * 调用方在这里收全局态：是否还有可翻的页（存在 `hasMore` 为真的源）、覆盖层要不要落下、
     * 是不是所有源都失败了该给错误态。零条启用源时本事件是**唯一**收到的事件。
     */
    data object AllFinished : AggregateSearchEvent()
}
