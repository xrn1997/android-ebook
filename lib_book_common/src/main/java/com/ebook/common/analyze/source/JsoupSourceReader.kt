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
import com.ebook.common.text.TextNormalizer
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
     * 清理规则跑在拼接后的整章串上（规则可能跨段），再按行切回段落存储；**不写缩进**——
     * 章文件是「抓取后、清洗前」的原文切片，缩进与空白折叠由读取层的 `TextNormalizer` 补
     * （spec §4 §8）。段落按 `\n` 分行存储，章内不再出现 `\r`，排版管线的 CRLF 缺陷无触发面。
     *
     * 空正文**不落盘**：正文选择器失配时站点往往回 HTTP 200 的空壳页，写下去就得到一个
     * "看着已缓存"的空章文件——[BookStore.hasChapter] 会让重试与后续阅读把它当成功短路，
     * 用户侧表现为"显示已下载、翻开是空白页"。此时返回空段落，由调用方判失败。
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
                    // 提取正文文本：优先使用 p 标签，否则使用 wholeText。
                    // 这里**不补缩进**——段首缩进是表现层，由读取层的 TextNormalizer.toDisplayText
                    // 统一补（spec §8），写进存储就不可逆了
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
                    if (content.isNotEmpty() && text.isNotEmpty()) {
                        content.append("\n")
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

            // 应用清理规则（跑在整章串上，规则可能跨段）
            val rawText = JsoupHelper.applyReplaceRules(
                content.toString(),
                contentRule.replaceRules.filter { it.enabled }
            )
            // 统一换行后按行切回段落：存储层只切不洗（spec §4 §8），读取层再规范化
            val stored = TextNormalizer.unifyNewlines(rawText).split('\n')
            if (stored.none { it.isNotBlank() }) {
                // 空正文不落盘，也不覆盖既有章文件：让调用方按失败处理并重试
                Logger.w(TAG, "正文为空，不写章文件: ${entry.contentRef}")
                return ChapterContent(title = entry.title, paragraphs = emptyList())
            }
            store.writeChapter(location, entry.index, stored)

            return ChapterContent(title = entry.title, paragraphs = stored)
        } catch (e: Exception) {
            Logger.e(TAG, "fetchAndStore: ", e)
            ErrorAnalyzeContentManager.writeNewErrorUrl(context, entry.contentRef)
            throw IllegalStateException("章节内容解析失败: ${entry.contentRef}", e)
        }
    }
}
