package com.ebook.common.analyze.source

import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.SourceDefinition
import com.ebook.api.entity.SourceFormat
import com.ebook.source.analyze.AggregateSearchEvent
import com.ebook.source.analyze.BookParser
import kotlinx.coroutines.flow.Flow

/**
 * 书源管理器：多书源共存清单的唯一入口（见 ADR-0016）。
 *
 * 职责边界：本接口是 `book_source` 表（[com.ebook.db.dao.BookSourceDao]）之上唯一的书源读写面，
 * 业务模块**不直调该 DAO**——规则内容整块存在 `rule_json` 列里，SQL 侧筛不到，只有这里能把它
 * 还原成 [BookSourceRule]，绕开这层就等于拿到一份没经实体列覆盖的脏规则。
 *
 * **本接口不再有同步读面**：应用不随包携带任何书源，启动时没有任何可同步给出的默认源，
 * 清单与默认源一律经 Room 获得（挂起读与 Flow）。默认源走 [observeDefaultSource] 订阅，
 * 别为了「首帧不空白」重新修一条同步出口——同步出口要么靠随包资产、要么靠第二份内存缓存，
 * 两者都是在 Room 之外另造一处事实源；而清单与默认源的真值只有 Room 这一处，只它自己说了算。
 *
 * 历史上同步面还有两个兜底 parser 出口（`currentParser` / `requireParser`）：ADR-0016 P3-a/P3-b
 * 把解析路径逐条迁到「按 `tag` 取源」与聚合搜索之后它们已无调用方、随之一并删除。
 * 别把「同步拿个 parser 用」这条路重新修回来——多书源下按全局默认源解析一本书
 * 不会闪退，只会拉回一本不相干的书或空目录（错数据比崩溃难查得多）。
 *
 * **读面返回的类型按「谁消费」分工，这个不对称是刻意的**：
 * [getAllSources]、[getEnabledSources]、[getSourceByUrl] 三个**规则类型化读面**只给 [BookSourceRule]，
 * [observeDefaultSource] 给 [SourceDefinition]?（默认源不限于原生规则，理由见下段），
 * 而面向书源管理页的 [observeSources] 给 [BookSourceItem]（规则 + `format`）。
 * 理由一句话：**解析链路只关心规则，只有面向用户的管理页需要出身元数据**——页面要先看 `format`
 * 才知道这一行该渲染成什么（脚本行没有可用规则、要不要给导出入口也看它），而那个事实只住在
 * `book_source.format` 列里，不该为了它污染规则对象（判据与代价见 [BookSourceItem]）。
 * 后来者注意：书城顶部切换器（ADR-0016 P3-c 已落地）订阅清单时取的是 `item.rule`，
 * 并当场把禁用项滤掉——**别把 `format` 继续沿链路往下带**——元数据一旦出管理页就开始
 * 长出第二个用途，到时要改的就是全链路的签名，而不是这里多写的一句话。
 *
 * **规则类型化读面与默认源载体的分工（2e 起）**：[getAllSources]、[getEnabledSources] 与
 * [getSourceByUrl] 仍**只认原生规则书源**——脚本的社区 JSON 硬解成 [BookSourceRule] 有两种结局，
 * 都不是「可用规则」（键名对不上解出空规则静默零条目；同名不同形的键直接抛解码异常），理由见
 * [BookSourceManagerImpl.toRule]。但**默认源不再是原生专属**：[observeDefaultSource] 以
 * [SourceDefinition] 为载体，脚本行同样有资格被立为默认源；
 * 脚本书源仍在 [observeSources]（条目带 `format`）、[getParserFor]（真解析器）、
 * [getFormatByUrl]（出身查询）与 [getExploreEntries]（分类条目）四处现身。
 *
 * **关于「有没有可用默认源」只有一个答案**：Room 里没有任何 enabled 行（用户把源全禁用了）时
 * [observeDefaultSource] 发 null。调用方（书城）此时应展示「请先启用或导入书源」的引导态；
 * 聚合搜索（[searchAcross]）则天然收到零条源，
 * 由页面把「没有可用书源」呈现成空结果而不是错误。
 */
interface BookSourceManager {

    /**
     * 全部书源（含被禁用的），按 `weight ASC, addedAt ASC, url ASC`；读 Room。
     *
     * **空表就是「零书源」**：应用不随包携带任何书源，表里每一行都来自用户导入，所以读到空表
     * 只说明用户还没导入过（或库被外部清空过）。调用方一律按「无可用书源」走引导态即可。
     *
     * **只含原生规则书源**：脚本书源（`format = script`）的行会被跳过（见 [getSourceByUrl] 的同条说明），
     * 库里只有脚本书源时这里就是空清单，不等于「一个源都没有」——要按格式看清单请走 [observeSources]。
     */
    suspend fun getAllSources(): List<BookSourceRule>

    /**
     * 启用中的书源，排序同 [getAllSources]；读 Room。
     *
     * **当前无生产调用方**，保留为规则类型化读面的一员（与 [getAllSources]、[getSourceByUrl] 同族，
     * 供将来「只要原生规则」的场景取用）。三条实况链路都不经过它：书城切换候选走 [observeSources]
     * 的 enabled 过滤；默认源回落走实现类的 `defaultFromRows`（读全量行后在条目面筛 `enabled`，
     * 因为候选还要先经解码挡下坏行）；聚合搜索读条目面（见 [searchAcross]）。
     *
     * 与 [getAllSources] 同为**只含原生规则书源**：脚本书源的 `rule_json` 是社区格式原始 JSON，
     * 按 [BookSourceRule] 硬解只会得到一条键名全对不上的空规则，所以本面按格式挡下它们。
     */
    suspend fun getEnabledSources(): List<BookSourceRule>

    /**
     * 按 URL 精确取单个书源；未收录返回 null。禁用与否不影响本查询（禁用不切断归属）。
     *
     * **null 就是「库里真没有这行」**。
     *
     * **null 还有第二种成因：那一行是脚本书源**（`format = script`）。本方法的返回类型是
     * [BookSourceRule]，而脚本书源的 `rule_json` 是社区格式的原始 JSON，硬解只会得到一条键名全对不上的
     * 空规则（每字段带默认值，连报错都不会），所以这里宁可不给。要区分「没这行」与「这行不是原生格式」
     * 走 [getFormatByUrl]（只问一行的出身，导入预览用的就是它），已经拿着整份清单时才看
     * [observeSources] 条目上的 `format`，
     * **不要**把 null 一律当成「书源已失效」报给用户。
     */
    suspend fun getSourceByUrl(url: String): BookSourceRule?

    /**
     * 按书源 URL 取解析器（带 LRU 缓存）。「每本书绑源」的落点。
     *
     * null 的成因是封闭的，下面逐条列出三种，**调用方自己决定怎么处置**
     * （前两种一般是抛 [BookSourceNotFoundException]）：
     * 1. [sourceUrl] 是空白，或等于 [com.ebook.db.entity.BookShelfEntity.LOCAL_TAG]（`"loc_book"`）——
     *    本地书不归属任何书源，压根不查库；
     * 2. 该 URL 在库里没有行（用户删了这个源，或这条 `tag` 本就是脏数据）；
     * 3. 行存在但 `rule_json` 解不出来（版本降级或手工改库留下的脏行），实现按行跳过并记 ERROR 日志。
     *
     * **脚本书源（`format = script`）不在这三种里，也绝不返回 null**：拿到的是真解析器
     * `com.ebook.source.analyze.ScriptBookParser`（2d 落地），它的规则装载是**惰性**的，
     * 所以取 parser 这一步对脚本行既不为 null 也不抛。坏 `rule_json` 的行在**首次求值**才抛类型化
     * 装载失败（消息含「脚本书源 JSON 无法解析」），含 JS 段或使用本项目不支持能力的规则同样在
     * 求值期抛类型化异常。
     * 这条区分仍是本方法契约的一部分：null 说的是「没有这行/这行坏了」，类型化异常说的是
     * 「源在、这条规则解不动」，混用的话用户会被提示去重新导入一个本就导入成功的书源。
     *
     * 注意取到 null **不等于**「书源被删」这个结论可以套到所有 `tag` 上：本地书的 `tag` 在此表里
     * 永无对应行（见 [com.ebook.db.entity.BookSourceEntity.url] 的例外说明）。
     */
    suspend fun getParserFor(sourceUrl: String): BookParser?

    /**
     * 聚合搜索（一次搜全站）：对每条**启用中**的书源并发解析该页，按 [AggregateSearchEvent] 增量吐出。
     *
     * 集合取自**启用中的两种格式行**（经条目面读，故脚本书源也参与，见
     * [BookSourceManagerImpl.searchAcross]），每条源经 [getParserFor] 拿自己的 parser，
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
     * 流随之下游完成。零条启用源时 [AllFinished] 是唯一的事件。
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
     * 新增或覆盖书源（主键 `url` 命中即 REPLACE）。
     *
     * 覆盖是刻意语义：社区书源 JSON 通常整包重发，用户重导即更新规则。
     * 失败（URL 空白、写库异常）经 [Result.failure] 返回，不抛异常。
     *
     * 落库成功后失效该 URL 的 parser 缓存；当前无可用默认源时，新导入的启用源会被提升为默认源。
     */
    suspend fun addSource(rule: BookSourceRule): Result<Unit>

    /**
     * 新增或覆盖脚本书源（主键 `url` 命中即 REPLACE），[rawJson] 原样落 `rule_json`，`format` 落 `script`。
     *
     * 与 [addSource] 同一套不变式：落库成功后失效 parser 缓存、无默认源时提升为默认源。
     * **不做格式内容校验**——校验与警示归导入 UI（是否含可执行代码、依赖登录等），本方法只负责存储。
     * 要在落库前读出顶层字段（预览、警示）请走 [SourceStorageJson.parseOrNull]：它与本方法共用同一个
     * 解码器，于是「预览判解得开」与「这里存得下来」是同一个接受集，不会两边各说一套。
     * [rawJson] 必须含 `bookSourceUrl` 键（判别约定见 [com.ebook.api.entity.SourceFormatDetector]）；
     * 名称与 URL 从 JSON 顶层读取，缺任一即 [Result.failure]。
     *
     * 「无默认源时提升为默认源」这一条对脚本行**同样成立**（2e 起）：默认源候选是条目面的
     * 全部启用行、不再筛格式，库里没有可用默认源时导入一条启用的脚本源会把它立为默认源。
     */
    suspend fun addScriptSource(rawJson: String): Result<Unit>

    /**
     * 按已入库书源的 URL 查格式出身；不存在返回 null。
     * 导入预览的「将覆盖」判定与格式显示经此查询，**不要**用 [getSourceByUrl]（它只对 native 行有值）。
     *
     * null 因此只说「库里真没有这一行」，导入预览才不会把「将覆盖已有源」误报成「新增」。
     * 本方法读的是 Room 真值。
     */
    suspend fun getFormatByUrl(url: String): SourceFormat?

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

    /**
     * 删除书源；**任何一行都可删**（应用不随包携带书源，表里每行都是用户导入的），
     * 删不掉的唯一成因是这行本就不存在，由返回的 [Result] 表达。
     *
     * 成功后失效该 URL 的 parser 缓存，删掉的若是当前默认源则回落到下一条启用源。
     */
    suspend fun removeSource(url: String): Result<Unit>

    /**
     * 启用/禁用某书源。
     *
     * 禁用只意味着「不参与聚合搜索、不出现在书城候选」，**不切断已有归属**：
     * 书架里绑着该源的书仍经 [getParserFor] 解析。两条默认源分支（判据都在写之前求值）：
     * - 禁用分支：**SP 是否命中被禁用的这个 URL**，命中才收敛；
     * - 启用分支：**写之前是否没有可用默认源**（源全被禁用），是才收敛。
     *
     * 收敛本身只是「把这次现算到的选择固化进 SP + 补一次订阅面推送」，不承担「补齐默认源」的职责：
     * 默认源由实现每次从 Room 现算，Room 的失效事件已让订阅面自己给出正确结果，
     * 不存在「启用完仍是无源可用」这种要靠写路径兜的情形。
     *
     * 成功后失效该 URL 的 parser 缓存。
     */
    suspend fun setEnabled(url: String, enabled: Boolean)

    /**
     * 把 [url] 指向的书源设为默认源（写 SharedPreferences + 推订阅面）；库里无该行则忽略。
     *
     * **注意一个不在名字里的副作用：目标源当前是禁用态时，本方法会顺带把它 `enabled = true`。**
     * 理由是「默认源永不为禁用态」这条不变式：只记下 URL 而不启用，回落规则会在下一次读清单时
     * 把它换回第一条启用源，用户看到的现象就是「点了没换」。因此 UI 侧**不必**「先启用、再设默认」
     * 两步走，直接调本方法即可。
     *
     * 成功后失效该 URL 的 parser 缓存。
     */
    suspend fun setDefaultSource(url: String)

    /**
     * 订阅书源清单（含禁用），供书源管理页渲染。
     *
     * 与其余读面不同，这里给的是 [BookSourceItem] 而不是裸 [BookSourceRule]：管理页要先看
     * `format` 才知道这一行该渲染成什么（脚本行没有可用规则、要不要给导出入口也看它），
     * 而 [getAllSources] 那类挂起读服务于解析与导入预览，
     * 多带一份元数据只会诱导调用方去读它（不对称的理由见接口顶部说明）。
     *
     * **脚本书源只在这一面现身**（`item.format`）：它的 `item.rule` 是按实体列合成的展示用空壳
     * （只带名称/地址/启用态，`rule_json` 不参与解码），页面必须先看 `format` 再决定渲染什么
     * （出身标记、导出入口的取舍），不要把那条空规则当成可用规则。
     *
     * 冷流：收集时才查 DAO，不做共享。
     */
    fun observeSources(): Flow<List<BookSourceItem>>

    /**
     * 订阅默认书源，供书城渲染；永不为禁用态。
     *
     * **没有任何 enabled 行时（用户全禁用）发 null**。
     * 回落在**两种格式的启用行**里算（2e 起不再筛 format）：SP 命中项（且启用）→ 否则
     * 启用清单第一条——与实现类的 `defaultFromRows` 是同一个函数，两处口径不可能分裂。
     * 调用方（书城）收到 null 应展示「请先启用或导入书源」的引导态，而不是空白页。
     */
    fun observeDefaultSource(): Flow<SourceDefinition?>

    /**
     * 把一段书源 JSON 解成 [BookSourceRule]（纯函数，**不落库、不改变任何状态**）。
     *
     * 解析失败或关键字段缺失（`name`/`url` 为空）返回 null。调用方拿到结果后自行决定是否
     * 交给 [addSource] 落库——导入 UI 通常要先做预览与校验。
     */
    fun importFromJson(jsonStr: String): BookSourceRule?

    /** 把单个书源规则导出成美化 JSON（配 [importFromJson] 用于社区书源交换） */
    fun exportToJson(rule: BookSourceRule): String
}
