package com.ebook.source.analyze

import com.ebook.db.entity.BookInfoEntity
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.LibraryEntity
import com.ebook.db.entity.LibraryKindBookListEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.db.entity.WebChapterEntity
import com.ebook.source.sandbox.GuardedNetwork
import com.ebook.source.sandbox.JsNetworkGuard
import com.ebook.source.sandbox.JsSandboxHost
import com.ebook.source.sandbox.SourceCookieJar
import com.ebook.source.sandbox.SourceHostAllowlist
import com.ebook.source.script.EvalContext
import com.ebook.source.script.ExploreUrlFormat
import com.ebook.source.script.OkHttpScriptTransport
import com.ebook.source.script.RuleObjectKind
import com.ebook.source.script.RuleResult
import com.ebook.source.script.RuleValue
import com.ebook.source.script.ScriptContentPager
import com.ebook.source.script.ScriptFieldExtractor
import com.ebook.source.script.ScriptPage
import com.ebook.source.script.ScriptPageFetcher
import com.ebook.source.script.ScriptRuleEvaluator
import com.ebook.source.script.ScriptRuleSet
import com.ebook.source.script.ScriptTocPager
import com.ebook.source.script.ScriptTransport
import com.ebook.source.script.ScriptUrlOption
import com.ebook.source.script.firstText
import com.xrn1997.common.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/**
 * 脚本书源的 `BookParser` 实现：把 2a~2c 的词法/求值/取文层装配成 `BookParser` 的五个求值面。
 *
 * ## 装载是惰性的（getParserFor 契约的一部分）
 *
 * 构造只存原始 JSON，`ScriptRuleSet.load` 在**首次求值**才执行（`rules` 惰性字段）：
 * `getParserFor` 对脚本行**永不返回 null、也不在取 parser 时抛**——「行在但 JSON 坏」必须以
 * 求值期的类型化 `ScriptRuleParseException` 如实报（消息含「脚本书源 JSON 无法解析」），
 * 构造期抛会让缓存锁内炸出未预期异常、null 则会被调用方当成「书源已失效」（§12）。
 *
 * ## 构造可见性
 *
 * 生产构造（parserFactory 用）只收原始 JSON + `@Named("source")` 纯净客户端 + 装配好的
 * [JsSandboxHost]；测试构造收 `ScriptTransport` 假件断言「请求长什么样」。`internal` 主构造引用
 * internal 类型合法，public 次构造签名只出现 public 类型——跨模块（lib_book_common）只用 public 次构造。
 *
 * `jsHost` 为 null 就是「本机没装配沙箱」，五处 JS 抛点照 2d 报「待执行」：脚本行因此在没有
 * `.so`、没有 `:js` 进程的机器上仍然可解（只要它不含 JS），含 JS 的那部分报得清清楚楚。
 */
class ScriptBookParser internal constructor(
    private val rawJson: String,
    transport: ScriptTransport,
    /** null = 本机未装配沙箱（单测、装配失败、以及不接 Hilt 的独立运行路径）：JS 段照 2d 抛待执行 */
    private val jsHost: JsSandboxHost? = null,
    /** 测试构造里让沙箱外呼也打到假 transport（假件不接 DNS，字符串层的白名单判定照样测得到） */
    private val sandboxTransportOverride: ScriptTransport? = null,
) : BookParser, ScriptContentParser {

    constructor(rawJson: String, okHttpClient: OkHttpClient?, jsHost: JsSandboxHost? = null) : this(
        rawJson,
        OkHttpScriptTransport(okHttpClient ?: error("okHttpClient 未注入（生产接线必须传真客户端）")),
        jsHost,
    )

    /** 求值输入：惰性装载（理由见类 KDoc「装载是惰性的」） */
    internal val rules: ScriptRuleSet by lazy { ScriptRuleSet.load(rawJson) }

    /** 取文门面：sourceRoot 是书源 URL（= `rules.sourceUrl`，惰性联动） */
    internal val fetcher: ScriptPageFetcher by lazy {
        ScriptPageFetcher(okHttpClient = null, sourceRoot = rules.sourceUrl, transport = transport)
    }

    /**
     * 日志 TAG。`by lazy` 而不是构造期初始化：`rules.name` 要等首次求值才装载，
     * 构造期摸它会破坏「构造不失败」的 getParserFor 契约（见类 KDoc「装载是惰性的」）
     */
    private val tag by lazy { "ScriptBookParser[${rules.name}]" }

    /** 本源可解析 host 白名单：从规则原文导出（Task 5），首次用到才算（`rules` 是惰性的） */
    private val allowlist by lazy {
        SourceHostAllowlist.of(
            sourceUrl = rules.sourceUrl,
            ruleTexts = rules.objects.values.flatMap { it.values } +
                listOfNotNull(rules.searchUrl, rules.exploreUrl),
        )
    }

    private val guard by lazy { JsNetworkGuard(allowlist) }

    /**
     * 源级会话罐：一条源一份，随本 parser 一起被 `BookSourceManagerImpl` 的 LRU 逐出。
     *
     * 只有这一处实例化点，[sandboxGateway] 与 `newContext` 里的 `bridgeFor` 拿到的是同一个对象——
     * 分成两份的形态是「脚本外呼攒下了会话，`cookie.getCookie` 却读到空串」。
     * 罐**不落盘**：进程内活着就活着，逐出/重启即丢（上游是磁盘 CookieStore，本仓不做，
     * 缺口登记在规格 §11-37）。
     */
    private val cookieJar by lazy { SourceCookieJar() }

    /** 沙箱外呼用的传输：生产走带 `GuardedDns` 的派生客户端，测试给假件（见主构造参数注释） */
    private val sandboxGateway: ScriptTransport by lazy {
        sandboxTransportOverride
            ?: OkHttpScriptTransport(GuardedNetwork.clientFor(jsHost!!.baseClient, guard, cookieJar))
    }

    /**
     * 沙箱 `source` 对象的数据面（`sourceJson` 绑定）：源级字段在整个 parser 生命周期内不变
     * （规则改动会让 `BookSourceManagerImpl` 逐出这一份），故一次装配、每任务复用同一份文本。
     */
    private val sourceJson by lazy { rules.sourceBindingJson() }

    /**
     * 造一次解析任务的上下文（五处 `EvalContext(...)` 唯一的诞生地）。
     *
     * **顺序是刻意的**：先有 ctx、再把它交给 host 造桥、回填 `ctx.js`——桥要现读 ctx 的
     * `baseUrl`/`page`/变量表，ctx 又要持有桥，做成构造参数就是个环，用一次赋值打断
     * （`EvalContext.js` 因此是 `var`，赋值点全仓只有这一处）。
     *
     * [bindings] 只给当前调用点真拿得出的量。`sourceJson` 是唯一不需要调用点提供的对象面绑定：
     * 它的每一栏都来自书源自己，故在这里无条件给上。`chapterJson`/`title` 在本阶段恒缺：正文链路的
     * 入口只有一个 `contentRef` 字符串，章名要查 Room 才有，而本层禁止碰持久层（§5.1 分工）——
     * 显式传 null 让脚本里 `chapter` 是 `undefined`，比给一个空对象让脚本以为「这本书没有章节」好。
     */
    private fun newContext(
        baseUrl: String,
        key: String = "",
        page: Int = 1,
        bindings: Map<String, String?> = emptyMap(),
    ): EvalContext {
        val ctx = EvalContext(baseUrl = baseUrl, key = key, page = page)
        // 源级自定义变量的初值就是规则里声明的那一串：脚本 `JSON.parse(source.getVariable())`
        // 改完再 `setVariable` 整串写回，改的是这一份任务级副本（不写回 Room，理由见 EvalContext）
        ctx.sourceVariable = rules.variable
        jsHost?.let { host ->
            ctx.js = host.bridgeFor(
                ctx = ctx,
                guard = guard,
                transport = sandboxGateway,
                cookies = cookieJar,
                bindings = mapOf(
                    "chapterJson" to null,
                    "title" to null,
                    "sourceJson" to sourceJson,
                ) + bindings,
            )
        }
        return ctx
    }

    /**
     * 详情页/目录页在手的调用点把书实体灌进绑定，让 `{{book.name}}` 与脚本里的 `book` 有值。
     *
     * 只给实体真有的字段：上游 `book` 另有 `kind`/`lastChapter` 两项，本仓的 [BookInfoEntity]
     * 不带它们（那两个字段的落点在 [SearchBookEntity] 上，详情实体没有），故不提供——
     * 脚本读到 `undefined` 比读到一个恒空串更容易发现问题。
     */
    private fun bookBindings(bookShelf: BookShelfEntity): Map<String, String?> = mapOf(
        "bookJson" to bookShelf.bookInfo?.let { info ->
            buildJsonObject {
                put("name", info.name.orEmpty())
                put("author", info.author.orEmpty())
                put("intro", info.introduce.orEmpty())
                put("coverUrl", info.coverUrl.orEmpty())
                put("tocUrl", info.chapterUrl.orEmpty())
            }.toString()
        },
    )

    /**
     * 搜索链路（§9 的 1~7 步在搜索面上的装配）：searchUrl 渲染（`{{key}}`/`{{page}}` 展开、
     * 相对落位、URL 选项）→ 取文 → 列表字段求值 → 逐条目提取字段。
     *
     * 未配 searchUrl 返回空列表而非报错：这是配置形态（该源无搜索），调用方据此走
     * 「本源不支持搜索」的提示，与「搜索失败」区分。
     */
    override suspend fun searchBook(content: String, page: Int): List<SearchBookEntity> =
        withContext(Dispatchers.IO) {
            val searchUrl = rules.searchUrl ?: return@withContext emptyList()  // 未配搜索地址：非错误，本源无搜索
            val ctx = newContext(
                baseUrl = sourceBase(),
                // {{key}} 展开值按表单百分号编码（本仓规定，登记 §11）：不编码时中文关键词
                // 在 OkHttp HttpUrl 里直接非法；编码错不如不编码坏的根源在「上游是否自行编码」无文档
                key = tryEncodeKey(content),
                page = page,
                // 搜索阶段手上还没有书实体：book/chapter 两个绑定恒缺（见 newContext 的说明）
            )
            val evaluator = ScriptRuleEvaluator(ctx)
            val pageData = fetchPage(searchUrl, ctx, evaluator)
            // 落位基准推进到请求落点：字段结果里的相对地址「写在哪页、就相对那页」（§6.5）
            ctx.baseUrl = pageData.url
            parseBookList(pageData.text, ctx, evaluator, RuleObjectKind.SEARCH, fallbackKind = null)
        }

    /** 源根作为目录形态的落位基准：裸相对 searchUrl（无 `/` 前缀）按「源根目录 + 相对段」落位 */
    private fun sourceBase(): String =
        if (rules.sourceUrl.endsWith("/")) rules.sourceUrl else rules.sourceUrl + "/"

    /**
     * 关键词的表单百分号编码。`URLEncoder.encode(String, String)` 按契约声明抛
     * `UnsupportedEncodingException`（UTF-8 恒被支持，实际不会发生），捕获后回退原词
     * 而不是抛：编码是传输层适配、不是规则语义，把关键词原样发出去最坏是搜不到，
     * 抛则整个搜索面不可用——与原生链路（`JsoupBookParser.searchBook`）同一取舍。
     */
    private fun tryEncodeKey(keyword: String): String = try {
        java.net.URLEncoder.encode(keyword, "UTF-8")
    } catch (e: Exception) {
        keyword
    }

    /**
     * 列表页 → 书籍条目（搜索与发现共用；发现无 bookList 时回落搜索字段，语料里两对象同构，§1.3）。
     * 条目判据（§1.2 本仓规定）：`name` 与 `bookUrl` 都非空才收录——其余字段缺失只留空。
     */
    internal fun parseBookList(
        pageText: String,
        ctx: EvalContext,
        evaluator: ScriptRuleEvaluator,
        kind: RuleObjectKind,
        fallbackKind: RuleObjectKind?,
    ): List<SearchBookEntity> {
        fun fieldRule(name: String): String =
            rules.rule(kind, name)?.takeIf { it.isNotBlank() }
                ?: fallbackKind?.let { rules.rule(it, name) }.orEmpty()

        val listRule = rules.rule(kind, "bookList")?.takeIf { it.isNotBlank() }
            ?: fallbackKind?.let { rules.rule(it, "bookList") }?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        val extractor = ScriptFieldExtractor(evaluator, rules.sourceUrl)
        // bookList 是列表字段，走列表口径（`ChainTail.SELECTOR`）：裸 CSS 末段（`.box@ul@li` 的
        // `li`）按选择器出节点集，而不是被当属性名筛成空——否则 ~62% 真实源的搜索/发现恒返空
        val items = extractor.listItems(extractor.evaluateListField(listRule, RuleValue.Page(pageText)))
        val books = mutableListOf<SearchBookEntity>()
        for (item in items) {
            val name = extractor.fieldText(item, fieldRule("name"))
            val bookUrlRaw = extractor.fieldText(item, fieldRule("bookUrl"))
            if (name.isBlank() || bookUrlRaw.isBlank()) continue
            books += SearchBookEntity().apply {
                this.name = name
                author = extractor.fieldText(item, fieldRule("author"))
                noteUrl = extractor.fieldUrl(item, fieldRule("bookUrl"), ctx.baseUrl)
                coverUrl = extractor.fieldUrl(item, fieldRule("coverUrl"), ctx.baseUrl)
                lastChapter = extractor.fieldText(item, fieldRule("lastChapter"))
                // 显式 this：外层函数参数 kind（RuleObjectKind）优先于隐式接收者的同名属性，
                // 不限定会把实体字段赋值解析成参数重赋值（val 不可重赋、类型也不匹配）
                this.kind = extractor.fieldText(item, fieldRule("kind"))
                desc = extractor.fieldText(item, fieldRule("intro"))
                tag = rules.sourceUrl
                origin = rules.name
            }
        }
        return books
    }

    /** 取文 + `@@规则` 递归求值闭包（§5.1：URL 插值里的 `@@` 规则跑在当前页上） */
    private suspend fun fetchPage(
        ruleUrl: String,
        ctx: EvalContext,
        evaluator: ScriptRuleEvaluator,
    ): ScriptPage = fetcher.fetchPage(ruleUrl, ctx) { inner ->
        evaluator.evaluate(inner, RuleValue.Page(""))  // 取文前的 URL 没有页面上下文：@@ 递归按空文档求值
    }

    /**
     * 详情链路（ruleBookInfo，规格 §1.4）：取详情页 → init 预处理 → 逐字段求值 → 装配
     * [BookInfoEntity] 并挂回 `bookShelf.bookInfo`。口径与原生 `JsoupBookParser.getBookInfo`
     * 逐条对齐：`tag` 写书源 URL、`origin` 写书源名；intro 为空填「暂无简介」；tocUrl
     * 未配置回落详情页地址（目录就在详情页的站点不配 tocUrl）。
     *
     * **init 预处理（§1.4）**：init 规则以 AllInOne 正则跑在整页上，**首个匹配**作为条目
     * 上下文（本仓规定，登记 §11——「字段=对象键」读法属 JS 分支，Plan 3），后续字段规则
     * 以 `$n` 组引用从该条目取值（TXT 流站点把整页切成「一段文本多字段」的惯用形态）。
     * init 求值结果不是 [RuleResult.Matches]（如 init 配成了链式规则）或无匹配时视为
     * **无预处理**：字段回落整页求值——init 不是必经环节，只是改写「字段规则喂哪个输入」
     * 的可选层（口径登记 §11）。JS 形态的 init 走「字段=对象键」，字段规则被忽略；
     * 两种 init 的判定在 [ScriptRuleEvaluator.evaluateInitObject] 里做（那里才看得出是不是
     * **单个** JS 段）。
     *
     * **canReName（§1.4）在本管道无行为差**：入参 shelf 不携带搜索值（`addFromSearch`
     * 只装 noteUrl/tag），字段解出即填——与原生 getBookInfo 的无条件覆盖同形（§11 登记）。
     *
     * **tocUrl 只支持单个 URL**（§1.4）：多 URL 形态是 `downloadUrls` 一族能力（导入报告
     * 已登记不支持），本管道对解出的文本直接落位。
     */
    override suspend fun getBookInfo(bookShelf: BookShelfEntity): BookShelfEntity =
        withContext(Dispatchers.IO) {
            rules  // 触发惰性装载：坏 JSON 在此抛类型化异常（§12）
            bookShelf.tag = rules.sourceUrl
            val bookInfo = BookInfoEntity()
            bookInfo.noteUrl = bookShelf.noteUrl
            bookInfo.tag = rules.sourceUrl
            bookInfo.origin = rules.name

            // 初始落位基准取详情页地址（先剥选项尾段——尾段不是地址的一部分）；取文后推进到
            // 实际请求落点，字段结果里的相对地址「写在哪页、就相对那页」（§6.5）
            val ctx = newContext(stripTail(bookShelf.noteUrl), bindings = bookBindings(bookShelf))
            val evaluator = ScriptRuleEvaluator(ctx)
            val extractor = ScriptFieldExtractor(evaluator, rules.sourceUrl)
            val pageData = fetchPage(bookShelf.noteUrl, ctx, evaluator)
            ctx.baseUrl = pageData.url
            val input = RuleValue.Page(pageData.text)

            // init 预处理：AllInOne 以首个匹配为条目上下文；非 Matches（如链式规则）视为
            // 无预处理（口径见方法 KDoc）
            val initRule = rules.rule(RuleObjectKind.BOOK_INFO, "init").orEmpty()
            // init 的 JS 分支（§1.4）：脚本回传对象，**字段名即键、字段规则整体被忽略**
            // （本仓规定，登记规格 §11-21——上游也是这么读的，但文档没写；与 AllInOne 那条
            // 「字段规则以 $n 取组」的路径互斥，二者只会走一条）
            val initFields = evaluator.evaluateInitObject(initRule, input)
            val initItem = if (initFields != null) null else initRule.takeIf { it.isNotBlank() }
                ?.let { evaluator.evaluate(it, input) }
                ?.let { it as? RuleResult.Matches }
                ?.items?.firstOrNull()
                ?.let { ScriptFieldExtractor.ItemContext.GroupsCtx(it) }

            fun field(name: String): String = when {
                initFields != null -> initFields[name].orEmpty()
                initItem != null -> extractor.fieldText(initItem, rules.rule(RuleObjectKind.BOOK_INFO, name).orEmpty())
                else -> evaluator.evaluate(rules.rule(RuleObjectKind.BOOK_INFO, name).orEmpty(), input).firstText()
            }

            // URL 类字段的判空口径与列表侧 fieldUrl（Task 2）同形：空串直接给空串、不进落位——
            // 空 raw 走 TocPageUrl.join 的「目录相对」分支会拼出 baseUrl 自身，把「没取到值」
            // 伪装成一个看似合法的地址
            fun fieldUrl(name: String): String {
                val raw = field(name)
                return if (raw.isBlank()) "" else extractor.resolveUrl(raw, ctx.baseUrl)
            }

            bookInfo.name = field("name")
            bookInfo.author = field("author")
            bookInfo.introduce = field("intro").ifBlank { "暂无简介" }
            bookInfo.coverUrl = fieldUrl("coverUrl")
            val tocRaw = field("tocUrl")
            // tocUrl 为空回落详情页 URL；非空则落位（尾段回附由 resolveUrl 处理）
            bookInfo.chapterUrl = if (tocRaw.isBlank()) bookShelf.noteUrl else extractor.resolveUrl(tocRaw, ctx.baseUrl)

            bookShelf.bookInfo = bookInfo
            bookShelf
        }

    /** 以 URL 为落位基准前先剥选项尾段（`,{...}`）：尾段是取文选项、不是地址的一部分 */
    private fun stripTail(url: String): String =
        ScriptUrlOption.splitTail(url)?.first ?: url

    /**
     * 目录链路（ruleToc，规格 §1.5/§7.1）：从章节索引入口逐页抓取章节，装配
     * [ChapterListEntity] 列表挂回 `bookShelf.chapterList`。终止判据与上限策略与原生
     * `TocPager` 同一套（§12：contentRef 跨页去重、零新增即到底、回环即停、触顶截断），
     * 驱动方式换成 `nextTocUrl` 的字符串规则/URL 数组（经 `ScriptTocPager`/`ScriptPageChain`）。
     *
     * 章节索引入口 = 详情页解出的 tocUrl（`bookInfo.chapterUrl`，含选项尾段），未配置时
     * 回落详情页地址——「目录就在详情页」的站点不配 tocUrl，与原生同形。翻页循环里
     * `ctx.baseUrl` 逐页推进到当前页，让下一页字段与章节链接的相对落位都按
     * 「写在哪页、就相对哪页」走（§6.5）。
     */
    override suspend fun getChapterList(bookShelf: BookShelfEntity): WebChapterEntity<BookShelfEntity> =
        withContext(Dispatchers.IO) {
            bookShelf.tag = rules.sourceUrl
            // 章节索引入口 = 详情页解出的 tocUrl（含选项尾段），无则详情页地址——与原生同形
            val entryUrl = bookShelf.bookInfo?.chapterUrl ?: bookShelf.noteUrl
            // 目录在详情之后跑，`bookShelf.bookInfo` 已在：把书实体灌进 JS 绑定
            val ctx = newContext(stripTail(entryUrl), bindings = bookBindings(bookShelf))
            val evaluator = ScriptRuleEvaluator(ctx)
            val extractor = ScriptFieldExtractor(evaluator, rules.sourceUrl)
            val pager = ScriptTocPager(extractor, rules) { urlRule -> fetchPage(urlRule, ctx, evaluator) }
            bookShelf.chapterList = pager.collect(
                entryUrl = entryUrl,
                ctx = ctx,
                noteUrl = bookShelf.noteUrl,
                tag = rules.sourceUrl,
            )
            WebChapterEntity(bookShelf, false)
        }

    /**
     * 分类页（发现链路的翻页入口）：`url` 是 `ExploreUrlFormat` 条目里的 urlRule（可含 `{{page}}`），
     * 页码换算与渲染收在 [loadExplorePage] 的取文层（脚本 URL 语义，不套 `ListPageUrl` 的
     * 首页裁剪——对算好的 URL 再裁一次会裁掉真实页码段）。
     *
     * 与原生 `JsoupBookParser.getKindBook` 同一取舍：分类页失败按**空列表**返回而不是抛，
     * 页面按「没数据」处置；取消（[CancellationException]）原样上抛——吞掉它会让被取消的
     * 调用方误判成「本分类无书」。
     */
    override suspend fun getKindBook(url: String, page: Int): List<SearchBookEntity> =
        withContext(Dispatchers.IO) {
            if (url.isBlank()) return@withContext emptyList()
            try {
                loadExplorePage(url, page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(tag, "getKindBook 失败: url=$url", e)
                emptyList()
            }
        }

    /**
     * 书库数据（纯网络解析，发现链路的书城首页入口）：`exploreUrl` 条目切分后逐分类抓首页，
     * 拼装成一个 [LibraryEntity]。**不碰缓存**——读哪份缓存、何时过期、要不要回写全是仓库层
     * （`module_find` 的 `BookSourceRepository`）的策略，与原生 `JsoupBookParser.fetchLibraryData`
     * 的分工同一条。
     *
     * 三个口径（均对齐原生）：
     * - 未配 `exploreUrl` 返回空实体（`kindBooks` 为 null）：配置形态而非错误，页面按「书库无数据」处理；
     * - 单个分类抓取失败按**空区块**计入而不中断整批：书城少一个分类仍是可用页面，整批失败会连
     *   别的分类一起丢；代价是「网络全挂」体现为「区块在、书全空」，由调用方（仓库）以
     *   「全部分类都没书」为信号拒绝回写缓存；
     * - 取消（[CancellationException]）原样上抛，不吞成空区块。
     */
    override suspend fun fetchLibraryData(): LibraryEntity = withContext(Dispatchers.IO) {
        val exploreUrl = rules.exploreUrl ?: return@withContext LibraryEntity()  // 未配发现 = 配置形态非错误（对齐原生 kinds 为空）
        val entries = ExploreUrlFormat.split(exploreUrl)
        if (entries.isEmpty()) return@withContext LibraryEntity()
        val blocks = mutableListOf<LibraryKindBookListEntity>()
        for (entry in entries) {
            try {
                blocks += LibraryKindBookListEntity(entry.title, entry.urlRule, loadExplorePage(entry.urlRule, 1))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(tag, "发现分类抓取失败: ${entry.title}", e)
                blocks += LibraryKindBookListEntity(entry.title, "", emptyList())
            }
        }
        LibraryEntity().apply { kindBooks = blocks }
    }

    /**
     * 发现页 URL 规则串 → 分类页条目：GET 形态、`{{page}}` 初值 1（首页由 URL 模板自身渲染，
     * 不做 `ListPageUrl` 式首页裁剪）。字段求值用 ruleExplore，未配的字段回落 ruleSearch
     * （两规则对象语料同构，§1.3；与原生「发现规则没配搜索规则就回落通用搜索规则」同口径）。
     * 消费者是本类的两个发现面（分类条目列表与首屏书目）；失败直接抛，容忍口径由两个调用面各自决定。
     */
    private suspend fun loadExplorePage(urlRule: String, page: Int): List<SearchBookEntity> {
        val ctx = newContext(baseUrl = sourceBase(), page = page)
        val evaluator = ScriptRuleEvaluator(ctx)
        val pageData = fetchPage(urlRule, ctx, evaluator)
        ctx.baseUrl = pageData.url
        return parseBookList(pageData.text, ctx, evaluator, RuleObjectKind.EXPLORE, fallbackKind = RuleObjectKind.SEARCH)
    }

    /**
     * 正文链路（ruleContent，规格 §1.6/§7.2/§7.3）：从章节入口 [contentRef]（含选项尾段，
     * 取文时由 URL 解析侧消费）逐页抓取并拼接整章文本。终止判据与目录链**刻意不同**
     * （§7.2）：没有「零新增即到底」——正文没有去重语义，停止只认空/`null`、回环访问集
     * 与 50 页上限；**不套用 `ChapterPageMatcher`**（§7.3 明令）：`nextContentUrl` 是作者
     * 显式给出的下一页，可信度高于 URL 形状启发式。`replaceRegex` 以净化形态跑在拼接后的
     * 整章串上（规则可能跨段）。content 为空串时由读取层判「本章取失败」，与原生同分工。
     *
     * [ScriptContentPager] 持有本次求值的 [EvalContext]：每取回一页把 `baseUrl` 推进到
     * 该页，让 `nextContentUrl` 结果里的相对链接按「写在哪页、就相对哪页」落位（§6.5）。
     */
    override suspend fun fetchChapterText(contentRef: String): String = withContext(Dispatchers.IO) {
        // 本调用点手上只有一个 contentRef 字符串，章名要查 Room 才有：chapter/title 绑定恒缺（见 newContext）
        val ctx = newContext(stripTail(contentRef))
        val evaluator = ScriptRuleEvaluator(ctx)
        val extractor = ScriptFieldExtractor(evaluator, rules.sourceUrl)
        val pager = ScriptContentPager(extractor, rules, ctx) { urlRule -> fetchPage(urlRule, ctx, evaluator) }
        pager.collect(contentRef)
    }
}
