package com.ebook.common.analyze.source

import android.content.Context
import com.ebook.common.analyze.local.BookLocation
import com.ebook.common.analyze.local.ChapterContent
import com.ebook.common.analyze.local.ChapterEntry
import com.ebook.common.analyze.local.ChapterReader
import com.ebook.common.manager.ErrorAnalyzeContentManager
import com.ebook.common.store.BookStore
import com.ebook.common.text.TextNormalizer
import com.ebook.db.entity.BookShelfEntity
import com.ebook.source.analyze.BookContentParser
import com.ebook.source.analyze.BookParser
import com.ebook.source.analyze.BookSourceNotFoundException
import com.ebook.source.analyze.JsoupBookParser
import com.xrn1997.common.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 网络书的章节正文读取器（spec §7 §10 M1b）。
 *
 * 分界：本类只管**正文存储**——章文件命中直接读盘、空正文不落盘、`\n` 切段、缩进归
 * [TextNormalizer]；「怎么取」归两种出身共用的 [BookContentParser]（原生 [JsoupBookParser]
 * 按规则翻页抓 HTML，脚本解析器按规则求值）。早先原生分支靠 `instanceof` 下转型再掏
 * `rule` 自己拼请求：脚本侧可假可测、原生侧必须真网络 + 真 Context，两条路径的存储策略
 * 还各写一份。取文下沉到 seam 之后，本类只剩一条路径、一份存储策略。
 *
 * **按书绑源**（见 ADR-0016）：抓哪一站的正文由 [BookLocation.sourceUrl]（= 该书在书架上的 `tag`）
 * 决定，而不是由「当前默认书源」决定——多书源共存时后者会把 A 站的书拿去按 B 站规则解析。
 * 由此一条必须知道的语义差异：[readChapter] 先判章文件存在性、命中就直接读盘，
 * 所以**已缓存过的章节即使所属书源被删也照样能读**，只有需要联网补抓的章才会抛
 * [BookSourceNotFoundException]。这是「删源不删书」（见 [SourceAdmin.removeSource]）能成立的前提。
 */
@Singleton
class JsoupSourceReader @Inject constructor(
    private val store: BookStore,
    private val sourceResolver: SourceResolver,
    @ApplicationContext private val context: Context,
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
     * 取不到 parser 的成因分两支处置：本地书是编程错误，网络书的取源失败是业务失败。
     * 混成一个就会说错根因：
     * 1. **本地书被路由到网络 reader**（`tag` 等于 [BookShelfEntity.LOCAL_TAG]）→ [IllegalStateException]
     *    （编程错误）。reader 按 `book_format` 选，本地书永远是 TXT/EPUB reader，正常路由下走不到这里。
     *    [SourceResolver.resolveSource] 对它返回 [SourceBinding.Local]，若照此报成「书源已失效」，用户会收到
     *    「请重新导入或换源」——本地书压根没有书源可导，等于把数据 bug 转嫁成用户操作。
     *    消息带现场（本地 tag 值 + `bookId`），否则只有一行「拿到本地书」的日志无从定位是哪条路由错了。
     * 2. **网络书的 `tag` 为空白**→ [BookSourceNotFoundException]。这与第 1 种**不是一回事**：
     *    网络书本来必须有归属，空白是脏数据（历史行 / 导入链漏传），不是接线错误。脏数据改不了代码，
     *    用户手上唯一有效的处置仍与「源被删」相同（重新导入或换源），所以它必须落在可展示的业务异常里、
     *    由调用方提示出去；报成编程错误会被上层当成「不该发生的分支」吞掉，于是只剩一行日志、
     *    页面既不闪退也永远加载不出来。
     * 3. **有 URL 但取源结果为 Missing/Broken**（源被删 / 原生坏行）→ [BookSourceNotFoundException]。
     *    2、3 两支共用同一异常类型，也正因如此第 1 支必须与它们分开。
     *    **不再走 `requireParser()`**——那条路会拿默认书源的规则去解另一站点的章节 URL，
     *    现象是「不闪退、正文永远加载不出来」，比抛异常坏得多。
     *
     * 第 4 种成因（「取到的 parser 不支持抓正文」）不在本方法判：[BookParser] 是发现面，
     * [BookContentParser] 是取文面，第三方实现可能只满足前者；这件事由 [fetchAndStore]
     * 的转型判，判不过仍报 [IllegalStateException]（编程错误，不是用户可处置的源失效）。
     *
     * 抽成 internal 方法是为了可测：本类的构造要 `@ApplicationContext`，
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
        return sourceResolver.parserOrThrow(location.sourceUrl)
    }

    /**
     * 抓正文并落盘：**一份存储策略，两种出身共用**。
     *
     * 取源刻意放在 try 之外：书源失效既不是解析失败、重导同一 URL 也不会好，被裹掉还会让上层
     * （ViewModel / DownloadService）认不出这个类型、说错根因。取到之后只做三步：
     * 取文本（失败时记排查线索后原样上抛）→ 规范化并切段 → 空则返回空段落、否则写章文件。
     *
     * **不合并 error mode**（ADR-0046）：脚本的类型化异常原样上抛（消息面向用户，裹掉等于把真话
     * 换成查不出根因的套话），原生由 [JsoupBookParser.fetchChapterText] 裹成「章节内容解析失败」
     * （选择器失配，重试可能好）。本类只记同一份线索，不改写类型。取消不是失败，不记不裹。
     */
    private suspend fun fetchAndStore(entry: ChapterEntry, location: BookLocation): ChapterContent {
        val parser = resolveParser(location) as? BookContentParser
            ?: throw IllegalStateException("该书源不支持抓取网络正文：${location.sourceUrl}")

        val rawText = try {
            parser.fetchChapterText(entry.contentRef)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "fetchAndStore: ", e)
            ErrorAnalyzeContentManager.writeNewErrorUrl(context, entry.contentRef)
            throw e
        }

        // 章文件格式是存储层契约：两种出身必须同形，否则同一本书换源后既有章文件读不回来。
        // 存储层只切不洗——缩进与空白折叠由读取层的 TextNormalizer 补（spec §4 §8）。
        val stored = TextNormalizer.unifyNewlines(rawText).split('\n')
        if (stored.none { it.isNotBlank() }) {
            // 空正文不落盘：正文选择器失配时站点往往回 HTTP 200 的空壳页，写下去就得到一个
            // "看着已缓存"的空章文件——BookStore.hasChapter 会让重试与后续阅读把它当成功短路
            Logger.w(TAG, "正文为空，不写章文件: ${entry.contentRef}")
            return ChapterContent(title = entry.title, paragraphs = emptyList())
        }
        store.writeChapter(location, entry.index, stored)

        return ChapterContent(title = entry.title, paragraphs = stored)
    }
}
