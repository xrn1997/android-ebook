package com.ebook.common.analyze.source

import android.content.Context
import com.ebook.api.service.source.BookSourceNetwork
import com.ebook.api.utils.JsoupHelper
import com.ebook.common.analyze.local.BookLocation
import com.ebook.common.analyze.local.ChapterContent
import com.ebook.common.analyze.local.ChapterEntry
import com.ebook.common.analyze.local.ChapterReader
import com.ebook.common.manager.ErrorAnalyzeContentManager
import com.ebook.common.store.BookStore
import com.xrn1997.common.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * 网络书的章节正文读取器（spec §7 §10 M1b）。
 *
 * 与 [JsoupBookParser] 的分界：本类只管**正文获取与存储**，发现类能力（搜索/目录/分类）
 * 留在 `BookParser`。`readChapter` 的语义是"给我这章的正文"——如果章文件已存在就直接读，
 * 否则从网络抓取并写入章文件，下次再读就是纯文件 I/O。
 *
 * 多页拼接逻辑从 `JsoupBookParser.getBookContent` 搬来，判定规则不变：
 * 只跟进同章分页链接（[ChapterPageMatcher.isSameChapterPage]），上限 [MAX_CONTENT_PAGES]。
 */
@Singleton
class JsoupSourceReader @Inject constructor(
    private val store: BookStore,
    private val bookSourceManager: BookSourceManager,
    @ApplicationContext private val context: Context,
    @Named("source") private val okHttpClient: OkHttpClient,
) : ChapterReader {

    override suspend fun readChapter(
        entry: ChapterEntry,
        location: BookLocation,
    ): ChapterContent = withContext(Dispatchers.IO) {
        if (store.hasChapter(location, entry.index)) {
            return@withContext readChapterFromFile(entry, location, store)
        }
        fetchAndStore(entry, location)
    }

    /**
     * 纯文件读取路径（无网络），供测试与已缓存场景使用。
     *
     * 放在 companion object 里作为静态函数：测试无需构造完整的 DI 依赖图即可验证文件读取行为。
     */
    companion object {
        private const val TAG = "JsoupSourceReader"

        /** 单章最多抓取的正文分页数：兜底防御翻页链接异常导致的死循环 */
        const val MAX_CONTENT_PAGES = 50

        /**
         * 解析失败时的占位文案标记。与 [JsoupBookParser.UNSUPPORTED_CONTENT_MARKER] 同义，
         * 抽出供下载侧校验使用。
         */
        const val UNSUPPORTED_CONTENT_MARKER = "站点暂时不支持解析"

        /**
         * 从章文件读取正文（无网络 I/O）。
         *
         * @param entry 章节索引
         * @param location 该书的内容仓库定位
         * @param store 内容仓库实例
         * @return 章节内容，文件不存在时段落列表为空
         */
        internal fun readChapterFromFile(
            entry: ChapterEntry,
            location: BookLocation,
            store: BookStore,
        ): ChapterContent {
            val paragraphs = store.readParagraphs(location, entry.index)
            return ChapterContent(title = entry.title, paragraphs = paragraphs)
        }
    }

    /**
     * 从网络抓取正文并写入章文件。
     *
     * 抓取逻辑从 `JsoupBookParser.getBookContent` 搬来：多页拼接 + 同章分页判定 + 清理规则。
     * 抓取结果以**单段落**存储（`listOf(fullText)`），保持与旧 `durChapterContent` 行为一致——
     * `ReadBookActivity` 的排版管线（`ReaderTypesetter.lineStartOffsets`）按 `\n` 分段，
     * 单段落的 `displayText` 等于原文，排版结果与旧实现完全一致。
     */
    private suspend fun fetchAndStore(
        entry: ChapterEntry,
        location: BookLocation,
    ): ChapterContent {
        val parser = bookSourceManager.requireParser() as? JsoupBookParser
            ?: throw IllegalStateException("当前书源不是 JsoupBookParser，无法抓取网络正文")

        val rule = parser.rule
        val network = BookSourceNetwork(rule, okHttpClient)
        val content = StringBuilder()

        try {
            val contentRule = rule.ruleContent
            // 章节分页基准 = 目录页给出的原始章节 URL（即本章第一页），**不对入口做后缀剥离**。
            // 与 JsoupBookParser.getBookContent 保持一致的判定逻辑。
            val chapterBaseUrl = entry.contentRef
            // 已访问页集合：既防翻页链接回环死循环，又兼作单章抓页数上限的计数依据
            val visited = mutableSetOf(entry.contentRef)
            var currentUrl: String? = entry.contentRef

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
                    val next = JsoupHelper.parseUrl(
                        rule.url,
                        JsoupHelper.selectAttr(doc, contentRule.nextPage)
                    )
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
            val rawText = JsoupHelper.applyReplaceRules(
                content.toString(),
                contentRule.replaceRules.filter { it.enabled }
            )

            // 以单段落存储，保持与旧 durChapterContent 排版行为一致
            store.writeChapter(location, entry.index, listOf(rawText))

            return ChapterContent(title = entry.title, paragraphs = listOf(rawText))
        } catch (e: Exception) {
            Logger.e(TAG, "fetchAndStore: ", e)
            ErrorAnalyzeContentManager.writeNewErrorUrl(context, entry.contentRef)
            throw IllegalStateException("章节内容解析失败: ${entry.contentRef}", e)
        }
    }
}
