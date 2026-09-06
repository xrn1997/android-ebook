package com.ebook.common.analyze.source

import com.xrn1997.common.util.Logger
import com.ebook.api.cache.ACache
import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.PageRule
import com.ebook.api.entity.SearchRule
import com.ebook.api.service.source.BookSourceNetwork
import com.ebook.api.utils.JsoupHelper
import com.ebook.common.event.LIBRARY_CACHE_KEY
import com.ebook.db.entity.BookInfoEntity
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.ChapterListEntity
import com.ebook.db.entity.LibraryEntity
import com.ebook.db.entity.LibraryKindBookListEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.db.entity.WebChapterEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * 基于 JSON 规则的书源解析器
 * 根据 BookSourceRule 动态解析 HTML，支持通用书源配置
 */
class JsoupBookParser(
    internal val rule: BookSourceRule,
    okHttpClient: OkHttpClient
) : BookParser {
    private val TAG = "JsoupBookParser[${rule.name}]"
    private val network = BookSourceNetwork(rule, okHttpClient)
    private val json = Json { ignoreUnknownKeys = true }

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
            book.coverUrl = JsoupHelper.selectAttr(el, searchRule.coverUrl)
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
        // 优先使用 bookInfo.chapterUrl（目录页 URL），如果不存在则使用 noteUrl
        val chapterUrl = bookShelf.bookInfo?.chapterUrl ?: bookShelf.noteUrl
        val relativeUrl = chapterUrl.replace(rule.url, "")
        val html = network.getPage(relativeUrl)
        val doc = Jsoup.parse(html)
        val ruleToc = rule.ruleToc
        val elements = JsoupHelper.selectElements(doc, ruleToc.list)
        val chapters = mutableListOf<ChapterListEntity>()

        for ((index, el) in elements.withIndex()) {
            val chapter = ChapterListEntity()
            chapter.contentRef = JsoupHelper.parseUrl(rule.url, JsoupHelper.selectAttr(el, ruleToc.url))
            chapter.durChapterIndex = index
            chapter.durChapterName = JsoupHelper.selectText(el, ruleToc.name)
            chapter.noteUrl = bookShelf.noteUrl
            chapter.tag = rule.url
            chapters.add(chapter)
        }

        if (ruleToc.reverse || rule.ruleBookInfo.reverseToc) {
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
    override suspend fun getLibraryData(aCache: ACache): LibraryEntity = withContext(Dispatchers.IO) {
        // 先检查缓存
        val cachedData = aCache.getAsString(LIBRARY_CACHE_KEY)
        if (!cachedData.isNullOrEmpty()) {
            try {
                val library = deserializeLibrary(cachedData)
                if (library.kindBooks?.isNotEmpty() == true) {
                    Logger.d(TAG, "使用缓存的书库数据")
                    return@withContext library
                }
            } catch (e: Exception) {
                Logger.w(TAG, "缓存数据解析失败，重新加载", e)
            }
        }

        // 缓存无效，加载发现/分类页面
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
        result.libraryNewBooks = emptyList()

        // 缓存结果
        try {
            val serialized = serializeLibrary(result)
            aCache.put(LIBRARY_CACHE_KEY, serialized)
            Logger.d(TAG, "书库数据已缓存")
        } catch (e: Exception) {
            Logger.w(TAG, "缓存书库数据失败", e)
        }

        result
    }

    override fun analyzeLibraryData(data: String): LibraryEntity {
        return try {
            deserializeLibrary(data)
        } catch (e: Exception) {
            Logger.e(TAG, "analyzeLibraryData: ", e)
            LibraryEntity()
        }
    }

    /**
     * 序列化 LibraryEntity 为 JSON 字符串
     */
    private fun serializeLibrary(library: LibraryEntity): String {
        val cacheData = LibraryCacheData.fromLibrary(library)
        return json.encodeToString(cacheData)
    }

    /**
     * 反序列化 JSON 字符串为 LibraryEntity
     */
    private fun deserializeLibrary(jsonStr: String): LibraryEntity {
        val cacheData = json.decodeFromString<LibraryCacheData>(jsonStr)
        return cacheData.toLibrary()
    }
    // endregion

    companion object {
        /**
         * 解析失败时的占位文案标记（正文 = 书源 URL + 本标记，见 `JsoupSourceReader` 的 catch 分支）。
         *
         * 抽成常量是为了让下载侧能把它与真正文区分开：占位文案**非空**，仅靠 `isBlank()` 判不出来，
         * 照单入库会把「站点暂时不支持解析」当正文永久缓存、任务还被删掉（用户以为下好了，
         * 离线打开只看到这行字）。见 `DownloadService.downloading` 的内容校验。
         */
        const val UNSUPPORTED_CONTENT_MARKER = "站点暂时不支持解析"

        /** 单章最多抓取的正文分页数：兜底防御翻页链接异常导致的死循环 */
        private const val MAX_CONTENT_PAGES = 50
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
 * 章节分页判定（纯函数）。
 *
 * 提为文件级 `internal` 对象而非 [JsoupBookParser] 的私有方法：判定规则是纯字符串运算，
 * 单测无需构造 parser（书源规则 + OkHttpClient）即可直接覆盖各种 URL 形态边界。
 */
internal object ChapterPageMatcher {
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

    /** 去掉章节 URL 末尾的分页后缀（兼容 -2、_2、_2.html 等形态），保留原扩展名 */
    // 多美元插值字符串（$$ 前缀下单个 $ 不开启插值）：IDE 推荐取代 \$1 转义写法；
    // 这里的 $1 仍是字面替换串，由 Regex.replace 解释为捕获组引用
    fun stripPageSuffix(url: String): String = PAGE_SUFFIX_REGEX.replace(url, $$"$1")
}
