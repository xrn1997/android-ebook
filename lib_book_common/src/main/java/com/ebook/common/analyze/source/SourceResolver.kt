package com.ebook.common.analyze.source

import com.ebook.source.analyze.AggregateSearchEvent
import kotlinx.coroutines.flow.Flow

/**
 * 取源面：按书源 URL 取解析器、聚合搜索与书城分类条目。
 *
 * **解析一律跟书走**：每本书经 `tag` 绑定归属源，本面按那份归属取 parser，不读全局默认源
 * （默认源只服务书城首页，见 [SourceCatalog.observeDefaultSource]）。多书源下按全局默认源解析一本书
 * 不会闪退，只会拉回一本不相干的书或空目录——错数据比崩溃难查得多。
 *
 * 取源结果用四档 [SourceBinding] 表达（见 [resolveSource]）；只要 parser 的调用方走 [parserOrThrow]
 * 翻译一次，不再各自重推四档。脚本书源取 parser 永不抛，规则求值失败是公开的
 * `ScriptRuleException` 子类（ADR-0029/0045），不得折叠成 [SourceBinding.Broken]。
 */
interface SourceResolver {

    /**
     * 按书源 URL 解析出「本地 / 找到 / 缺失 / 坏行」四档结果（见 [SourceBinding]）。
     *
     * 与旧 `getParserFor(): BookParser?` 的 null 契约相比，四档互斥且可区分：
     * - [SourceBinding.Local]：本地书 tag，不归属任何书源，不是故障；
     * - [SourceBinding.Missing]：空白 tag 或库里没有这行（源被删 / 脏 tag）；
     * - [SourceBinding.Broken]：行在，但原生 `rule_json` 解不出来（降级或手工改库的脏行）；
     * - [SourceBinding.Found]：真解析器。脚本行取 parser **永不抛**，坏 JSON 留到首次求值，
     *   抛公开的 `ScriptRuleException` 子类（ADR-0029/0045），不得折叠成 [SourceBinding.Broken]。
     *
     * 需要区分四档的调用方对这个结果做一次模式匹配；只要 parser 的调用方走 [parserOrThrow]，
     * 不再各自重推四档。
     */
    suspend fun resolveSource(sourceUrl: String): SourceBinding

    /**
     * 聚合搜索（一次搜全站）：对每条**启用中**的书源并发解析该页，按 [AggregateSearchEvent] 增量吐出。
     *
     * 集合取自**启用中的两种格式行**（经条目面读，故脚本书源也参与，见
     * [BookSourceManagerImpl.searchAcross]），每条源经 [resolveSource] 拿自己的 parser，
     * 于是 `SearchBookEntity.tag`/`origin` 天然写成该书真正的归属源（解析器负责写，见 [AggregateSearchEvent.SourceResult]）。
     *
     * **并发上限 5**：一条源一次请求打的是第三方站点，同时给 10+ 个站点发同一关键词极易被判定为
     * 爬虫而触发风控（封 IP / 验证码），代价由用户承担而不是由本机承担。5 是「一次搜全站的体感并行度」
     * 与「对第三方站点的礼貌度」之间的取值，源少于 5 条时等同于全并发。
     *
     * **单源失败绝不让聚合流终止**：任何一条源抛出的异常（含取不到 parser 这种理论上不该发生的
     * [IllegalStateException]）都被收敛成该源的 [AggregateSearchEvent.SourceFailed] +
     * [AggregateSearchEvent.SourceFinished]`（hasMore = false）`，其它源的结果照常到达。
     * 反过来，取消 [kotlinx.coroutines.CancellationException] 一律原样上抛——调用方取消整条流
     * （换关键词重搜）就是该终止，把它咽下去会让旧轮次继续往新结果里灌。
     *
     * 「到底」不由本方法判定：[AggregateSearchEvent.SourceFinished.hasMore] 只说「本源该页有结果」，
     * 而越界页会以 HTTP 200 重复返回首页书目（软 404），所以真正的收尾判据在调用方——
     * 按 [com.ebook.db.entity.SearchBookEntity.noteUrl] 去重后「这一页没带来新条目」即视该源已结束，
     * 下一轮把它列进 [skipSourceUrls]。
     *
     * 全部源都发完 [AggregateSearchEvent.SourceFinished] 后发 [AggregateSearchEvent.AllFinished]，
     * 流随之下游完成。零条启用源时 [AggregateSearchEvent.AllFinished] 是唯一的事件。
     *
     * **本方法是冷流**：每次收集才查库、才发请求，收集结束即停；不做共享，多个收集者就是多轮全站请求。
     *
     * @param keyword 搜索关键词，原样交给各源的解析器（URL 编码由解析器按各源字符集处理）
     * @param page 本轮向每条参与源请求的页码。**各源页码同步推进**：一条源只在「该页有结果」时
     *   才活到下一轮，所以仍在跑的源此刻的下一页必然同号——这也是本方法能用单个 [page]
     *   而不是收一份「每源各自的页码」的原因
     * @param skipSourceUrls 本轮不再请求的书源 URL（通常是已判定到底或已失败的源）。
     *   默认空集 = 全部启用源参与。**这个参数是为了让「每源独立游标」真的成立**：Manager 不持有
     *   搜索会话状态（它跨页面、跨关键词，任何「记住上一轮」都会长出第二个事实源），
     *   于是「哪些源已经到底」这个只有调用方知道的事实必须由调用方带进来，
     *   否则下一轮会对已结束的源重复发请求。
     */
    fun searchAcross(
        keyword: String,
        page: Int,
        skipSourceUrls: Set<String> = emptySet(),
    ): Flow<AggregateSearchEvent>

    /**
     * 书城分类条目（分类胶囊的数据源）：按 [sourceUrl] 那一行的格式路由读出「标题 + 分类地址」。
     *
     * - 原生行：`ruleFind.kinds` 逐项映射（url 是模板/分类标识，由解析器在 `getKindBook` 里渲染）；
     * - 脚本行：`exploreUrl` 条目切分（`com.ebook.source.script.ScriptExplore`），url 承载
     *   **URL 规则串**（可含 `{{page}}`），由脚本解析器按脚本 URL 语义渲染——消费方对两种出身
     *   拿到的是同一个形状。
     *
     * 空白 [sourceUrl]、库里没有该行、原生行 `rule_json` 解不出（脏行），都返回空列表；
     * 脚本行的 `rule_json` 坏掉时抛类型化 `ScriptRuleParseException`（消息含「脚本书源 JSON 无法解析」），
     * 调用方（书城 VM）按「加载失败给空列表」兜底。**纯解析零网络**：分类胶囊不能因为渲染就发请求。
     */
    suspend fun getExploreEntries(sourceUrl: String): List<SourceExploreEntry>
}
