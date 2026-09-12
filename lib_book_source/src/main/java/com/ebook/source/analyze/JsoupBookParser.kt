package com.ebook.source.analyze

import com.xrn1997.common.util.Logger
import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.PageRule
import com.ebook.api.entity.SearchRule
import com.ebook.api.entity.TocRule
import com.ebook.api.service.source.BookSourceNetwork
import com.ebook.api.utils.JsoupHelper
import com.ebook.db.entity.BookInfoEntity
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.ChapterListEntity
import com.ebook.db.entity.LibraryEntity
import com.ebook.db.entity.LibraryKindBookListEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.db.entity.WebChapterEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * 基于 JSON 规则的书源解析器
 * 根据 BookSourceRule 动态解析 HTML，支持通用书源配置
 */
class JsoupBookParser(
    /**
     * 书源规则。可见性为 public（不是 internal）：正文读取器（`lib_book_common` 的
     * `JsoupSourceReader`）按规则拼网络请求与清理规则，故规则对本层外部可见；
     * 迁移前两者同模块，internal 足够，拆模块后跨模块访问 internal 直接编译不过。
     */
    val rule: BookSourceRule,
    okHttpClient: OkHttpClient
) : BookParser {
    private val TAG = "JsoupBookParser[${rule.name}]"
    private val network = BookSourceNetwork(rule, okHttpClient)

    // region 搜索书籍
    override suspend fun searchBook(content: String, page: Int): List<SearchBookEntity> = withContext(Dispatchers.IO) {
        try {
            val keyword = try {
                URLEncoder.encode(content, rule.charset)
            } catch (e: Exception) {
                Logger.w(TAG, "URLEncoder.encode failed for charset=${rule.charset}, falling back to raw content", e)
                content
            }

            // 计算页码：URL 模板交 ListPageUrl 渲染（首页不带页码段），
            // 请求体是表单参数、首页仍要带真实页码，故此处单独换算
            val pageParam = rule.searchPage
            val url = ListPageUrl.build(
                rule.searchUrl.replace("{{keyword}}", keyword),
                page,
                pageParam,
            )

            val method = rule.searchMethod.ifEmpty { rule.method }
            val body = rule.searchBody
                .replace("{{keyword}}", keyword)
                .replace("{{page}}", ListPageUrl.actualPage(page, pageParam).toString())

            Logger.d(TAG, "searchBook: url=$url, method=$method")
            val html = network.getPage(url, method, body)
            val results = parseSearchBook(html)
            Logger.d(TAG, "searchBook: parsed ${results.size} results")
            results
        } catch (e: Exception) {
            Logger.e(TAG, "searchBook: ", e)
            emptyList()
        }
    }

    private fun parseSearchBook(html: String): List<SearchBookEntity> {
        return parseSearchBookWithRule(html, rule.ruleSearch)
    }

    private fun parseSearchBookWithRule(html: String, searchRule: SearchRule): List<SearchBookEntity> {
        val doc = Jsoup.parse(html)
        val elements = JsoupHelper.selectElements(doc, searchRule.list)
        val books = mutableListOf<SearchBookEntity>()
        for (el in elements) {
            val book = SearchBookEntity()
            book.name = JsoupHelper.selectText(el, searchRule.name)
            book.author = JsoupHelper.selectText(el, searchRule.author)
            book.noteUrl = JsoupHelper.parseUrl(rule.url, JsoupHelper.selectAttr(el, searchRule.bookUrl))
            book.coverUrl = JsoupHelper.parseUrl(rule.url, JsoupHelper.selectAttr(el, searchRule.coverUrl))
            book.lastChapter = JsoupHelper.selectText(el, searchRule.lastChapter)
            book.kind = JsoupHelper.selectText(el, searchRule.kind)
            book.tag = rule.url
            book.origin = rule.name
            if (book.name.isNotEmpty() && book.noteUrl.isNotEmpty()) {
                books.add(book)
            }
        }
        return books
    }
    // endregion

    // region 书籍详情
    override suspend fun getBookInfo(bookShelf: BookShelfEntity): BookShelfEntity = withContext(Dispatchers.IO) {
        bookShelf.tag = rule.url
        val bookInfo = BookInfoEntity()
        bookInfo.noteUrl = bookShelf.noteUrl
        bookInfo.tag = rule.url
        bookInfo.origin = rule.name

        val relativeUrl = bookShelf.noteUrl.replace(rule.url, "")
        val html = network.getPage(relativeUrl)
        val doc = Jsoup.parse(html)
        val ruleBookInfo = rule.ruleBookInfo

        bookInfo.name = JsoupHelper.selectText(doc, ruleBookInfo.name)
        var author = JsoupHelper.selectText(doc, ruleBookInfo.author)
        if (ruleBookInfo.authorPrefix.isNotEmpty()) {
            author = author.replace(ruleBookInfo.authorPrefix, "")
        }
        bookInfo.author = author
        bookInfo.introduce = JsoupHelper.selectText(doc, ruleBookInfo.intro)
        if (ruleBookInfo.introPrefix.isNotEmpty()) {
            bookInfo.introduce = bookInfo.introduce.replace(ruleBookInfo.introPrefix, "")
        }
        if (bookInfo.introduce.isEmpty()) {
            bookInfo.introduce = "暂无简介"
        }
        bookInfo.coverUrl = JsoupHelper.parseUrl(rule.url, JsoupHelper.selectAttr(doc, ruleBookInfo.coverUrl))
        bookInfo.chapterUrl = if (ruleBookInfo.tocUrl.isNotEmpty()) {
            JsoupHelper.parseUrl(rule.url, JsoupHelper.selectAttr(doc, ruleBookInfo.tocUrl))
        } else {
            bookShelf.noteUrl
        }
        // 将 BookInfoEntity 赋值给 bookShelf.bookInfo
        bookShelf.bookInfo = bookInfo
        bookShelf
    }
    // endregion

    // region 章节列表
    override suspend fun getChapterList(bookShelf: BookShelfEntity): WebChapterEntity<BookShelfEntity> = withContext(Dispatchers.IO) {
        bookShelf.tag = rule.url
        // 优先使用 bookInfo.chapterUrl（章节索引页 URL），如果不存在则使用 noteUrl
        val chapterUrl = bookShelf.bookInfo?.chapterUrl ?: bookShelf.noteUrl
        val chapters = TocPager.collect(
            entryUrl = chapterUrl,
            tocRule = rule.ruleToc,
            pageParam = rule.searchPage,
            sourceRoot = rule.url,
            noteUrl = bookShelf.noteUrl,
            tag = rule.url,
            fetch = { url -> network.getPage(url) },
        )

        if (rule.ruleToc.reverse || rule.ruleBookInfo.reverseToc) {
            chapters.reverse()
            chapters.forEachIndexed { i, ch -> ch.durChapterIndex = i }
        }

        // 将章节列表赋值给 bookShelf.chapterList
        bookShelf.chapterList = chapters
        WebChapterEntity(bookShelf, false)
    }
    // endregion

    // region 分类书籍（发现）
    override suspend fun getKindBook(url: String, page: Int): List<SearchBookEntity> = withContext(Dispatchers.IO) {
        try {
            val findRule = rule.ruleFind
            if (findRule.url.isEmpty()) {
                return@withContext emptyList()
            }

            // {{kind}} 由调用方给出，页码换算与 {{page}} 渲染统一收在 ListPageUrl
            val kindUrl = ListPageUrl.build(
                findRule.url.replace("{{kind}}", url),
                page,
                rule.searchPage,
            )

            val html = network.getPage(kindUrl)
            // 使用发现规则的搜索规则，如果没有则使用通用搜索规则
            val searchRule = if (findRule.ruleSearch.list.isNotEmpty()) findRule.ruleSearch else rule.ruleSearch
            parseSearchBookWithRule(html, searchRule)
        } catch (e: Exception) {
            Logger.e(TAG, "getKindBook: ", e)
            emptyList()
        }
    }
    // endregion

    // region 主页数据
    /**
     * 书库数据（纯网络解析）：按 `ruleFind.kinds` 逐分类抓首页、拼装成一个 [LibraryEntity]。
     *
     * **不碰缓存**——读哪份缓存、何时过期、要不要回写全是仓库层（`module_find` 的 `BookSourceRepository`）的策略
     * （见 [BookParser.fetchLibraryData] 的接口契约）。旧实现把缓存读写长在解析器里，
     * 「下拉刷新」与「缓存命中」在两层之间各说各话，刷新手势实际被缓存吞掉。
     *
     * 单个分类抓取失败按**空区块**计入而不中断整批：书城少一个分类仍是可用页面，整批失败
     * 会连别的分类一起丢。代价是「网络全挂」不体现为异常，而是「区块在、书全空」的实体——
     * 调用方（仓库）以「全部分类都没书」为信号拒绝回写缓存，避免把坏页冻一个 TTL。
     *
     * 该源没配 `ruleFind.kinds` 时返回空实体（`kindBooks` 为 null）：这是配置形态而非错误，
     * 页面按「书库无数据」处理。
     */
    override suspend fun fetchLibraryData(): LibraryEntity = withContext(Dispatchers.IO) {
        val findRule = rule.ruleFind
        if (findRule.kinds.isEmpty()) {
            return@withContext LibraryEntity()
        }

        val kindBooksList = mutableListOf<LibraryKindBookListEntity>()

        for (kind in findRule.kinds) {
            try {
                // 首页必须走 ListPageUrl：直接填 1 会生成 /xuanhuan/1，站点 404
                val kindUrl = ListPageUrl.build(
                    findRule.url.replace("{{kind}}", kind.url),
                    1,
                    rule.searchPage,
                )
                val html = network.getPage(kindUrl)
                val searchRule = if (findRule.ruleSearch.list.isNotEmpty()) findRule.ruleSearch else rule.ruleSearch
                val books = parseSearchBookWithRule(html, searchRule)
                kindBooksList.add(LibraryKindBookListEntity(kind.title, kind.url, books))
            } catch (e: Exception) {
                Logger.e(TAG, "加载分类 ${kind.title} 失败", e)
                kindBooksList.add(LibraryKindBookListEntity(kind.title, "", emptyList()))
            }
        }

        val result = LibraryEntity()
        result.kindBooks = kindBooksList
        result
    }
    // endregion
}

/**
 * 列表（分类页/搜索结果页）分页 URL 渲染（纯函数）。
 *
 * 提为文件级 `internal` 对象而非 [JsoupBookParser] 的私有方法：与 [ChapterPageMatcher] 同一考量，
 * 纯字符串运算，单测无需构造 parser（书源规则 + OkHttpClient）即可覆盖各种模板形态。
 */
internal object ListPageUrl {

    /** 路径段式分页占位符：模板以它结尾时，第 1 页整段裁掉（见 [build]） */
    private const val PATH_PAGE_PLACEHOLDER = "/{{page}}"

    /** 把调用方页序号 [page]（从 1 起）按 [pageParam] 的起始页与步长换算成站点侧页码。 */
    fun actualPage(page: Int, pageParam: PageRule): Int =
        if (pageParam.start > 0) (page - 1) * pageParam.step + pageParam.start else page

    /**
     * 渲染第 [page] 页地址。[template] 支持 {{page}}、{{pageParam}} 占位符，
     * {{kind}}、{{keyword}} 之类的业务占位符由调用方先行替换。
     *
     * **首页不带页码段**：笔趣阁式站点的列表首页是裸路径（`/xuanhuan`、`/so/关键词`），
     * `/xuanhuan/1` 与 `/xuanhuan/` 都是 404 —— 首页照旧填 1 会让首屏直接取不到数据。
     * 故模板以 `/{{page}}` 结尾且换算后正好是起始页时，把这一段整个去掉。
     *
     * 只裁「结尾的页码段」：查询参数式（`?{{pageParam}}={{page}}`）与页码段在模板中段的形态，
     * 其首页地址本身就带 page=1，裁掉反而错，因此这类模板原样渲染真实页码。
     */
    fun build(template: String, page: Int, pageParam: PageRule): String {
        val actualPage = actualPage(page, pageParam)
        val withParam = template.replace("{{pageParam}}", pageParam.param)
        return if (actualPage == firstPage(pageParam) && withParam.endsWith(PATH_PAGE_PLACEHOLDER)) {
            withParam.removeSuffix(PATH_PAGE_PLACEHOLDER)
        } else {
            withParam.replace("{{page}}", actualPage.toString())
        }
    }

    /**
     * 站点侧的起始页码。
     *
     * [actualPage] 在 `start <= 0` 时不做换算（直接用页序号），此时序号 1 就是首页，
     * 不能拿 start（0 或负数）去比对——否则首页会渲染成带 `/1` 的地址而 404。
     */
    private fun firstPage(pageParam: PageRule): Int = if (pageParam.start > 0) pageParam.start else 1
}

/**
 * 章节索引分页地址推算（纯函数）。
 *
 * 提为文件级 `internal` 对象而非 [JsoupBookParser] 的私有方法：与 [ListPageUrl] 同一考量，
 * 纯字符串运算，单测无需构造 parser（书源规则 + OkHttpClient）即可覆盖各种模板形态。
 *
 * [TocRule.pageUrl] 渲染出第 [page] 页地址，三种形态的基准不同：
 * - `http(s)://` 开头：书源作者写死的绝对地址，原样；
 * - `/` 开头：源根相对（`https://host` + 路径）；
 * - 其余（如 `index_{{page}}.html`）：**索引页 URL 所在目录**相对——索引页 `/book/1/`
 *   （目录式，带尾斜杠）+ `index_2.html` → `/book/1/index_2.html`；索引页
 *   `/book/1/index.html`（文件式）+ `list_2.html` → `/book/1/list_2.html`。
 *   与浏览器相对地址解析同一语义：基准不带尾斜杠时末段视为文件、被替换。
 *
 * {{page}} 的渲染复用 [ListPageUrl.build]（{{pageParam}} 同步替换、页码按 [PageRule] 换算）。
 * 模板模式从第 2 页起渲染，换算结果不会落在「起始页裁剪」分支（那只在页码等于起始页时触发）。
 */
internal object TocPageUrl {

    /** 渲染模板第 [page] 页地址（{{page}} 交 [ListPageUrl]，再按 [join] 落位） */
    fun resolve(entryUrl: String, template: String, page: Int, pageParam: PageRule, sourceRoot: String): String =
        join(entryUrl, ListPageUrl.build(template, page, pageParam), sourceRoot)

    /**
     * 把页面上的地址按浏览器相对地址语义落成绝对地址，四种形态基准不同：
     * - `http(s)://` 开头：已是绝对地址，原样；
     * - `//host/path` 开头：**协议相对**——沿用基准的 scheme（网页通用惯用法：站点同时服务
     *   http/https 时 meta 与 href 都这么写，`og:novel:read_url` 是重灾区）；
     * - `/` 开头：源根相对（`https://host` + 路径）；
     * - 其余（如 `index_3.html`）：**[currentUrl] 所在目录**相对——当前页 `/book/1/`
     *   （目录式，带尾斜杠）+ `index_2.html` → `/book/1/index_2.html`；当前页
     *   `/book/1/index.html`（文件式）+ `list_2.html` → `/book/1/list_2.html`。
     *   与浏览器同一语义：基准不带尾斜杠时末段视为文件、被替换。
     *
     * 协议相对形态必须排在 `/` 形态**之前**判定：`//host/x` 同样以 `/` 开头，
     * 顺序反了会落成 `https://host//host/x`（真语料「小说屋」的 tocUrl 当场炸出，2026-09-11）。
     *
     * 模板推算与「下一页」选择器候选共用本函数：前者的相对形态相对索引页目录，
     * 后者的相对形态相对**当前页**目录——两者都是「链接写在哪页、就相对那页」。
     */
    fun join(currentUrl: String, raw: String, sourceRoot: String): String = when {
        raw.startsWith("http://") || raw.startsWith("https://") -> raw
        raw.startsWith("//") -> {
            // scheme 取源根（与相邻的 `/` 形态同源）；源根没有 scheme 时按网页惯例回落 https
            val scheme = sourceRoot.substringBefore("://", "").ifEmpty { "https" }
            "$scheme:$raw"
        }
        raw.startsWith("/") -> JsoupHelper.parseUrl(sourceRoot, raw)
        else -> {
            val dir = if (currentUrl.endsWith("/")) currentUrl else currentUrl.substringBeforeLast('/') + "/"
            dir + raw
        }
    }
}

/**
 * 章节索引分页抓取循环。
 *
 * 提为文件级 `internal` 对象而非 [JsoupBookParser] 的私有方法：抓取经 [collect] 的 [fetch]
 * 函数参数注入，单测用假 HTML 表即可覆盖分页/终止/触顶各形态，无需构造 parser。
 *
 * 分页模式二选一（[TocRule.nextPage] 优先，两者都配时链接是站点实况、模板只是推算）：
 * - **选择器模式**（nextPage）：每页解析完用选择器找「下一页」链接跟进，与正文分页同思路；
 * - **模板模式**（pageUrl）：索引页 URL 有规律，由 [TocPageUrl] 逐页推算，页码沿用
 *   [PageRule]（与搜索/分类一致）。
 *
 * **终止只靠自然条件，上限仅作规则配错时的防御兜底**（[MAX_TOC_CHAPTERS]）：
 * 1. 本页章节经 contentRef 去重后**零新增**——软 404（越界页以 HTTP 200 重复返回首页内容）
 *    与真正的末页都落在这里，与搜索/分类分页同一终止判据；
 * 2. 选择器模式「下一页」无匹配，或指向已访问过的地址（回环）；
 * 3. 已收集章节数触顶 [MAX_TOC_CHAPTERS]：单位是章而不是页——上限对齐列表的本质约束
 *    （一本书的章节数）。真实长篇远达不到，能触顶的只剩「选择器乱配且去重失效」的病态情况，
 *    按截断处理并记日志，不抛异常。
 *
 * 其余约定：
 * - **无分页配置时行为与旧单页实现一致**：只请求索引页一次（两个模式字段均为空串即不分页，
 *   绝大多数站点属于这种）；
 * - 同页内重复的章节条目一并去重（旧实现会给出重复条目，属站点缺陷）；
 * - 网络失败**不回退部分列表**，异常原样抛给调用方按失败刷新——静默接受半本目录会让用户
 *   以为书就这几章，比报错难查。
 */
internal object TocPager {

    private const val TAG = "TocPager"

    /** 章节索引防御上限（章）。极端长篇若真触顶，调大常量即可 */
    const val MAX_TOC_CHAPTERS = 20_000

    /**
     * 从索引页 [entryUrl]（绝对地址）开始逐页抓取章节，返回按出现顺序编号的完整章节列表。
     *
     * [noteUrl]/[tag] 逐章回填（与所在书、所属书源绑定，见「书源归属标记」）；
     * [fetch] 把（可能绝对、可能相对的）页面地址换成 HTML。
     */
    suspend fun collect(
        entryUrl: String,
        tocRule: TocRule,
        pageParam: PageRule,
        sourceRoot: String,
        noteUrl: String,
        tag: String,
        fetch: suspend (String) -> String,
    ): MutableList<ChapterListEntity> {
        val chapters = mutableListOf<ChapterListEntity>()
        // contentRef 去重集：跨页防重复（软 404 的重复首页内容靠它判零新增），同页重复条目顺带去重
        val seenRefs = mutableSetOf<String>()
        // 已访问页集合：拦「下一页」链接回环
        val visitedUrls = mutableSetOf(entryUrl)
        var currentUrl = entryUrl
        // 站点侧页序号：索引页是第 1 页，模板模式从 2 起推算
        var pageNo = 1

        while (true) {
            val sizeBefore = chapters.size
            val doc = Jsoup.parse(fetch(currentUrl))
            for (el in JsoupHelper.selectElements(doc, tocRule.list)) {
                if (chapters.size >= MAX_TOC_CHAPTERS) break
                val contentRef = JsoupHelper.parseUrl(sourceRoot, JsoupHelper.selectAttr(el, tocRule.url))
                if (contentRef.isEmpty() || !seenRefs.add(contentRef)) continue
                chapters.add(
                    ChapterListEntity(
                        noteUrl = noteUrl,
                        durChapterIndex = chapters.size,
                        contentRef = contentRef,
                        durChapterName = JsoupHelper.selectText(el, tocRule.name),
                        tag = tag,
                    )
                )
            }

            if (chapters.size >= MAX_TOC_CHAPTERS) {
                Logger.w(TAG, "章节索引触及防御上限 $MAX_TOC_CHAPTERS 章，按截断处理（请检查书源的分页规则是否配错）: $entryUrl")
                break
            }

            // 零新增即到底：软 404 页与末页同判，两种模式共用这一终止条件
            val nextUrl: String? = when {
                chapters.size == sizeBefore -> null
                // 选择器模式优先：链接是站点实况。无匹配（空串）或回环（已访问）都终止；
                // 相对链接按浏览器语义落在当前页目录（与模板推算共用同一落位规则）
                tocRule.nextPage.isNotEmpty() -> {
                    val candidate = TocPageUrl.join(currentUrl, JsoupHelper.selectAttr(doc, tocRule.nextPage), sourceRoot)
                    candidate.takeIf { it.isNotEmpty() && visitedUrls.add(it) }
                }
                // 模板模式：本页有新增才推下一页，页码自 2 起
                tocRule.pageUrl.isNotEmpty() ->
                    TocPageUrl.resolve(entryUrl, tocRule.pageUrl, ++pageNo, pageParam, sourceRoot)
                else -> null
            }
            currentUrl = nextUrl ?: break
        }
        return chapters
    }
}

/**
 * 章节分页判定（纯函数）。
 *
 * 提为文件级对象而非 [JsoupBookParser] 的私有方法：判定规则是纯字符串运算，
 * 单测无需构造 parser（书源规则 + OkHttpClient）即可直接覆盖各种 URL 形态边界。
 *
 * 可见性为 public（不是 internal）：「同章分页判定」必须与解析器共用这一份实现——
 * 仓库侧的正文读取器（`lib_book_common` 的 `JsoupSourceReader`）跨模块跟进正文分页链接，
 * 复制到仓库侧会让两处判定各自漂移（正文串章这类错乱就来自漂移）。
 *
 * 对外契约只有 [isSameChapterPage] 这一个判定函数：[stripPageSuffix] 是它的中间步骤，保持 internal。
 * 把剥离结果交给调用方，等于邀请它在别处自己拼比对条件——那条拼装一旦与这里不同，
 * 漂移又会回来，而这个对象的存在意义就是把判定收成一处。
 */
object ChapterPageMatcher {
    /**
     * 章节分页后缀：末尾的 `-数字`/`_数字`（可带扩展名），如 /5/3943720-2 的 `-2`。
     *
     * 扩展名入组是为了替换时**保留它**（`$1`）：`X_2.html` 归一为 `X.html` 而不是 `X`，
     * 否则带扩展名的章节 URL（`X.html` + 分页 `X_2.html`）永远对不上。
     */
    private val PAGE_SUFFIX_REGEX = Regex("""[-_]\d+(\.[A-Za-z]+)?$""")

    /** 末尾扩展名：供「入口与分页扩展名形态不一致」的兜底比对使用 */
    private val EXTENSION_REGEX = Regex("""\.[A-Za-z]+$""")

    /**
     * 判断「下一页」链接是否属于同一章节。
     *
     * 只对**候选链接**剥一次分页后缀，再与章节基准（= 目录页给出的原始章节 URL，未剥离）比对；
     * 相等才视为本章分页，否则视为本章最后一页（不再跟进）。
     *
     * 已覆盖的形态：
     * - 基准 `/5/3943720`，分页 `/5/3943720-2`（或 `-2.html`）→ 跟进；
     * - 基准 `/5/3943720.html`，分页 `/5/3943720_2.html` → 跟进；
     * - 基准 `/5/3943720`，下一章 `/5/3943721` → 无后缀可剥，不等，拦下；
     * - 基准 `/1234-15.html`，下一章 `/1234-16.html` → 剥后 `/1234.html` ≠ 基准，拦下
     *   （若对基准也剥离，两者同为 `/1234` 会误判同章而串章）。
     *
     * **已知取舍**：站点把「第 1 页」也写成带后缀形态（基准 `/ch/100-1`、分页 `/ch/100-2`）时，
     * 剥候选得 `/ch/100` ≠ 基准 → 后续页被漏掉。这与「章节号写在连字符后」在结构上完全同形、
     * 无法靠 URL 区分，只能由书源规则显式声明分页模板（属 ADR-0016 多书源工作项）。
     * 两害相权取其轻：漏页只是少内容，串章会污染正文并放大请求量。
     */
    fun isSameChapterPage(url: String, chapterBaseUrl: String): Boolean {
        val stripped = stripPageSuffix(url)
        if (stripped == chapterBaseUrl) return true
        // 兜底：入口与分页的扩展名形态不一致（入口 `/5/3943720`、分页 `/5/3943720-2.html`）时，
        // 去掉双方扩展名再比。**只去扩展名、不再剥数字后缀**：剥两次会让相邻章同形而串章
        return EXTENSION_REGEX.replace(stripped, "") ==
            EXTENSION_REGEX.replace(chapterBaseUrl, "")
    }

    /**
     * 去掉章节 URL 末尾的分页后缀（兼容 -2、_2、_2.html 等形态），保留原扩展名。
     *
     * internal：本模块外没有消费者——外部只需要 [isSameChapterPage] 这个结论，
     * 剥什么、剥几次属实现细节（与本对象只导出一个判定函数的理由见类注释）。
     */
    // 多美元插值字符串（$$ 前缀下单个 $ 不开启插值）：IDE 推荐取代 \$1 转义写法；
    // 这里的 $1 仍是字面替换串，由 Regex.replace 解释为捕获组引用
    internal fun stripPageSuffix(url: String): String = PAGE_SUFFIX_REGEX.replace(url, $$"$1")
}
