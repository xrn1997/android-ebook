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
import com.ebook.db.entity.BookShelfEntity
import com.ebook.source.analyze.BookParser
import com.ebook.source.analyze.BookSourceNotFoundException
import com.ebook.source.analyze.ChapterPageMatcher
import com.ebook.source.analyze.JsoupBookParser
import com.ebook.source.analyze.ScriptContentParser
import com.xrn1997.common.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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
 * 多页拼接原在 `lib_book_source` 的 `JsoupBookParser.getBookContent`，M1b 搬到本类（那边该方法已删）；
 * 规则未变：只跟进同章分页链接（[ChapterPageMatcher.isSameChapterPage]），上限 [MAX_CONTENT_PAGES]。
 *
 * 2d 起本类服务**两种出身的正文**，按 parser 类型分岔：原生 [JsoupBookParser] 自己按 `rule` 翻页，
 * 脚本 [ScriptContentParser] 把「这一章的文本」整串交给本类。分岔只发生在「怎么取」，
 * 「怎么存」（`\n` 切段、空正文不落盘、规范化归 [TextNormalizer]）两条路径逐字同口径——
 * 章文件格式是存储层契约，同形才能让同一本书换源后读回既有章文件。
 * 类名保留历史 `Jsoup` 前缀正因此处唯一的不一致是名字而不是行为。
 *
 * **按书绑源**（见 ADR-0016）：抓哪一站的正文由 [BookLocation.sourceUrl]（= 该书在书架上的 `tag`）
 * 决定，而不是由「当前默认书源」决定——多书源共存时后者会把 A 站的书拿去按 B 站规则解析。
 * 由此一条必须知道的语义差异：[readChapter] 先判章文件存在性、命中就直接读盘，
 * 所以**已缓存过的章节即使所属书源被删也照样能读**，只有需要联网补抓的章才会抛
 * [BookSourceNotFoundException]。这是「删源不删书」（见 `BookSourceManager.removeSource`）能成立的前提。
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
     * 取**这本书归属书源**的 parser（多书源共存的落点，见 ADR-0016）。
     *
     * 三种成因分别抛不同的异常，混成一个就会说错根因：
     * 1. **本地书被路由到网络 reader**（`tag` 等于 [BookShelfEntity.LOCAL_TAG]）→ [IllegalStateException]
     *    （编程错误）。reader 按 `book_format` 选，本地书永远是 TXT/EPUB reader，正常路由下走不到这里。
     *    [BookSourceManager.getParserFor] 对它返回 null，若照此报成「书源已失效」，用户会收到
     *    「请重新导入或换源」——本地书压根没有书源可导，等于把数据 bug 转嫁成用户操作。
     *    消息带现场（本地 tag 值 + `bookId`），否则只有一行「拿到本地书」的日志无从定位是哪条路由错了。
     * 2. **网络书的 `tag` 为空白**→ [BookSourceNotFoundException]。这与第 1 种**不是一回事**：
     *    网络书本来必须有归属，空白是脏数据（历史行 / 导入链漏传），不是接线错误。脏数据改不了代码，
     *    用户手上唯一有效的处置仍与「源被删」相同（重新导入或换源），所以它必须落在可展示的业务异常里、
     *    由调用方提示出去；报成编程错误会被上层当成「不该发生的分支」吞掉，于是只剩一行日志、
     *    页面既不闪退也永远加载不出来。
     * 3. **有 URL 但库里查不到**（源被删）→ [BookSourceNotFoundException]。
     *    2、3 两支共用同一异常类型，也正因如此第 1 支必须与它们分开。
     *    **不再走 `requireParser()`**——那条路会拿默认书源的规则去解另一站点的章节 URL，
     *    现象是「不闪退、正文永远加载不出来」，比抛异常坏得多。
     *
     * 第 4 种成因（「取到的 parser 不支持抓正文」）不在本方法判：2d 起正文抓取有两种出身——原生
     * [JsoupBookParser]（自己按 `rule` 翻页）与脚本 [ScriptContentParser]（按规则求值取文），
     * 本方法只回答「找到源没有」，能不能抓正文由 [fetchAndStore] 的分岔回答，判不过仍报
     * [IllegalStateException]。这里原先的 `as? JsoupBookParser` 是**脚本源进来后必须拆掉的**：
     * 它对脚本行必然失败，会把一条正常的脚本源降级成编程错误。
     *
     * 抽成 internal 方法是为了可测：本类的构造要 `@ApplicationContext` 与 OkHttpClient，
     * 而「按 tag 找源」这条判断不需要网络即可断言（见 JsoupSourceReaderTest）。
     */
    internal suspend fun resolveParser(location: BookLocation): BookParser {
        // 本地书（loc_book）：编程错误。空白 tag 是另一回事——网络书缺归属，属脏数据，
        // 见上面 KDoc 第 1、2 支的分界，两者合并会让用户收到一条他无从执行的提示、或让真该提示的
        // 脏数据被上层按「不该发生」吞掉。
        if (location.sourceUrl == BookShelfEntity.LOCAL_TAG) {
            throw IllegalStateException(
                "网络正文读取拿到了本地书：tag=${BookShelfEntity.LOCAL_TAG}, bookId=${location.bookId}"
            )
        }
        if (location.sourceUrl.isBlank()) {
            throw BookSourceNotFoundException(location.sourceUrl, "bookId=${location.bookId}")
        }
        return bookSourceManager.getParserFor(location.sourceUrl)
            ?: throw BookSourceNotFoundException(location.sourceUrl)
    }

    /**
     * 按 parser 出身分岔抓正文并落盘。
     *
     * 取源刻意放在任何 try 之外：原生分支的 catch 会把一切异常重裹成「章节内容解析失败」并记进
     * 错误 URL 清单（那是给选择器失配留的排查线索）。书源失效既不是解析失败、重导同一 URL
     * 也不会好，被裹掉还会让上层（ViewModel / DownloadService）认不出这个类型、说错根因。
     * 脚本分支不裹（[fetchAndStoreScript] 的 KDoc 给了理由），所以「先取源、后分岔」这个次序
     * 是两条路径共同的 precondition，不是某一分支的实现细节。
     */
    private suspend fun fetchAndStore(
        entry: ChapterEntry,
        location: BookLocation,
    ): ChapterContent = when (val parser = resolveParser(location)) {
        is JsoupBookParser -> fetchAndStoreNative(parser, entry, location)
        is ScriptContentParser -> fetchAndStoreScript(parser, entry, location)
        else -> throw IllegalStateException("该书源不支持抓取网络正文：${location.sourceUrl}")
    }

    /**
     * 原生路径：按 `rule` 的选择器与分页规则抓 HTML。
     *
     * 抓取逻辑原在 `lib_book_source` 的 `JsoupBookParser.getBookContent`，M1b 搬到本类（那边方法已删）：
     * 多页拼接 + 同章分页判定 + 清理规则。
     * 清理规则跑在拼接后的整章串上（规则可能跨段），再按行切回段落存储；**不写缩进**——
     * 章文件是「抓取后、清洗前」的原文切片，缩进与空白折叠由读取层的 `TextNormalizer` 补
     * （spec §4 §8）。段落按 `\n` 分行存储，章内不再出现 `\r`，排版管线的 CRLF 缺陷无触发面。
     *
     * 空正文**不落盘**：正文选择器失配时站点往往回 HTTP 200 的空壳页，写下去就得到一个
     * "看着已缓存"的空章文件——[BookStore.hasChapter] 会让重试与后续阅读把它当成功短路，
     * 用户侧表现为"显示已下载、翻开是空白页"。此时返回空段落，由调用方判失败。
     */
    private suspend fun fetchAndStoreNative(
        parser: JsoupBookParser,
        entry: ChapterEntry,
        location: BookLocation,
    ): ChapterContent {
        val rule = parser.rule
        val network = BookSourceNetwork(rule, okHttpClient)
        val content = StringBuilder()

        try {
            val contentRule = rule.ruleContent
            // 章节分页基准 = 目录页给出的原始章节 URL（即本章第一页），**不对入口做后缀剥离**。
            // 同章分页的判定只此一份，收在 [ChapterPageMatcher.isSameChapterPage] 里，本类不得复刻：
            // 两处各写一遍，改分页形态时就会漂移成正文串章，边界形态由 ChapterPageMatcherTest 钉住。
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
            Logger.e(TAG, "fetchAndStoreNative: ", e)
            ErrorAnalyzeContentManager.writeNewErrorUrl(context, entry.contentRef)
            throw IllegalStateException("章节内容解析失败: ${entry.contentRef}", e)
        }
    }

    /**
     * 脚本路径：解析器答「这一章的文本」，本类答「怎么存」。
     *
     * 两条刻意与原生不同的口径：
     * - **异常原样上抛**（原生裹成「章节内容解析失败」）：脚本求值的类型化异常自带面向用户的中文
     *   消息（「需脚本沙箱执行器」「本项目不支持的能力」「这条规则读不懂」），裹掉等于把真话换成
     *   一句查不出根因的套话，上层还会按「选择器失配、重试可能好」去处置一个重试永远不会好的失败。
     *   错误 URL 清单照记（那是同一份排查线索），[CancellationException] 不记不裹先上抛——
     *   协程取消不是失败，记进清单会让下一次排查把「用户划走了」当成站点问题。
     * - 其余（空正文不落盘、`\n` 切段、不写缩进、`TextNormalizer` 归读取层）与原生逐字同口径：
     *   章文件格式是存储层契约，两种出身必须同形，否则同一本书换源后既有章文件读不回来。
     *
     * 与原生另一处**不是选择而是约束**的差异：本分支不翻页。翻页链由解析器在
     * [ScriptContentParser.fetchChapterText] 内部按 `nextContentUrl` 规则走完（规则说怎么翻，
     * 而不是读取器替它猜），所以这里拿到的一串文本已经是一章的完整版。
     */
    private suspend fun fetchAndStoreScript(
        parser: ScriptContentParser,
        entry: ChapterEntry,
        location: BookLocation,
    ): ChapterContent {
        val rawText = try {
            parser.fetchChapterText(entry.contentRef)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "fetchAndStoreScript: ", e)
            ErrorAnalyzeContentManager.writeNewErrorUrl(context, entry.contentRef)
            throw e
        }
        val stored = TextNormalizer.unifyNewlines(rawText).split('\n')
        if (stored.none { it.isNotBlank() }) {
            // 空正文不落盘：与原生分支同一条理由（写下去就是个「看着已缓存」的空章文件）
            Logger.w(TAG, "正文为空，不写章文件: ${entry.contentRef}")
            return ChapterContent(title = entry.title, paragraphs = emptyList())
        }
        store.writeChapter(location, entry.index, stored)
        return ChapterContent(title = entry.title, paragraphs = stored)
    }
}
