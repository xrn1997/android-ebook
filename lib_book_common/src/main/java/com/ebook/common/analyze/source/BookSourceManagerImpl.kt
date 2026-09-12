package com.ebook.common.analyze.source

import android.content.Context
import androidx.core.content.edit
import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.ScriptSourceRule
import com.ebook.api.entity.SourceDefinition
import com.ebook.api.entity.SourceFormat
import com.ebook.db.dao.BookSourceDao
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.BookSourceEntity
import com.ebook.source.analyze.AggregateSearchEvent
import com.ebook.source.analyze.BookParser
import com.ebook.source.analyze.JsoupBookParser
import com.ebook.source.analyze.ScriptBookParser
import com.ebook.source.sandbox.JsSandboxHost
import com.ebook.source.script.ScriptExplore
import com.xrn1997.common.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * [BookSourceManager] 实现：Room 持久化的书源清单 + 按 URL 取 parser（见 ADR-0016）。
 *
 * 使用纯净 OkHttpClient（`@Named("source")`），不携带登录凭证，避免 token 泄漏给第三方书源。
 *
 * ## Room 是唯一事实源
 *
 * 应用**不随包携带任何书源**（书源是指向第三方内容站点的访问配置），所以既没有首启灌库，也没有
 * 任何可同步给出的冷启动快照：表里的每一行都由用户导入而来，规则内容、启用状态与权重的真值
 * 只在 Room 里。用户对书源做的任何增删改都只写这一处、也只从这一处读，
 * 不存在「下次启动被随包配置覆盖回去」的可能。
 *
 * 构造因此不碰任何 IO，也不持有任何作用域：拿一个 SharedPreferences 句柄、建一份空的默认 URL
 * 推送流，仅此而已。清单与默认源一律经挂起读或 Flow 拿——本类的可变状态都只在协程里改。
 *
 * ## 默认源：每次现算，没有内存快照
 *
 * 默认源由 [defaultFromRows] 从 Room 行现算（SP 命中的启用行优先，否则第一条启用行，两种格式平等）：
 * 订阅面 [observeDefaultSource] 与写路径的收敛 [syncDefaultAfterWrite] 都调它，口径不可能分裂。
 *
 * **不再有「定义 + parser」的内存快照**（曾叫 `defaultSnapshot`）：那份快照是为「同步面首帧就要有值」
 * 而生的，同步面既已随内置书源一并删除，它就只剩副作用——凡是可能改掉默认源那一行的写路径都得
 * 记得同步它，漏一处就是「改了规则解析一点没变、零报错」。现在默认源与其它源同构：
 * parser 一样进 LRU，写路径的 [evictParser] 一样覆盖它。
 *
 * [defaultUrlFlow] 是**本次会话里的重推信号**，不是事实源：`setDefaultSource` 对已启用目标只写 SP、
 * 不写库，没有它 Room 的失效事件不会触发，订阅面就看不到这次切换。SP（`KEY_CURRENT_SOURCE`）
 * 跨启动记住「用户上次主动选了哪个源」，与 [defaultUrlFlow] 由 [persistDefaultUrl] 成对更新。
 *
 * ## 两条求值后端只在 [toDefinition] 一处按 `format` 分岔
 *
 * `book_source` 表现在同时装两种出身的行：原生规则书源与脚本书源（社区通用 JSON，规则内嵌可执行脚本）。
 * 路由键是 `format` 列，**读它的有四处、各办各的事**：
 * - [toDefinition]：求值链路唯一的分岔点，按分支把行交给 [parserFactory]（原生解规则、脚本原样递出 JSON）；
 * - [toRule]：把非原生行整条挡下，于是 [getAllSources] / [getEnabledSources] / [getSourceByUrl]
 *   三面同时生效，不需要每个入口各写一次判断（默认源回落不在此列：它走 [defaultFromRows]，
 *   脚本行同样可以是默认源）；
 * - [toItem]：决定这一行**要不要**解 `rule_json`（脚本行不解，条目 rule 由实体列合成，见其 KDoc）；
 * - [getFormatByUrl]：只问一行的出身，供导入预览判「会不会覆盖、覆盖成什么格式」。
 * 这几处读法必须一起看：让求值链路改走 [getSourceByUrl]，脚本书源就会凭空变成「库里没这行」；
 * 让 [toItem] 无条件解 `rule_json`，形状冲突的脚本行就会整行消失（曾是的）。
 * **写侧同样只有两处**（[addSource] 写 NATIVE、[addScriptSource] 写 SCRIPT），两处都显式取 `.raw`
 * 并在写成功后失效该 URL 的缓存——`format` 是路由键，谁悄悄改它而不 evict，LRU 就成了第二个事实源。
 * 换求值后端只动 [parserFactory] 的分支，本类的路由结构不参与：这正是把分岔收在一处的目的。
 */
@Singleton
class BookSourceManagerImpl internal constructor(
    @ApplicationContext private val context: Context,
    private val dao: BookSourceDao,
    @Named("source") private val okHttpClient: OkHttpClient,
    /** null = 未装配沙箱（测试、独立运行）：脚本书源的 JS 段按「待执行」如实报，行为与 2d 一致 */
    private val jsHost: JsSandboxHost? = null,
    /**
     * 「求值输入 → parser」的工厂，生产实现按 [SourceDefinition] 的密封分支分发：
     * 原生规则走 [JsoupBookParser]，脚本书源走 [ScriptBookParser]（2d 落地的真解释器装配层）。
     *
     * 做成构造参数只为**可测性**：聚合搜索（[searchAcross]）会对
     * 每条启用源取 parser，测试若照默认工厂走就会真的向第三方站点发请求。测试注入一个假工厂
     * 即可锁「并发上限、单源失败不外溢、hasMore 判据」这些**编排**行为；
     * 「按规则怎么把 HTML 解成实体」属 [JsoupBookParser] 自己的面，不在本类测试范围内。
     *
     * 全类的 parser **只经本工厂诞生**（当前唯一调用点是 [getParserFor] 的 LRU 未命中分支），
     * 这样注入点只有一个，不会出现「测试换了工厂、某条路径仍偷偷 new 真 parser」的假绿。
     * 参数从 [BookSourceRule] 换成 [SourceDefinition] 正是为了守住这条：格式路由一旦挪到工厂之外
     * （比如在 [getParserFor] 里直接给脚本行 new 一个后端），注入假工厂的用例就再也锁不住那条路径
     * 真实会构造什么了。
     *
     * 注入方注意：只服务原生规则的假工厂应当在 Script 分支**直接抛断**，不要图省事返回 null 或复用
     * 原生假件——静默兜住等于把「哪天长出一条把脚本行当原生规则解的路」这种接线错误吞掉。
     */
    private val parserFactory: (SourceDefinition) -> BookParser = { definition ->
        when (definition) {
            is SourceDefinition.Native -> JsoupBookParser(definition.rule, okHttpClient)
            // 真解析器只收原始 JSON + 同一个纯净客户端（不带 token）+ 进程级沙箱装配面（可空，
            // null 时 JS 段照 2d 报「待执行」）；规则装载是**惰性**的，
            // 所以构造永不失败——getParserFor 对脚本行「不 null、不在取 parser 时抛」的契约由它承担。
            // 实例按 URL 各建一份进 LRU（不像旧的待实现桩是单例），因为它的可变状态就是那份规则集。
            is SourceDefinition.Script -> ScriptBookParser(definition.rawJson, okHttpClient, jsHost)
        }
    },
) : BookSourceManager {

    /**
     * Hilt 生产构造入口：可选参数（沙箱装配面与 [parserFactory]）由主构造的默认值给出、
     * 依赖图无从提供，故在此逐参转发——注入点只有这一个，签名由它固定。
     *
     * 第 4 个参数名刻意与主构造的 `jsHost` 不同：两者同名同型时，下面这次转发调用可以匹配到
     * 次构造自身（它恰好也是「Context + DAO + Client + 沙箱面」四参），Kotlin 会报
     * `There's a cycle in the delegation calls chain`；转发处带名字地传给主构造即可避开。
     */
    @Inject
    constructor(
        @ApplicationContext context: Context,
        dao: BookSourceDao,
        @Named("source") okHttpClient: OkHttpClient,
        sandboxHost: JsSandboxHost?,
    ) : this(
        context = context,
        dao = dao,
        okHttpClient = okHttpClient,
        jsHost = sandboxHost,
    )

    companion object {
        private const val TAG = "BookSourceManager"
        private const val PREFS_NAME = "book_source_prefs"
        private const val KEY_CURRENT_SOURCE = "current_source_url"

        /**
         * 「书源 → parser」LRU 容量：同时活跃的书源数量级（用户手上有 2~3 个源在读）。
         *
         * 聚合搜索一轮会触达全部启用源，超出容量的条目被淘汰——代价只是下一轮重建一个 parser 对象
         * （`BookSourceNetwork` 建 Retrofit service），不影响正确性。缓存的是「解析链路反复取用的热源」，
         * 不是「所有启用源」，所以容量不必随源数扩张；真要调，判据是同时打开的**书籍**数而不是源数。
         */
        private const val PARSER_CACHE_SIZE = 3

        /**
         * 聚合搜索同时在跑的书源数：再多会对更多第三方站点同时发同一关键词，触发风控的概率
         * 随站点数上升（封 IP / 出验证码），受罚的是用户而不是本机，故宁慢不爆（判据见接口 KDoc）。
         */
        private const val AGGREGATE_SEARCH_CONCURRENCY = 5

        /**
         * 分组兜底值：社区 JSON 未声明 `bookSourceGroup`、且库里没有旧行可沿用时用它。
         * 与 `BookSourceEntity.group` / [BookSourceRule.group] 的默认值同字，不另立新词。
         */
        private const val DEFAULT_SOURCE_GROUP = "小说"
    }

    // 落库/读库用的紧凑 JSON 已移到文件外的 `storageJson`（`SourceStorageJson.kt`，原生与脚本两种
    // rule_json 都解码自这一个实例）：
    // 导入预览要用同一个解码器判「这条脚本源解不解得开」，两处各持一份就会长出两个接受集，
    // 于是出现「预览说能导、落库说解不出」这种两边各按自己那份配置说话的故障。
    /** 对外导出用美化 JSON（社区书源交换要给人看、手改） */
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 默认源 URL 的推送源，只为让 [observeDefaultSource] 能把「换默认源」与「清单变化」combine 起来 */
    private val defaultUrlFlow = MutableStateFlow<String?>(null)

    /**
     * 访问序 LRU：容量超过 [PARSER_CACHE_SIZE] 时淘汰最久未用条目。
     *
     * 所有读写都在 [parserCacheMutex] 下进行——本 map 不是并发安全的，`accessOrder = true` 意味着
     * **连读都会改结构**，故绝不允许在锁外访问。
     */
    private val parserCache = object : LinkedHashMap<String, BookParser>(4, 1f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, BookParser>?): Boolean =
            size > PARSER_CACHE_SIZE
    }

    /** 串行化 [parserCache] 读写：并发 getParserFor 同一 URL 时不致各建一个实例 */
    private val parserCacheMutex = Mutex()

    // region 默认源

    /**
     * 默认源挑选的**唯一实现**：条目面（两种出身平等）的启用行里，SP 命中项优先、否则第一条。
     *
     * 订阅面与写路径的收敛共用本函数，口径不可能分裂（曾有一版是「回填按原生行筛、
     * 订阅面在条目上另筛一次 format」，两处一致只靠两侧纪律维持）。
     *
     * 候选先经 [toItem] 解码（原生行解不出即出局，与脏行同一口径；脚本行恒非 null），
     * 挑选在实体层做：条目 [BookSourceItem] 不携带脚本的原文，定义要经 [toDefinition] 从行造。
     * 没有任何候选返回 null——「库里没有可用源」对两种格式一视同仁。
     */
    private fun defaultFromRows(rows: List<BookSourceEntity>): SourceDefinition? {
        val candidates = rows.filter { toItem(it) != null }
        val savedUrl = prefs.getString(KEY_CURRENT_SOURCE, null)
        val target = candidates.firstOrNull { it.url == savedUrl && it.enabled }
            ?: candidates.firstOrNull { it.enabled }
            ?: return null
        return toDefinition(target)
    }

    /** 现算当前默认源；库里没有任何启用行时为 null */
    private suspend fun resolveDefault(): SourceDefinition? = defaultFromRows(dao.getAll())

    /**
     * 定下当前默认源：写 SP + 推 [defaultUrlFlow]。
     *
     * 两件事必须成对完成：SP 是跨启动的记忆，[defaultUrlFlow] 是本次会话里订阅面的重推信号
     * （`observeDefaultSource` 的 combine 依赖它——改 SP 不产生 Room 失效事件）。
     *
     * [url] 为 null（库里没有任何启用行）时**不写 SP**：SP 记的是「用户上次主动选了哪个源」，
     * 抹掉它就只剩「按权重取第一条」这个次优答案了。
     *
     * **判据与改写都以 SP 记着的 URL 为准，不比「当前有效默认源」**：写路径（[addSource] /
     * [addScriptSource] / [removeSource]）判「要不要收敛」时读的是 SP 里的值，因此存在这样一处口径差异
     * ——SP 记着 A、而 A 已禁用或已删（此刻现算出的有效默认源是 B）时，本实现会把 SP 从 A 改写成 B；
     * 若以「有效默认源的 URL」为判据，这种形态下 SP 不会被改写。方向无害（SP 只是优先级提示，
     * [defaultFromRows] 对 SP 命中不到的 URL 本就回落到第一条启用行），但读那几个写路径的条件时
     * 要按「以 SP 为准」理解，别以为它们在比较当前默认源。
     */
    private fun persistDefaultUrl(url: String?) {
        if (url != null) prefs.edit { putString(KEY_CURRENT_SOURCE, url) }
        defaultUrlFlow.value = url
    }

    // endregion
    // region 实体 <-> 规则 / 清单条目

    /**
     * 行 → 清单条目（规则 + `format`）。
     *
     * ## 原生行：解 `rule_json` 并**以实体列为准覆盖**
     *
     * [BookSourceEntity.enabled] / [BookSourceEntity.weight] / [BookSourceEntity.group] 同时存在
     * 实体列与 `rule_json` 里，而用户改启用状态/权重走的是只动列的路径（[BookSourceDao.setEnabled]
     * 用 UPDATE 正是为了不把 `rule_json` 冲掉）。若直接返回 `rule_json` 解出来的对象，就会出现
     * 「库里禁用、内存 rule 说启用」的分裂，故返回前统一把列覆盖回去。
     *
     * 对原生行而言本方法是实体 → 对外形态的**唯一解码路径**：[toRule] 只是取它的 `rule`。两条路径各解一次
     * 的话，脏行跳过、列覆盖这些规则极易只补在其中一处，出现「挂起读看得见这行、订阅面看不见」
     * 这种按入口分裂的现象。
     *
     * 原生行单行解码失败**跳过该行**并记日志：一行脏数据不该把整个书源列表打成空。
     *
     * ## 脚本行：不进上面那条解码路径，`rule` 由实体列合成
     *
     * **非原生格式的行一律不解 `rule_json`**，条目里的 [BookSourceItem.rule] 直接由实体列摆出来
     * （`BookSourceRule` 其余字段都有默认值，于是得到一条只有展示信息、没有选择器的空壳规则）。
     *
     * 为什么不解：社区格式的顶层键里有若干与 [BookSourceRule] **同名但形状不同**的字段
     * （如 `weight` 常是 `null` 而原生侧是非空 Int、`headers` 常是 `[{key,value}]` 数组而原生侧是
     * `Map<String,String>`），拿原生序列化器去解**会抛异常**。而抛异常在上面的原生分支里的处置是
     * 「跳过该行」——那一行就此从 [observeSources] 消失，只留一行 ERROR 日志。这与「解出空壳」完全不同：
     * 管理页看得到一条内容全空的源，与根本看不到这条源，用户体验上是两种故障，而后者直接把
     * 脚本书源阶段一的验收状态（可导入、**可管理**、可禁用）打掉——用户删不掉一个自己导进来、
     * 却在页面上不存在的源。
     *
     * 为什么这一面仍然要放出脚本行：[observeSources] 是**管理页的唯一清单出口**，
     * 用户得知道他导入过什么、得能禁用与删除它；条目里的 [BookSourceItem.format] 就是页面用来
     * 区分两种出身、决定这条 `rule` 该怎么渲染（乃至要不要给导出入口）的那一位。
     * **求值从不读这里的 rule**：它走 [toDefinition] 的格式路由（脚本行原样递出原始 JSON）。
     */
    private fun toItem(entity: BookSourceEntity): BookSourceItem? {
        val format = SourceFormat.fromRaw(entity.format)
        val rule = if (format == SourceFormat.NATIVE) {
            val parsed = try {
                storageJson.decodeFromString(BookSourceRule.serializer(), entity.ruleJson)
            } catch (e: Exception) {
                Logger.e(TAG, "书源规则解码失败，跳过该行: ${entity.url}", e)
                return null
            }
            parsed.copy(
                name = entity.name,
                url = entity.url,
                enabled = entity.enabled,
                weight = entity.weight,
                group = entity.group,
            )
        } else {
            // 只取展示要用的列，其余留默认值：这条 rule 的唯一读者是管理页（名称 + 地址 + 启用开关）
            BookSourceRule(
                name = entity.name,
                url = entity.url,
                enabled = entity.enabled,
                weight = entity.weight,
                group = entity.group,
            )
        }
        return BookSourceItem(
            rule = rule,
            // 管理页要靠这一位区分「原生规则源」与「脚本源」（出身标记、导出入口的取舍）。
            // 脚本行的 rule 是上面那条分支合成的展示用空壳（见 [toRule] 为何挡下它们），
            // 所以 format 必须一起带出，否则页面就会把一条空规则当成一个正常的原生源渲染。
            format = format,
        )
    }

    /**
     * 行 → 规则；解析链路与挂起读面只要规则，元数据经 [toItem] 只随 [observeSources] 出门。
     *
     * **非原生格式的行在这里被挡下**，于是规则类型化读面（[getAllSources] / [getEnabledSources] /
     * [getSourceByUrl]）自动对脚本书源不可见。默认源回落不在其中——它走 [defaultFromRows]
     * （[toItem] 筛、[toDefinition] 造），脚本行同样有资格当默认源。
     *
     * 为什么必须挡而不是「让它解出个空规则」：脚本书源的 `rule_json` 是社区格式的原始 JSON，
     * 拿 [BookSourceRule] 去解有两种结局，没有一种能用——该类型每个字段都带默认值、且解码开了
     * `ignoreUnknownKeys`，于是键名对不上的结果是**一条「没有任何选择器的空规则」**；而碰上同名不同形的键
     * （社区侧的 `weight` 常是 `null`、`headers` 常是数组）则是**直接抛解码异常**。
     * 空规则比脏数据危险：它会真去发请求、解出零条目，页面表现为「这本书没有章节」「这个源搜不到东西」
     * 而全程零报错；抛异常则会顺着 [toItem] 的原生分支把整行丢出清单。
     * 脚本书源求值走 [toDefinition] 的分格式路由，不经过这里。
     *
     * 跳过只记 INFO 不记 ERROR：那**不是脏数据**，是脚本书源对规则类型化读面天然不可见
     * （它已参与聚合搜索与书城，走的是 [toDefinition] 路由到自己的求值后端，不读这套规则类型）。
     */
    private fun toRule(entity: BookSourceEntity): BookSourceRule? {
        if (SourceFormat.fromRaw(entity.format) != SourceFormat.NATIVE) {
            Logger.i(TAG, "规则类型化读面跳过非原生格式的书源: ${entity.url} (format=${entity.format})")
            return null
        }
        return toItem(entity)?.rule
    }

    /**
     * 行 → 求值输入（[SourceDefinition]）：`book_source.format` 列是这里唯一的路由键。
     *
     * - [SourceFormat.SCRIPT]：**不解** `rule_json`，整段原文随 [SourceDefinition.Script] 递出去。
     *   脚本书源「入库不翻译」是决策的前提，在这里解一次就已经把它作废了。默认工厂的
     *   Script 分支给出 [ScriptBookParser]（规则装载惰性、构造期不解析），因此本分支**不是异常态**：
     *   命中它意味着一条脚本源正在正常工作，「只改工厂那一处分支、路由结构不动」的约定到此兑现。
     *   这里记 debug 而不是 WARN：一次命中仍值得看得见（排查时想知道这条路由被走过、从哪走到），
     *   但正常链路不该用 WARN 吵日志——WARN 留给「解不动」那一类真正的失败（各解析器自己的日志）。
     * - [SourceFormat.NATIVE]：既有解码路径 [toRule]，解不出即 null。
     *
     * **null 的语义没有被格式拓宽**：空白或未知的 `format` 都被 [SourceFormat.fromRaw] 归到
     * [SourceFormat.NATIVE]（走既有校验，报错对用户更有指向性），因此返回 null 仍然只有
     * 「原生行的 `rule_json` 解不出来」这一种成因——[getParserFor] 的「null 就等于没有这行/这行坏了」
     * 不会被格式判不出来给污染。
     */
    private fun toDefinition(entity: BookSourceEntity): SourceDefinition? =
        when (SourceFormat.fromRaw(entity.format)) {
            SourceFormat.SCRIPT -> {
                Logger.d(TAG, "脚本书源求值路由: ${entity.url}")
                // name/url 从实体列填：这是 SourceDefinition.Script 展示信息的生产填充点（唯一一处），
                // 脚本的 rule_json 是社区格式原文，本类型从不解析它取展示信息
                SourceDefinition.Script(entity.ruleJson, name = entity.name, url = entity.url)
            }

            SourceFormat.NATIVE -> toRule(entity)?.let { SourceDefinition.Native(it) }
        }

    /**
     * 规则 → 行；`rule_json` 存整段规则（列里的冗余字段只为让列表查询不必先解 JSON）。
     *
     * `addedAt` 由调用方带进来而不是在这里取当前时间：覆盖导入要沿用旧行的入库时刻，
     * 否则列表里这条源的位置（排序次级键）与「导入时间」展示都会被刷成今天。
     *
     * `format` 显式写 [SourceFormat.NATIVE] 的列值而不吃实体默认值：这一列现在是求值链路的**路由键**，
     * 靠默认值意味着默认值一旦漂移（或有人给实体加了别的默认），原生写入就静默变成脚本行、
     * 解析从此命中桩而不报错。列值取自枚举的 `raw`（小写），不用 `name`（大写枚举名，与迁移里的
     * `DEFAULT 'native'` 不同字，会被 fromRaw 静默判成 native）。
     */
    private fun BookSourceRule.toEntity(addedAt: Long) = BookSourceEntity(
        url = url,
        name = name,
        ruleJson = storageJson.encodeToString(BookSourceRule.serializer(), this),
        enabled = enabled,
        weight = weight,
        group = group,
        format = SourceFormat.NATIVE.raw,
        addedAt = addedAt,
    )

    // endregion

    /** 全部书源（含禁用的）。空表就是「用户还没导入过书源」，不是异常 */
    override suspend fun getAllSources(): List<BookSourceRule> =
        dao.getAll().mapNotNull { toRule(it) }

    /**
     * 启用中的书源。**当前无生产调用方**，保留为规则类型化读面的一员（与 [getAllSources]、
     * [getSourceByUrl] 同族）：聚合搜索候选走 [BookSourceDao.getEnabled] + [toItem]，
     * 默认源回落走 [defaultFromRows]（读全量行后在条目面筛 `enabled`），两条链路都不经过本方法。
     */
    override suspend fun getEnabledSources(): List<BookSourceRule> =
        dao.getEnabled().mapNotNull { toRule(it) }

    /** 按 URL 取单个源；null 只可能是「这行真不存在」或「这行不是原生格式/解码失败」 */
    override suspend fun getSourceByUrl(url: String): BookSourceRule? =
        dao.getByUrl(url)?.let { toRule(it) }

    /**
     * 按 URL 取 parser，未命中则查库构建并放入 LRU。
     *
     * 五个要点：
     * - **查库走 [BookSourceDao.getByUrl] 而不是 [getSourceByUrl]**：后者是规则类型化读面，
     *   已按 `format` 把脚本书源挡下（见 [toRule]），照它读就会让「脚本行」在求值链路上凭空变成
     *   「没有这行」——而 null 在这里只该表示「源不存在/坏行」，混进「这条源是脚本出身」就等于
     *   把可正常阅读的源说成已失效。
     * - **按 [toDefinition] 分格式路由，两条格式共用同一套缓存结构**：脚本行与原生行一样进 [parserCache]
     *   （命中哪个后端只由 `format` 列决定，不由缓存决定；实例按 URL 各建一份，因为脚本解析器的
     *   可变状态就是它那份规则集），
     *   格式翻转后的失效仍统一走 [evictParser]——缓存只按 URL 存，若写侧漏了失效，缓存就成了
     *   「第二个格式事实源」，现象是同 URL 换了格式却还拿到旧后端。
     * - **本地书短路**：`tag` 为空白或 `loc_book` 时直接返回 null，不查库——本地书根本不该走到网络 parser 上；
     * - **整段上锁**：「查缓存 → 查库 → 放缓存」必须整体在 [parserCacheMutex] 内，
     *   否则并发下各建一个实例、后写的把先写的覆盖掉（构建本身不便宜，`BookSourceNetwork` 会建 Retrofit service）；
     * - **默认源没有特殊路径**：它与其它源共用一个 LRU（配置成默认源不额外复制 parser，也就没有
     *   「改规则后缓存里那份仍是旧的」这种只在默认源上出现的坑）；改掉默认源那一行的写入口
     *   照常调 [evictParser] 失效缓存即可。
     *
     * 返回 null 的三种成因：本地书 tag / 空白、库里没这行、行在但原生格式的 `rule_json` 解不出来
     * （[toRule] 跳过脏行）。逐条处置由调用方定，见接口 KDoc。
     * **脚本书源不在这三种里**：它拿到的是真解析器 [ScriptBookParser]，且**取 parser 永不为脚本行抛**
     * （规则装载是惰性的）。坏 JSON 的脚本行要等到**首次求值**才抛类型化装载失败，
     * 消息含「脚本书源 JSON 无法解析」——null 仍然只说「没有这行/这行坏了」，
     * 「源在库里、这条规则解不动」必须是另一种可区分的结局（§12 的失败如实报）。
     */
    override suspend fun getParserFor(sourceUrl: String): BookParser? {
        if (sourceUrl.isBlank() || sourceUrl == BookShelfEntity.LOCAL_TAG) return null
        return parserCacheMutex.withLock {
            parserCache[sourceUrl]
                ?: dao.getByUrl(sourceUrl)?.let { entity ->
                    toDefinition(entity)?.let { definition ->
                        parserFactory(definition).also { parserCache[sourceUrl] = it }
                    }
                }
        }
    }

    /**
     * 聚合搜索（一次搜全站）：把 [searchAcross] 声明里的编排落地——启用源清单 → 并发上限内逐源解析
     * 该页 → 按源发事件。
     *
     * 三处值得一提：
     * - **不加 [flowOn]**：解析器内部（`JsoupBookParser.searchBook`）自己就 `withContext(Dispatchers.IO)`，
     *   Room 的挂起查询也由 Room 自己的执行器承接，本流只剩「查清单 + 发事件」这点轻活；
     *   留在收集者的调度器上，测试才能用虚拟时间确定性地驱动并发编排。
     * - [getParserFor] 返回 null 时**发 Failed 而不是让整条流少一格**：清单来自 enabled 行，
     *   理论上取不到 parser 只可能是那一行的 `rule_json` 解不出来（脏行被 [toRule] 跳过），
     *   这是「这条源坏了」而不是「聚合逻辑坏了」，处置范围必须限定在它自己身上。
     * - **候选集走 [BookSourceDao.getEnabled] + [toItem]，不走 [getEnabledSources]**：后者是
     *   **规则类型化读面**，按 `format` 把脚本行挡下（它的返回类型是 [BookSourceRule]，
     *   脚本行的 `rule_json` 硬解只会得到一条键名全对不上的空规则）。2d 起脚本源参与聚合搜索，
     *   所以这里读条目面：[toItem] 对两种出身都给真 `url`/`name`（脚本行的 rule 只是实体列合成的
     *   展示壳，聚合只取它的 url 与 name 发事件，从不拿规则字段去发请求）。
     * - 单源协程里的异常一律就地收敛（见 [searchOneSource]），[AggregateSearchEvent.AllFinished]
     *   因此永远会发——包括一条源都没有的情形。
     */
    override fun searchAcross(
        keyword: String,
        page: Int,
        skipSourceUrls: Set<String>,
    ): Flow<AggregateSearchEvent> = flow {
        val sources = dao.getEnabled().mapNotNull { toItem(it) }.filterNot { it.rule.url in skipSourceUrls }
        emitAll(
            sources.asFlow().flatMapMerge(AGGREGATE_SEARCH_CONCURRENCY) { item ->
                searchOneSource(item, keyword, page)
            }
        )
        emit(AggregateSearchEvent.AllFinished)
    }

    /**
     * 单条书源的一次解析，产 [AggregateSearchEvent.SourceStarted] → 结果或失败 → [AggregateSearchEvent.SourceFinished]。
     *
     * [kotlinx.coroutines.CancellationException] 必须先于普通 catch 原样上抛：吞掉它会让被取消的
     * 子协程继续跑到「正常结束」，表现为换关键词重搜时旧轮次还在往新结果里灌。
     *
     * 入参是 [BookSourceItem] 而不是 [BookSourceRule]：候选集现在有两种出身（见 [searchAcross]），
     * 本方法只取 `rule.url`/`rule.name` 两件——都是两种出身一致为真值的列值。
     */
    private fun searchOneSource(
        item: BookSourceItem,
        keyword: String,
        page: Int,
    ): Flow<AggregateSearchEvent> = flow {
        emit(AggregateSearchEvent.SourceStarted(item.rule.url, item.rule.name))
        try {
            val parser = getParserFor(item.rule.url)
                // 清单里的源取不到 parser：按「这条源坏了」报出去，范围不外溢（理由见 searchAcross）
                ?: throw IllegalStateException("书源清单里的源取不到解析器：${item.rule.name}(${item.rule.url})")
            val books = parser.searchBook(keyword, page)
            emit(AggregateSearchEvent.SourceResult(item.rule.url, books))
            emit(AggregateSearchEvent.SourceFinished(item.rule.url, hasMore = books.isNotEmpty()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 脚本源的类型化失败（含 JS 段待沙箱、用了不支持的能力、规则 JSON 读不懂）也在此收敛为
            // 该源的 Failed——「这条源解不动」不说成「搜不到结果」，也不带走其它源
            Logger.w(TAG, "聚合搜索单源失败: ${item.rule.url}", e)
            emit(AggregateSearchEvent.SourceFailed(item.rule.url, e))
            // 失败也要 Finished：UI 靠它收进度（见 AggregateSearchEvent 类注释）
            emit(AggregateSearchEvent.SourceFinished(item.rule.url, hasMore = false))
        }
    }

    /**
     * 新增/覆盖书源（主键 `url` 命中即 REPLACE）。
     *
     * **REPLACE 陷阱**：[BookSourceDao.upsert] 是整行替换，未回填的列会被实体默认值冲掉。
     * 故这里必须 [BookSourceDao.getByUrl] 取旧行，把 `addedAt` 带过来——重导一条已有书源
     * 不该把它在列表里的位置（排序次级键）与「导入时间」展示刷成今天。
     *
     * 成功后失效该 URL 的 parser 缓存（见 [evictParser]），并在必要时收敛默认源
     * （见 [syncDefaultAfterWrite] 的调用条件）。判据「写之前有没有可用默认源」取的是**写前状态**
     * （落库之前读一次 [resolveDefault]）——写后再读，这次导入的启用行已经入库，判据恒为「有」，
     * 提升分支永不触发。这一次写前读失败会随保存一起报 [Result.failure]（还没写库，如实报错），
     * 而写成功之后收敛里的读失败只记日志，不会让调用方以为这次保存失败了。
     */
    override suspend fun addSource(rule: BookSourceRule): Result<Unit> {
        if (rule.url.isBlank()) {
            return Result.failure(IllegalArgumentException("书源 URL 为空，无法保存：${rule.name}"))
        }
        return try {
            val existing = dao.getByUrl(rule.url)
            // 「写之前有没有可用默认源」必须在写之前算：写完之后再问，这次导入已经把新的启用行
            // 落进库里，答案几乎恒为「有」，「无默认源时顶上」就成了永不触发的死分支。
            // 这次读若失败会连同整个保存一起报 Result.failure——此刻还没写库，如实报错是对的；
            // 而写成功之后的收敛由 syncDefaultAfterWrite 自行吞掉读失败（见其 KDoc）。
            val hadNoDefault = resolveDefault() == null
            dao.upsert(rule.toEntity(addedAt = existing?.addedAt ?: System.currentTimeMillis()))
            evictParser(rule.url)
            // 两种状态必须收敛默认源。**写路径不承担「补齐默认源」的职责**：默认源每次由
            // [defaultFromRows] 从 Room 现算，上面这次 upsert 产生的 Room 失效事件已经会让
            // [observeDefaultSource] 重新现算，该轮到这条新导入的启用行就轮得到它。
            // 收敛在这里的真实作用只剩两件：把这次现算到的选择**固化进 SP**（跨启动的记忆），
            // 以及**补一次 [defaultUrlFlow] 推送**（订阅面的 combine 依赖它，见 [persistDefaultUrl]）。
            // 两种触发状态：
            // - 写之前无可用默认源（原先全禁用）→ 固化的就是这次导入的启用行；
            // - 覆盖的就是 SP 记着的当前默认源 → 解析输入换了一份，订阅面必须重推
            //   （[syncDefaultAfterWrite] 经 [persistDefaultUrl] 推 [defaultUrlFlow]）。
            // 收敛走 Room 而不是直接用入参 [rule]：解码/列覆盖只有一条路径（[toItem]），
            // 少一处「两份规则各自演进」的可能。
            if (hadNoDefault || prefs.getString(KEY_CURRENT_SOURCE, null) == rule.url) {
                syncDefaultAfterWrite()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e(TAG, "保存书源失败: ${rule.url}", e)
            Result.failure(e)
        }
    }

    /**
     * 新增/覆盖脚本书源：[rawJson] **原样**落 `rule_json`，`format` 落 `script`（决策见 ADR-0029）。
     *
     * 与 [addSource] 同构，四点必须一起看：
     *
     * - **入参校验先于任何读库动作**（与 [addSource] 「空白 URL 不值得先排队等 Room」同理），
     *   失败一律封成带中文消息的 [IllegalArgumentException] 走 [Result.failure]、不抛进调用方栈：
     *   导入 UI 逐条渲染这些消息（一整包里坏一条不该让整包崩），而裸
     *   `SerializationException` 的英文话术进不了用户界面。解码用 [storageJson]（开了
     *   `ignoreUnknownKeys`）——社区 JSON 带几十个本模型未声明的键，那些规则块**故意**不经这里，
     *   原文才是脚本书源的事实源。
     * - **不做格式内容校验**：是否含可执行代码、是否依赖登录（`loginUrl`）、是否漫画/音频源，
     *   都是导入 UI 的警示职责（本项目是文字阅读器），本方法只负责「存得下来」。
     * - **REPLACE 陷阱同 [addSource]**：整行替换会把未回填的列一起吃实体默认值，故旧行的
     *   `addedAt`（列表位置与「导入时间」展示）与 `weight`（社区最小模型没有对应字段，
     *   冲成 0 就是白丢用户的调整）从旧行带过来；
     *   `group` 只在 JSON 未声明 `bookSourceGroup` 时沿用旧行；`name`/`enabled` 以 JSON 为准
     *   ——「重导即更新规则，但不丢本机元数据」这条口径与 [addSource] 逐字一致。
     * - **默认源的收敛条件与 [addSource] 相同**（[syncDefaultAfterWrite] 的两个触发分支，
     *   判据「写之前有没有可用默认源」同样取写前状态、同样在落库之前求值），
     *   两条分支对脚本行给出的是与原生行**同一个结局**：候选出自 [defaultFromRows]
     *   （条目面、不筛格式），于是「本来无可用默认源 + 导入一条启用的脚本源」会把它立为默认源
     *   （[observeDefaultSource] 随后发出的就是该脚本行的 [SourceDefinition.Script]）；
     *   改写当前默认源那一行时订阅面重推，解析从此走新原文。
     *
     * `format` 写 [SourceFormat.SCRIPT.raw] 而不是 `.name`：列值是小写（与 `MIGRATION_5_6` 的
     * `DEFAULT 'native'`、实体默认值同字），大写枚举名会被 [SourceFormat.fromRaw] 静默判成 native，
     * 脚本书源从此按原生规则解析——不闪退、内容错乱，比崩溃难查（判据见 [SourceFormat] 的类注释）。
     * 同理 [evictParser] 必须调：缓存只按 URL 存、不认识 `format`，同一条 URL 从原生翻成脚本
     * 而不清缓存，就等于让 LRU 当第二个「该由哪个后端求值」的事实源（见 [evictParser]）。
     */
    override suspend fun addScriptSource(rawJson: String): Result<Unit> {
        val parsed = try {
            storageJson.decodeFromString<ScriptSourceRule>(rawJson)
        } catch (e: Exception) {
            Logger.w(TAG, "脚本书源 JSON 解析失败", e)
            return Result.failure(IllegalArgumentException("脚本书源 JSON 无法解析：${e.message}", e))
        }
        if (parsed.bookSourceUrl.isBlank()) {
            return Result.failure(
                IllegalArgumentException("书源 URL 为空，无法保存：${parsed.bookSourceName}")
            )
        }
        if (parsed.bookSourceName.isBlank()) {
            return Result.failure(
                IllegalArgumentException("书源名称为空，无法保存：${parsed.bookSourceUrl}")
            )
        }
        val url = parsed.bookSourceUrl
        return try {
            val existing = dao.getByUrl(url)
            // 判据取写前状态、且与上面那次读同在 try 内：写之前读失败会随保存一起报 Result.failure
            // （还没写库，如实报错），而写成功之后的收敛读失败只记日志（见 [syncDefaultAfterWrite]）。
            // 放到 upsert 之后求值就恒为「有默认源」——这次导入的行已经在库里且是启用态。
            val hadNoDefault = resolveDefault() == null
            dao.upsert(
                BookSourceEntity(
                    url = url,
                    name = parsed.bookSourceName,
                    ruleJson = rawJson,
                    enabled = parsed.enabled,
                    weight = existing?.weight ?: 0,
                    group = parsed.bookSourceGroup.ifBlank { existing?.group ?: DEFAULT_SOURCE_GROUP },
                    format = SourceFormat.SCRIPT.raw,
                    addedAt = existing?.addedAt ?: System.currentTimeMillis(),
                )
            )
            evictParser(url)
            // 触发条件与 addSource 一致：写之前无可用默认源时脚本行同样被提升为默认源；
            // 覆盖 SP 记着的当前默认源时订阅面必须重推（见上方 KDoc）
            if (hadNoDefault || prefs.getString(KEY_CURRENT_SOURCE, null) == url) {
                syncDefaultAfterWrite()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e(TAG, "保存脚本书源失败: $url", e)
            Result.failure(e)
        }
    }

    /**
     * 按 URL 查已入库书源的格式出身；null 就是「库里真没有这一行」。
     *
     * 这一位为什么必须单独开一个查询面：导入预览要判「这条会不会覆盖已有的源、覆盖的是哪种格式」，
     * 而规则类型化读面（[getSourceByUrl]）对脚本行**刻意**给 null——拿它判会把「已有一行脚本源」
     * 误报成「新源」，用户就看到一句指错方向的提示。本方法不碰 `rule_json`，
     * 只读主键与 `format` 列，两种格式的行在这里都是平等的一行。
     */
    override suspend fun getFormatByUrl(url: String): SourceFormat? =
        dao.getByUrl(url)?.let { SourceFormat.fromRaw(it.format) }

    override suspend fun getExploreEntries(sourceUrl: String): List<SourceExploreEntry> {
        if (sourceUrl.isBlank()) return emptyList()
        val entity = dao.getByUrl(sourceUrl) ?: return emptyList()
        return when (SourceFormat.fromRaw(entity.format)) {
            SourceFormat.NATIVE ->
                toItem(entity)?.rule?.ruleFind?.kinds?.map { SourceExploreEntry(it.title, it.url) } ?: emptyList()

            SourceFormat.SCRIPT ->
                ScriptExplore.entries(entity.ruleJson).map { SourceExploreEntry(it.title, it.urlRule) }
        }
    }

    /**
     * 删除书源。应用不随包携带书源，表里每一行都是用户导入的，**任何一行都可删**。
     *
     * [BookSourceDao.deleteByUrl] 返回 0 只有一种成因——这行本就不存在，故失败消息也只有一条。
     *
     * **删完不碰书架**（刻意）：书架行的 `tag` 仍指向已删书源，按 `tag` 取 parser 会拿到 null，
     * 由调用方抛 [com.ebook.source.analyze.BookSourceNotFoundException] 提示「书源已失效」。不在这里顺手清理书架，
     * 是因为「删源」不该连带删掉用户藏书（重新导入同一书源即可恢复阅读），静默清书才是更大的坑。
     */
    override suspend fun removeSource(url: String): Result<Unit> {
        if (url.isBlank()) {
            return Result.failure(IllegalArgumentException("书源 URL 为空，无法删除"))
        }
        return try {
            if (dao.deleteByUrl(url) > 0) {
                evictParser(url)
                // 删掉的正是 SP 记着的当前默认源（用户删的就是他选中的那个）：收敛一次让订阅面
                // 按新清单重推（回落到下一条启用源，或全空时置 null），书城不会继续指着一个已删的源
                if (prefs.getString(KEY_CURRENT_SOURCE, null) == url) syncDefaultAfterWrite()
                Result.success(Unit)
            } else {
                // 唯一的失败成因：这行本就不存在（没有删除保护，也没有第二种 0）
                Result.failure(IllegalStateException("书源不存在：$url"))
            }
        } catch (e: Exception) {
            Logger.e(TAG, "删除书源失败: $url", e)
            Result.failure(e)
        }
    }

    /**
     * 启用/禁用某书源。
     *
     * 走 [BookSourceDao.setEnabled]（UPDATE 单列）而不用 upsert：Switch 拨一下不该把 `weight`、
     * `rule_json` 按实体默认值冲掉。
     *
     * **默认源的两条分支**（与 [setDefaultSource] 共同维护「默认源永不为禁用态」这条不变式）：
     * - 禁用分支：判据是 **SP 是否命中被禁用的这个 URL**（不是「有没有别的源可用」）——命中说明
     *   用户上次主动选的就是它，收敛一次把 SP 从那个已禁用的 URL 上搬走（[defaultFromRows] 对 SP
     *   命中项要求 `enabled`，这一行已不再是现算结果，于是回落到其它启用源或全空时置 null）；
     * - 启用分支：判据是 **写之前是否没有可用默认源**（只出现在「用户把源全禁用了」之后）→ 收敛一次。
     *   （脚本行同样参与，候选出自 [defaultFromRows]。）
     *
     * **两条分支的收敛都只是「把这次现算到的选择固化进 SP + 补一次 [defaultUrlFlow] 推送」**
     * （见 [persistDefaultUrl]），不承担「补齐默认源」的职责：默认源由 [defaultFromRows] 每次从
     * Room 现算，Room 的失效事件已经会让 [observeDefaultSource] 重新现算出这一步的结果，
     * 所以「用户启用完仍是无源可用、还得再点一次启用」这类现象已经不会发生。
     *
     * **判据取写前状态、且只在启用分支求值**：`hadNoDefault` 在 [BookSourceDao.setEnabled] 之前求值
     * ——UPDATE 执行完之后这一行已经在库里且是启用态，那时再问「有没有可用默认源」恒为「有」，
     * 启用分支就是死代码；而禁用分支看的是 SP 命中、用不上这次求值（[resolveDefault] 会读全表并逐行解码），
     * 故禁用时不取该判据。
     * 整段（读判据 + 写 + 失效缓存 + 两个分支的收敛）同在一个 `try` 里：本方法无返回值，
     * 任何一步失败都只记日志、不外抛——判据这次读失败时随后的写也不会发生（因此不存在
     * 「写已成功却被报成失败」，现象是一条日志 + 开关按清单弹回），写成功后的收敛读失败则
     * 由 [syncDefaultAfterWrite] 自行吞掉（见其 KDoc）。
     * 但 [kotlinx.coroutines.CancellationException] 必须原样上抛（与 [searchOneSource] 同一写法）：
     * 判据里的 [resolveDefault] 是 suspend 调用，被它吞掉的取消会让方法在协程已取消后继续往下走。
     *
     * 禁用态的源仍可被 [getParserFor] 取到 parser——那是「不切断归属」的语义。
     */
    override suspend fun setEnabled(url: String, enabled: Boolean) {
        try {
            // 判据只在启用分支求值：禁用分支看 SP 命中，不需要这次整表读 + 逐行解码
            val hadNoDefault = enabled && resolveDefault() == null
            dao.setEnabled(url, enabled)
            evictParser(url)
            if (enabled) {
                if (hadNoDefault) syncDefaultAfterWrite()
            } else if (prefs.getString(KEY_CURRENT_SOURCE, null) == url) {
                syncDefaultAfterWrite()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "更新书源启用状态失败: $url -> $enabled", e)
        }
    }

    /**
     * 设为默认源：库里有该行才生效。
     *
     * 库里没有时只记一行警告、不改任何状态——UI 上的清单来自 Room，正常路径不会传一个不存在的 URL，
     * 传进来说明调用方拿了脏数据，静默忽略比把默认源指向空气安全。规则解不出来的脏行
     * （原生 `rule_json` 坏了）按同一口径忽略。
     *
     * 指向禁用源时**顺带启用它**：这是维护「默认源永不为禁用态」这条不变式的必要动作——
     * 若只是记下 URL，[defaultFromRows] 的回落规则立刻又把它换回第一条启用源，
     * 用户「点了没换」，反而比显式启用更难解释。UI 侧因此不必「先启用再设默认」两步走。
     *
     * 定源动作只剩「写 SP + 推订阅面」（[persistDefaultUrl]），没有内存快照要重建；
     * 末尾顺手失效该 URL 的 parser 缓存：本方法可能在上面把该行从禁用改成启用，
     * 而缓存里的实例是按当时那行规则（原生规则对象带着 `enabled` 位）建的。
     */
    override suspend fun setDefaultSource(url: String) {
        val entity = dao.getByUrl(url)
        if (entity == null) {
            Logger.w(TAG, "设为默认源被忽略：库里没有该书源 $url")
            return
        }
        // toDefinition 在这里只剩「这行解不解得出求值输入」的判定作用：脏行按「库里没有」同一口径
        // 忽略（脚本行恒解得出定义，不会被这条挡下）。定义本身不参与后续动作——默认源是现算的。
        if (toDefinition(entity) == null) {
            Logger.w(TAG, "设为默认源被忽略：该行的规则解不出来 $url")
            return
        }
        if (!entity.enabled) {
            try {
                dao.setEnabled(url, true)
            } catch (e: Exception) {
                Logger.e(TAG, "启用书源失败，设为默认源中止: $url", e)
                return
            }
        }
        persistDefaultUrl(url)
        evictParser(url)
    }

    /**
     * 写操作之后收敛默认源：现算一次并持久化。
     *
     * 落库已经成功，此刻读失败不该让调用方以为「这次保存/删除失败了」，故只记日志、不外抛。
     * （[addSource] / [removeSource] 用 [Result] 表达写结果，[setEnabled] 的语义也只是改状态。）
     *
     * 调用方「要不要调本方法」的判据（写之前有没有可用默认源、写的是不是 SP 记着的那条）
     * 必须在**写之前**求值——本方法内部这次读只用于取收敛后的 URL，不是那个判据的替代品：
     * 写完之后库里已经带着新状态，此时问「有没有可用默认源」只会得到「有」。
     */
    private suspend fun syncDefaultAfterWrite() {
        runCatching { persistDefaultUrl(resolveDefault()?.sourceUrl) }
            .onFailure { Logger.e(TAG, "写操作后收敛默认源失败", it) }
    }

    /**
     * 失效某个 URL 的 parser 缓存。
     *
     * **为什么必须做**：[JsoupBookParser] 实例只与构造时的 `rule` 绑定（规则在选择器里被固化），
     * 同 URL 覆盖规则（[addSource] 的 REPLACE）后缓存里仍留着旧规则的实例——现象是用户明明改了
     * 书源规则，解析行为一点没变，而且没有任何报错。故凡是可能改变解析结果的写操作
     * （[addSource] / [removeSource] / [setEnabled] / [setDefaultSource]）成功后都失效对应条目。
     *
     * **默认源没有单独的缓存位**：它与其它源共用 [parserCache]（见 [getParserFor]），
     * 所以这里清掉就等于默认源也清掉了，不存在「缓存清了、别处还留着一份旧规则」的缝隙——
     * 这份「只有一处缓存」的简单性正是拆掉内存快照换来的。
     *
     * **格式翻转同属「可能改变解析结果的写操作」**：[parserCache] 只按 URL 存，不认识 `format`，
     * 所以同一条 URL 换过后端（原生规则覆盖一条脚本行，或以脚本格式覆盖一条原生行）后，
     * 不失效缓存就等于让 LRU 变成第二个「这个 URL 该由哪个后端求值」的事实源——
     * 现象是用户换成了原生源却仍拿到「该规则含可执行脚本段，需脚本沙箱执行器才能求值」的提示，
     * 或反过来。任何写 `format` 的入口都必须在写成功后调本方法。
     *
     * 顺序上也无需担心「并发请求把旧实例回灌进缓存」：[getParserFor] 查 Room 与写缓存同在
     * [parserCacheMutex] 之内，本方法拿的是同一把锁，因此任何一次「按旧规则建的实例」要么在本方法
     * 之前入缓存（随即被这里清掉）、要么在本方法之后入缓存（那时 Room 已是新规则）。
     */
    private suspend fun evictParser(url: String) {
        parserCacheMutex.withLock { parserCache.remove(url) }
    }

    // region 订阅

    /**
     * 订阅书源清单。
     *
     * **冷流，收集时才查 DAO**：不做共享，多一个收集者就是多一次查询与多一份映射。
     *
     * 映射用 [toItem] 而不是 [toRule]：本流是全类唯一把**行元数据**（`format`）递出去的出口，
     * 书源管理页靠它标出身并取舍导出入口（为何只有这一面带元数据，见接口顶部说明）。
     *
     * 也因此这一面**不**按格式过滤：脚本书源得在管理页里看得见（用户要能知道他导进来过、要能删），
     * 只是条目里那条 `rule` 对脚本行没有意义。
     */
    override fun observeSources(): Flow<List<BookSourceItem>> =
        dao.observeAll().map { rows -> rows.mapNotNull { toItem(it) } }
            .flowOn(Dispatchers.IO)

    /**
     * 默认源订阅面。
     *
     * 挑选全交给 [defaultFromRows]（SP 命中且启用 → 否则第一条启用行，候选为两种格式的全部启用行）：
     * 「订阅面与写路径口径一致」由此从纪律约束变成结构约束（同一个函数）。
     * SP 缺失、指向已删或已禁用的源时按清单现算，故只要清单里有启用中的源，返回的就一定可用。
     *
     * **没有任何 enabled 行时发 null**（用户把源全禁用了），调用方（书城）据此展示引导态。
     * `defaultUrlFlow` 参与 combine 的原因：`setDefaultSource` 对已启用目标**不写库**，
     * 没有它的话「换默认源」不产生 Room 失效事件，订阅面就看不到切换。
     */
    override fun observeDefaultSource(): Flow<SourceDefinition?> =
        combine(defaultUrlFlow, dao.observeAll()) { _, rows -> defaultFromRows(rows) }
            .flowOn(Dispatchers.IO)

    // endregion
    // region 导入导出（纯函数，不落库）

    override fun importFromJson(jsonStr: String): BookSourceRule? = try {
        val rule = storageJson.decodeFromString<BookSourceRule>(jsonStr)
        rule.takeIf { it.name.isNotEmpty() && it.url.isNotEmpty() }
    } catch (e: Exception) {
        Logger.e(TAG, "导入书源失败", e)
        null
    }

    override fun exportToJson(rule: BookSourceRule): String =
        json.encodeToString(BookSourceRule.serializer(), rule)

    // endregion
}
