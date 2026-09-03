package com.ebook.common.analyze.source

import android.content.Context
import com.xrn1997.common.util.Logger
import com.ebook.api.cache.ACache
import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.SearchRule
import com.ebook.api.service.source.BookSourceNetwork
import com.ebook.api.utils.JsoupHelper
import com.ebook.common.event.LIBRARY_CACHE_KEY
import com.ebook.common.manager.ErrorAnalyzeContentManager
import com.ebook.db.entity.BookContentEntity
import com.ebook.db.entity.BookInfoEntity
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.ChapterListEntity
import com.ebook.db.entity.LibraryEntity
import com.ebook.db.entity.LibraryKindBookListEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.db.entity.WebChapterEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * 基于 JSON 规则的书源解析器
 * 根据 BookSourceRule 动态解析 HTML，支持通用书源配置
 */
class JsoupBookParser(
    private val rule: BookSourceRule,
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
                content
            }

            // 计算页码
            val pageParam = rule.searchPage
            val actualPage = if (pageParam.start > 0) {
                (page - 1) * pageParam.step + pageParam.start
            } else {
                page
            }

            val url = rule.searchUrl
                .replace("{{keyword}}", keyword)
                .replace("{{page}}", actualPage.toString())
                .replace("{{pageParam}}", pageParam.param)

            val method = rule.searchMethod.ifEmpty { rule.method }
            val body = rule.searchBody
                .replace("{{keyword}}", keyword)
                .replace("{{page}}", actualPage.toString())

            val html = network.getPage(url, method, body)
            parseSearchBook(html)
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
            chapter.durChapterUrl = JsoupHelper.parseUrl(rule.url, JsoupHelper.selectAttr(el, ruleToc.url))
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

    // region 正文内容
    override suspend fun getBookContent(
        context: Context,
        durChapterUrl: String,
        durChapterIndex: Int
    ): BookContentEntity = withContext(Dispatchers.IO) {
        val bookContent = BookContentEntity()
        bookContent.durChapterIndex = durChapterIndex
        bookContent.durChapterUrl = durChapterUrl
        bookContent.tag = rule.url

        try {
            val contentRule = rule.ruleContent
            val content = StringBuilder()
            // 章节分页基准 = 目录页给出的原始章节 URL（即本章第一页），**不对入口做后缀剥离**。
            // 此类站点一章拆多页（/5/3943720、/5/3943720-2…），且**最后一页的"下一页"链接指向下一章首页**，
            // 盲目跟进会把后续多章拼进本章（正文错乱 + 海量冗余请求），故仅跟进同章分页链接，
            // 判定见 [ChapterPageMatcher.isSameChapterPage]。
            // 为何不剥离入口：入口自身就以「-数字」结尾时（章节号写在连字符后，如 /1234-15.html），
            // 剥离会把基准裁成 /1234，与相邻章 /1234-16.html 剥离后同形 → 判等成立 → 一路跟进后续章节
            // 直到 MAX_CONTENT_PAGES（正文串章 + 数十次冗余请求），恰是本段注释要防的场景。
            val chapterBaseUrl = durChapterUrl
            // 已访问页集合：既防翻页链接回环死循环，又兼作单章抓页数上限的计数依据（MAX_CONTENT_PAGES）
            val visited = mutableSetOf(durChapterUrl)
            var currentUrl: String? = durChapterUrl

            while (currentUrl != null && visited.size <= MAX_CONTENT_PAGES) {
                val relativeUrl = currentUrl.replace(rule.url, "")
                val html = network.getPage(relativeUrl)
                val doc = Jsoup.parse(html)
                val contentElement = doc.selectFirst(contentRule.content)

                if (contentElement != null) {
                    // 提取正文文本：优先使用 p 标签，否则使用 wholeText
                    val paragraphs = contentElement.select("p")
                    val text = if (paragraphs.isNotEmpty()) {
                        paragraphs.mapNotNull { p ->
                            val t = p.text().trim()
                            if (t.isNotEmpty()) "　　$t" else null
                        }.joinToString("\r\n")
                    } else {
                        contentElement.wholeText()
                            .replace("&nbsp;", "　")
                            .trim()
                    }
                    if (content.isNotEmpty() && text.isNotEmpty()) {
                        content.append("\r\n")
                    }
                    content.append(text)
                }

                // 查找下一页：仅当链接属于本章分页且未访问过时才跟进，否则结束（视为本章最后一页）
                currentUrl = if (contentRule.nextPage.isNotEmpty()) {
                    val next = JsoupHelper.parseUrl(rule.url, JsoupHelper.selectAttr(doc, contentRule.nextPage))
                    if (next.isNotEmpty() &&
                        ChapterPageMatcher.isSameChapterPage(next, chapterBaseUrl) &&
                        visited.add(next)
                    ) {
                        next
                    } else {
                        null
                    }
                } else {
                    null
                }
            }

            // 应用清理规则
            bookContent.durChapterContent = JsoupHelper.applyReplaceRules(
                content.toString(),
                contentRule.replaceRules.filter { it.enabled }
            )
        } catch (e: Exception) {
            Logger.e(TAG, "getBookContent: ", e)
            ErrorAnalyzeContentManager.writeNewErrorUrl(context, durChapterUrl)
            bookContent.durChapterContent = rule.url + UNSUPPORTED_CONTENT_MARKER
        }

        bookContent
    }

    // endregion

    // region 分类书籍（发现）
    override suspend fun getKindBook(url: String, page: Int): List<SearchBookEntity> = withContext(Dispatchers.IO) {
        try {
            val findRule = rule.ruleFind
            if (findRule.url.isEmpty()) {
                return@withContext emptyList()
            }

            // 计算页码
            val pageParam = rule.searchPage
            val actualPage = if (pageParam.start > 0) {
                (page - 1) * pageParam.step + pageParam.start
            } else {
                page
            }

            val kindUrl = findRule.url
                .replace("{{kind}}", url)
                .replace("{{page}}", actualPage.toString())
                .replace("{{pageParam}}", pageParam.param)

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
        if (cachedData != null && cachedData.isNotEmpty()) {
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
                val kindUrl = findRule.url
                    .replace("{{kind}}", kind.url)
                    .replace("{{page}}", "1")
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
         * 解析失败时的占位文案标记（正文 = 书源 URL + 本标记，见 [getBookContent] 的 catch 分支）。
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
    fun stripPageSuffix(url: String): String = PAGE_SUFFIX_REGEX.replace(url, "\$1")
}
