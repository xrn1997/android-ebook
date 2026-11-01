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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

/** 原生正文单章最多跟进的页数：防规则配错时无限翻页。 */
private const val MAX_CONTENT_PAGES = 50

/**
 * 基于 JSON 规则的书源解析器
 * 根据 BookSourceRule 动态解析 HTML，支持通用书源配置
 */
class JsoupBookParser(
    /**
     * 书源规则。**internal**：正文抓取已不读它（取文走 [fetchChapterText] 接缝），
     * 对外没有消费者——「缓存有没有换成新行」由测试断言实例更换（`assertNotSame`）即可，
     * 规则内容随构造固化属实现细节；依赖方的 test source set 不是 friend module，
     * 跨模块读 internal 本就编译不过（同模块测试源集可见）。
     */
    internal val rule: BookSourceRule,
    okHttpClient: OkHttpClient
) : BookParser, BookContentParser {
    private val logTag = "JsoupBookParser[${rule.name}]"
    private val network = BookSourceNetwork(rule, okHttpClient)

    // region 搜索书籍
    override suspend fun searchBook(content: String, page: Int): List<SearchBookEntity> = withContext(Dispatchers.IO) {
        val keyword = try {
            URLEncoder.encode(content, rule.charset)
        } catch (e: Exception) {
            Logger.w(logTag, "URLEncoder.encode failed for charset=${rule.charset}, falling back to raw content", e)
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

        Logger.d(logTag, "searchBook: url=$url, method=$method")
        val pageUrl = network.absoluteUrl(url)
        val html = network.getPage(pageUrl, method, body)
        val results = parseSearchBookWithRule(html, rule.ruleSearch, pageUrl)
        Logger.d(logTag, "searchBook: parsed ${results.size} results")
        results
    }

    /**
     * 按给定搜索规则解析一段 HTML。
     *
     * 可见性为 `internal`（不是 private）：这是搜索与书城分类**共用**的那条链
     * （`searchBook` 走 `ruleSearch`，`fetchLibraryData` 走 `ruleFind.ruleSearch` 回落 `ruleSearch`），
     * 对它的直接断言一次锁住两个入口的字段口径，不必为测一行赋值而假造网络层。
     * 同模块测试源可见 `internal`，跨模块不可见（见 docs/architecture.md「脚本书源与解析层」的跨模块可见性口径）。
     *
     * [pageUrl] 是本次真正请求的列表页绝对地址（含查询串也可）：相对书目/封面按它落位，
     * 不能拿源根代替——那会丢掉列表页所在目录。
     */
    internal fun parseSearchBookWithRule(
        html: String,
        searchRule: SearchRule,
        pageUrl: String,
    ): List<SearchBookEntity> {
        val doc = Jsoup.parse(html)
        val elements = JsoupHelper.selectElements(doc, searchRule.list)
        val books = mutableListOf<SearchBookEntity>()
        for (el in elements) {
            val book = SearchBookEntity()
            book.name = JsoupHelper.selectText(el, searchRule.name)
            book.author = JsoupHelper.selectText(el, searchRule.author)
            // 搜索/分类页的相对书目与封面按「链接写在哪页、就相对哪页」落位；
            // 用源根拼会丢掉当前列表页目录，带查询串的页还会把参数当成路径段
            book.noteUrl = TocPageUrl.join(pageUrl, JsoupHelper.selectAttr(el, searchRule.bookUrl), rule.url)
            book.coverUrl = TocPageUrl.join(pageUrl, JsoupHelper.selectAttr(el, searchRule.coverUrl), rule.url)
            book.lastChapter = JsoupHelper.selectText(el, searchRule.lastChapter)
            book.kind = JsoupHelper.selectText(el, searchRule.kind)
            // 原生侧此前漏读 `SearchRule.intro`：规则字段与文档都在，只是这里少一行赋值，
            // 于是 desc 恒为空——列表那格永远只能显示最新章节，详情页（未加书架时走列表实体）
            // 的简介也是一片空。脚本侧 `ScriptBookParser` 一直在读 intro，补上这行是让两种格式对齐；
            // 列表项「简介优先、无简介回落最新章」（见 module_find 的 listBlurb）也才有余地。
            book.desc = JsoupHelper.selectText(el, searchRule.intro)
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
        // 相对地址按「链接写在哪页、就相对那页」落位：详情页目录相对、协议相对（//cdn…）
        // 都不能用相对源根的 JsoupHelper.parseUrl（落位规则见 TocPageUrl.join）。
        bookInfo.coverUrl = TocPageUrl.join(
            bookShelf.noteUrl,
            JsoupHelper.selectAttr(doc, ruleBookInfo.coverUrl),
            rule.url,
        )
        bookInfo.chapterUrl = if (ruleBookInfo.tocUrl.isNotEmpty()) {
            TocPageUrl.join(
                bookShelf.noteUrl,
                JsoupHelper.selectAttr(doc, ruleBookInfo.tocUrl),
                rule.url,
            )
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
        // 优先使用 bookInfo.chapterUrl（章节索引页 URL）；没配 tocUrl 或选择器失配（空串）都回详情页。
        // 不能把空串当 entryUrl 交给 TocPager：getPage("") 会落到源根首页，按目录选择器解出一堆无关链接。
        val chapterUrl = bookShelf.bookInfo?.chapterUrl?.takeIf { it.isNotBlank() } ?: bookShelf.noteUrl
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

            val pageUrl = network.absoluteUrl(kindUrl)
            val html = network.getPage(pageUrl)
            // 使用发现规则的搜索规则，如果没有则使用通用搜索规则
            val searchRule = if (findRule.ruleSearch.list.isNotEmpty()) findRule.ruleSearch else rule.ruleSearch
            parseSearchBookWithRule(html, searchRule, pageUrl)
        } catch (e: Exception) {
            Logger.e(logTag, "getKindBook: ", e)
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
     * 该源没配 `ruleFind.kinds`、或 `enabledExplore=false`（作者关闭发现入口，不是整条源）时
     * 返回空实体（`kindBooks` 为 null）：这是配置形态而非错误，页面按「书城无数据」处理。
     */
    override suspend fun fetchLibraryData(): LibraryEntity = withContext(Dispatchers.IO) {
        val findRule = rule.ruleFind
        // 书城首屏与分类胶囊是同一份发现入口的两个读面，必须一起收口；
        // 只挡 getExploreEntries 会让页面仍显示各分类区块，等于只关了一半。
        if (!rule.enabledExplore || findRule.kinds.isEmpty()) {
            return@withContext LibraryEntity()
        }

        val kindBooksList = mutableListOf<LibraryKindBookListEntity>()

        for ((title, url) in findRule.kinds) {
            try {
                // 首页必须走 ListPageUrl：直接填 1 会生成 /xuanhuan/1，站点 404
                val kindUrl = ListPageUrl.build(
                    findRule.url.replace("{{kind}}", url),
                    1,
                    rule.searchPage,
                )
                val pageUrl = network.absoluteUrl(kindUrl)
                val html = network.getPage(pageUrl)
                val searchRule = if (findRule.ruleSearch.list.isNotEmpty()) findRule.ruleSearch else rule.ruleSearch
                val books = parseSearchBookWithRule(html, searchRule, pageUrl)
                kindBooksList.add(LibraryKindBookListEntity(title, url, books))
            } catch (e: Exception) {
                Logger.e(logTag, "加载分类 $title 失败", e)
                kindBooksList.add(LibraryKindBookListEntity(title, "", emptyList()))
            }
        }

        val result = LibraryEntity()
        result.kindBooks = kindBooksList
        result
    }
    // endregion

    /**
     * 抓取一章正文（原生路径的取文接缝，见 [BookContentParser]）。
     *
     * 多页拼接 + 同章分页判定 + 清理规则跑在拼接后的整章串上；返回**未规范化**文本
     * （缩进与空白折叠归读取层）。空正文返回空串、不抛——选择器失配时站点常回 200 空壳页，
     * 写下去就得到一个「看着已缓存」的空章文件，故由调用方判失败。
     *
     * 失败裹成「章节内容解析失败」：与「书源已失效」是两回事（重导源不会好，重试可能好）；
     * 取消原样上抛，取消不是失败。
     */
    override suspend fun fetchChapterText(contentRef: String): String = withContext(Dispatchers.IO) {
        try {
            val contentRule = rule.ruleContent
            // 章节分页基准 = 目录页给出的原始章节 URL（不对入口剥后缀）；判定只此一份，不得复刻
            val chapterBaseUrl = contentRef
            val pages = PageWalker<String, Document>(maxPages = MAX_CONTENT_PAGES).walk(
                entryUrl = contentRef,
                fetch = { url -> WalkPage(url, Jsoup.parse(network.getPage(url.replace(rule.url, "")))) },
                items = { page ->
                    val contentElement = page.payload.selectFirst(contentRule.content)
                    if (contentElement == null) {
                        emptySequence()
                    } else {
                        // 提取正文文本：优先 p 标签，否则 wholeText。不补缩进——缩进是表现层
                        val paragraphs = contentElement.select("p")
                        val text = if (paragraphs.isNotEmpty()) {
                            paragraphs.map { it.text().trim() }
                                .filter { it.isNotEmpty() }
                                .joinToString("\n")
                        } else {
                            contentElement.wholeText()
                                .replace("&nbsp;", "　")
                                .trim()
                        }
                        if (text.isEmpty()) emptySequence() else sequenceOf(text)
                    }
                },
                next = { page ->
                    if (contentRule.nextPage.isEmpty()) {
                        null
                    } else {
                        val raw = JsoupHelper.selectAttr(page.payload, contentRule.nextPage)
                        // 相对链接相对**当前页目录**落位，与目录翻页共用 TocPageUrl.join；
                        // 空链接返回空串（不落成目录自身），交给 takeIf 终止。
                        TocPageUrl.join(page.url, raw, rule.url)
                            .takeIf { it.isNotEmpty() && ChapterPageMatcher.isSameChapterPage(it, chapterBaseUrl) }
                    }
                },
            )
            val content = pages.items.joinToString("\n")
            // 清理规则跑在拼接后的整章串上（规则可能跨段）
            JsoupHelper.applyReplaceRules(content, contentRule.replaceRules.filter { it.enabled })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(logTag, "fetchChapterText: ", e)
            throw IllegalStateException("章节内容解析失败: $contentRef", e)
        }
    }
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
 * [TocRule.pageUrl] 渲染出第 `page` 页地址，三种形态的基准不同：
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
     * 把页面上的地址按浏览器**路径**相对语义落成绝对地址（点段折叠除外，见下），落位形态如下：
     * - 空白：选择器无匹配/字段为空，原样返回空串（调用方据此终止，不落成当前目录或源根）；
     * - `?`/`#` 开头的裸片段不按浏览器「同资源换查询/片段」语义处理，而按普通相对路径直接拼接
     *   （既有书源兼容口径，不随路径落位一起改）：章节/翻页取的是资源地址，不把片段当下一页；
     * - `http(s)://` 开头：已是绝对地址，原样（大小写不敏感——`HTTP://host/x` 也是绝对地址，
     *   落进相对分支会得到 `https://host/HTTP://host/x`）；
     * - `//host/path` 开头：**协议相对**——沿用**源根**的 scheme（与 `/` 形态同源；网页通用惯用法：
     *   站点同时服务 http/https 时 meta 与 href 都这么写，`og:novel:read_url` 是重灾区）；
     * - 基准自身的查询串/片段不参与目录计算：它们是当前资源的参数，不是路径段
     *   （`https://host/search?q=x` 的目录是 `https://host/`，不是 `.../search?q=x/`）；
     * - `/` 开头：源根相对（`https://host` + 路径）；
     * - 其余（如 `index_3.html`）：**[currentUrl] 所在目录**相对——当前页 `/book/1/`
     *   （目录式，带尾斜杠）+ `index_2.html` → `/book/1/index_2.html`；当前页
     *   `/book/1/index.html`（文件式）+ `list_2.html` → `/book/1/list_2.html`。
     *   与浏览器同一语义：基准不带尾斜杠时末段视为文件、被替换。
     *
     * 协议相对形态必须排在 `/` 形态**之前**判定：`//host/x` 同样以 `/` 开头，
     * 顺序反了会落成 `https://host//host/x`（真语料「小说屋」的 tocUrl 当场炸出，2026-09-11）。
     *
     * **不做 RFC 3986 的点段折叠**：`../x.html`、`./x.html` 按拼接原样保留（浏览器会把
     * `/a/b/../x.html` 折成 `/a/x.html`）。请求侧 OkHttp 的 `HttpUrl` 会归一化，页面取得到；
     * 代价是落库的 `content_ref` 存未归一串，同一章的两种拼法不互认。这里**不**补折叠——
     * 归一化会让当前正常追更的存量条目按新键重解析、凭空多出一次停更，与 ADR-0027
     * 「存量章地址不迁移」是同一取舍；形态由 `TocPagerTest` 锁住。
     *
     * 模板推算与「下一页」选择器候选共用本函数：前者的相对形态相对索引页目录，
     * 后者的相对形态相对**当前页**目录——两者都是「链接写在哪页、就相对那页」。
     */
    fun join(currentUrl: String, raw: String, sourceRoot: String): String = when {
        // 空/空白 = 选择器无匹配，没有下一页。不能落成当前目录：
        // 调用方用 takeIf { isNotEmpty() } 判终止，目录自身会被当成下一页再请求一遍。
        raw.isBlank() -> ""
        raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true) -> raw
        raw.startsWith("//") -> {
            // scheme 取源根（与相邻的 `/` 形态同源）；源根没有 scheme 时按网页惯例回落 https
            val scheme = sourceRoot.substringBefore("://", "").ifEmpty { "https" }
            "$scheme:$raw"
        }
        raw.startsWith("/") -> JsoupHelper.parseUrl(sourceRoot, raw)
        else -> {
            // 基准不带尾斜杠时末段视为文件、只替换最后一段；但基准可能只有 scheme 的
            // 两个斜杠（`https://host`，详情页就是源根首页），那不是路径分隔符——直接截到那里
            // 会拼出 `https://toc.html`。这种形态把基准本身当目录（补 `/`）。
            // 查询串/片段属于当前资源的参数，不是路径，先剥掉再算目录。
            val pathUrl = pathPartOf(currentUrl)
            val pathStart = pathUrl.indexOf("://").let { if (it >= 0) it + 3 else 0 }
            val lastSlash = pathUrl.lastIndexOf('/')
            val dir = when {
                pathUrl.endsWith("/") -> pathUrl
                lastSlash < pathStart -> "$pathUrl/"
                else -> pathUrl.substring(0, lastSlash + 1)
            }
            dir + raw
        }
    }

    /** 当前地址的路径部分：剥掉查询串与片段，二者不是目录基准 */
    private fun pathPartOf(url: String): String {
        val cut = url.indexOfFirst { it == '?' || it == '#' }
        return if (cut >= 0) url.substring(0, cut) else url
    }
}

/**
 * 章节索引分页抓取循环。
 *
 * 提为文件级 `internal` 对象而非 [JsoupBookParser] 的私有方法：抓取经 [collect] 的 `fetch`
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
        // 站点侧页序号：索引页是第 1 页，模板模式从 2 起推算
        var pageNo = 1
        val result = PageWalker<ChapterListEntity, Document>(
            maxItems = MAX_TOC_CHAPTERS,
            stopOnNoNewItems = true,
            keyOf = { it.contentRef },
        ).walk(
            entryUrl = entryUrl,
            fetch = { url -> WalkPage(url, Jsoup.parse(fetch(url))) },
            items = { page ->
                JsoupHelper.selectElements(page.payload, tocRule.list).asSequence().mapNotNull { el ->
                    // 章链接同样按「写在哪页、就相对哪页」落位：相对源根会丢掉目录页所在目录
                    val raw = JsoupHelper.selectAttr(el, tocRule.url)
                    val contentRef = TocPageUrl.join(page.url, raw, sourceRoot)
                    if (contentRef.isEmpty()) {
                        null
                    } else {
                        ChapterListEntity(
                            noteUrl = noteUrl,
                            // 全局序号在整条链走完后统一回填，不能按页内下标写
                            durChapterIndex = 0,
                            contentRef = contentRef,
                            durChapterName = JsoupHelper.selectText(el, tocRule.name),
                            tag = tag,
                        )
                    }
                }
            },
            next = { page ->
                when {
                    // 选择器模式优先：链接是站点实况。无匹配（空串）即终止；
                    // 相对链接按浏览器语义落在当前页目录（与模板推算共用同一落位规则）
                    tocRule.nextPage.isNotEmpty() ->
                        TocPageUrl.join(
                            page.url,
                            JsoupHelper.selectAttr(page.payload, tocRule.nextPage),
                            sourceRoot,
                        ).takeIf { it.isNotEmpty() }
                    // 模板模式：本页有新增才推下一页，页码自 2 起。模板渲染出空串（规则只有
                    // 页码段、或整串是空白）与选择器无匹配是同一件事——没有下一页，不能拿去 fetch
                    tocRule.pageUrl.isNotEmpty() ->
                        TocPageUrl.resolve(entryUrl, tocRule.pageUrl, ++pageNo, pageParam, sourceRoot)
                            .takeIf { it.isNotEmpty() }
                    else -> null
                }
            },
        )
        // 恰好收满且本页再无条目时 walker 不置 truncated；上限告警仍按收满即报（与原循环一致）
        if (result.truncated || result.items.size >= MAX_TOC_CHAPTERS) {
            Logger.w(TAG, "章节索引触及防御上限 $MAX_TOC_CHAPTERS 章，按截断处理（请检查书源的分页规则是否配错）: $entryUrl")
        }
        result.items.forEachIndexed { index, chapter -> chapter.durChapterIndex = index }
        return result.items.toMutableList()
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
     * 相等（含去掉双方扩展名后的兜底相等）才视为本章分页，否则视为本章最后一页（不再跟进）。
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
