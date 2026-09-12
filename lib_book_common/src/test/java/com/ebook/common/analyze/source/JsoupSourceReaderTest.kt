package com.ebook.common.analyze.source

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ebook.api.entity.BookSourceRule
import com.ebook.common.analyze.local.BookFormat
import com.ebook.common.analyze.local.BookLocation
import com.ebook.common.analyze.local.ChapterEntry
import com.ebook.common.store.BookStore
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.LibraryEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.db.entity.WebChapterEntity
import com.ebook.source.analyze.BookParser
import com.ebook.source.analyze.BookSourceNotFoundException
import com.ebook.source.analyze.JsoupBookParser
import com.ebook.source.analyze.ScriptContentParser
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [JsoupSourceReader] 的测试（Robolectric + [FakeBookSourceManager]）。
 *
 * 为什么要 Robolectric：本类构造要 `@ApplicationContext`（异常分支里写错误 URL 清单用），
 * 纯 JVM 下 `Context` 起不来。断言本身不碰网络——[JsoupSourceReader.resolveParser]
 * 是「按书绑源」这条判断的接缝，取源失败发生在发请求之前。
 *
 * 锁的是 ADR-0016 P3-a 的核心事实：**抓哪一站的正文由 `BookLocation.sourceUrl` 决定，
 * 与「当前默认书源」无关**（旧实现按全局默认源取 parser，多书源下会把 A 站的书按 B 站规则解）。
 * 四种「取不到源」的成因各给一种异常，也是本类的断言重点，分界只有一条判据：
 * **用户能不能自己处置**。本地书被路由到网络 reader 是接线 bug（编程错误），报成「书源已失效」
 * 会让用户去重导一个根本不存在的书源；网络书的归属为空白则是脏数据，报成编程错误会被上层
 * 按「不该发生」吞掉，用户就永远等不到那条提示。
 *
 * 2d 起本类还锁另一件事：**原生与脚本两种出身的正文走同一条存储管线**（`\n` 切段、
 * 空正文不落盘、规范化归读取层），章文件格式必须同形，否则同一本书换源后旧章文件读不回来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JsoupSourceReaderTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    private lateinit var store: BookStore
    private lateinit var context: Context

    /** 脚本分支用例的归属 URL：[FakeBookSourceManager] 按 URL 键控 parser，脚本行也走同一张表 */
    private val SCRIPT_SOURCE_URL = "https://script.example.com"

    @Before
    fun setUp() {
        store = BookStore(tmpDir.root)
        context = ApplicationProvider.getApplicationContext()
    }

    private fun readerWith(manager: BookSourceManager) =
        JsoupSourceReader(store, manager, context, OkHttpClient())

    private fun rule(sourceUrl: String) = BookSourceRule(name = "源<$sourceUrl>", url = sourceUrl)

    @Test
    fun `readChapterFromFile returns content when chapter file exists`(): Unit = runTest {
        val location = BookLocation(
            bookId = "test-book",
            format = BookFormat.NETWORK,
            sourceUrl = "https://bound.example",
        )
        val paragraphs = listOf("段落一", "段落二", "段落三")
        store.writeChapter(location, 0, paragraphs)

        val entry = ChapterEntry(index = 0, title = "第一章", contentRef = "https://example.com/ch1")
        val result = JsoupSourceReader.readChapterFromFile(entry, location, store)

        assertEquals("第一章", result.title)
        assertEquals(paragraphs, result.paragraphs)
    }

    @Test
    fun `readChapterFromFile returns empty paragraphs when no file exists`(): Unit = runTest {
        val location = BookLocation(
            bookId = "empty-book",
            format = BookFormat.NETWORK,
            sourceUrl = "https://bound.example",
        )
        val entry = ChapterEntry(index = 0, title = "空章", contentRef = "https://example.com/empty")

        val result = JsoupSourceReader.readChapterFromFile(entry, location, store)
        assertTrue(result.paragraphs.isEmpty())
    }

    @Test
    fun `取正文用的是 location 归属那本书源的 parser，而不是全局默认源`(): Unit = runTest {
        val bound = rule("https://bound.example")
        val boundParser = JsoupBookParser(bound, OkHttpClient())
        val manager = FakeBookSourceManager(
            parsersBySourceUrl = mapOf(bound.url to boundParser)
        )
        val reader = readerWith(manager)

        val parser = reader.resolveParser(BookLocation("net-book", BookFormat.NETWORK, bound.url))

        assertSame("必须是这本书自己那源的 parser 实例", boundParser, parser)
        val native = parser as? JsoupBookParser
        assertNotNull("原生行的 parser 必须仍是 JsoupBookParser（正文抓取要它的 rule）", native)
        assertEquals(bound.url, native?.rule?.url)
        // 假件的 parser 表里只有这一条源，且本方法只被问过一次：解正文没有第二条取源路径
        // （全局默认源那个兜底出口已随同步读面一并删除，不再是可替代的解析入口）
        assertEquals("取源只该问一次，问的就是 location 里的归属", listOf(bound.url), manager.parserForCalls)
    }

    @Test
    fun `归属 URL 在库里查不到时抛 BookSourceNotFoundException 并带上该 URL`(): Unit = runTest {
        val manager = FakeBookSourceManager()
        val reader = readerWith(manager)

        val failure = runCatching {
            reader.resolveParser(BookLocation("net-book", BookFormat.NETWORK, "https://gone.example"))
        }.exceptionOrNull()
        if (failure !is BookSourceNotFoundException) {
            fail("书源被删后必须抛 BookSourceNotFoundException，不能退回默认源去抓，实际=$failure")
        }

        val typed = failure as BookSourceNotFoundException
        assertEquals("https://gone.example", typed.sourceUrl)
        assertEquals(listOf("https://gone.example"), manager.parserForCalls)
    }

    /**
     * 网络书的归属为空白 → [BookSourceNotFoundException]（脏数据，可由用户处置）。
     *
     * 旧断言把这一支与 `loc_book` 一起锁成「编程错误」的 [IllegalStateException]，那是错的：
     * 空白归属是数据问题（历史行 / 导入链漏传 tag），代码改不动它，上层只会把「不该发生的分支」
     * 吞成一行日志，于是 ADR-0016 承诺的「书源已失效」提示在阅读正文这条主路径上永远出不来。
     */
    @Test
    fun `空白归属抛 BookSourceNotFoundException（脏数据要能被用户看见）`(): Unit = runTest {
        val manager = FakeBookSourceManager()
        val reader = readerWith(manager)

        val failure = runCatching {
            reader.resolveParser(BookLocation("net-book", BookFormat.NETWORK, ""))
        }.exceptionOrNull()
        if (failure !is BookSourceNotFoundException) {
            fail("网络书没有书源归属必须报成可展示的书源异常，不能报成编程错误，实际=$failure")
        }

        val typed = failure as BookSourceNotFoundException
        assertTrue(
            "空白归属时消息要说得清是「没有书源信息」，不能是冒号后空无一物的「书源已失效：」，" +
                "实际=${typed.message}",
            typed.message.orEmpty().contains("这本书没有书源信息"),
        )
        assertTrue(
            "空白 URL 指不出是哪本书，消息必须带上 bookId 这个现场锚点，实际=${typed.message}",
            typed.message.orEmpty().contains("net-book"),
        )
        assertEquals("没有归属时压根不该去查库", emptyList<String>(), manager.parserForCalls)
    }

    /** 本地书被路由到网络 reader → 仍是「编程错误」，不能被报成书源已失效（本地书没有源可重导） */
    @Test
    fun `本地 tag 仍按编程错误报且不报成书源已失效`(): Unit = runTest {
        val manager = FakeBookSourceManager()
        val reader = readerWith(manager)

        val failure = runCatching {
            reader.resolveParser(
                BookLocation("local-book", BookFormat.NETWORK, BookShelfEntity.LOCAL_TAG)
            )
        }.exceptionOrNull()
        assertTrue(
            "本地书走到网络 reader 是路由 bug，应报 IllegalStateException 而不是书源已失效，实际=$failure",
            failure is IllegalStateException && failure !is BookSourceNotFoundException,
        )
        // 消息要能指出现场：哪个本地 tag 进来了、是哪本书，否则一行日志定位不了接线错在哪
        val message = failure?.message.orEmpty()
        assertTrue("消息应带本地 tag 值，实际=$message", message.contains(BookShelfEntity.LOCAL_TAG))
        assertTrue("消息应带 bookId，实际=$message", message.contains("local-book"))
        assertEquals("排除本地 tag 的判断不该落到查库", emptyList<String>(), manager.parserForCalls)
    }

    /**
     * 「取到的 parser 不支持抓正文」→ 编程错误。
     *
     * 2d 起这条判据不在 `resolveParser` 里（它只负责「按书找到源」，返回类型放宽成 [BookParser]），
     * 而在 [JsoupSourceReader] 按类型分岔的那一步：既不是原生 `JsoupBookParser` 也不是脚本
     * `ScriptContentParser` 的 parser 没有抓正文的能力，只能报编程错误。因此本例改走 `readChapter`，
     * 顺带锁住「这个分支不会被误报成书源已失效」。
     */
    @Test
    fun `取到的 parser 不支持抓正文时报编程错误而非书源失效`(): Unit = runTest {
        // 假 parser：getParserFor 命中了，但它既不是 JsoupBookParser 也不是 ScriptContentParser
        val manager = FakeBookSourceManager(
            parsersBySourceUrl = mapOf("https://other.example" to RecordingBookParser("https://other.example"))
        )
        val reader = readerWith(manager)

        val failure = runCatching {
            reader.readChapter(
                ChapterEntry(index = 0, title = "第一章", contentRef = "https://other.example/c1.html"),
                BookLocation("net-book", BookFormat.NETWORK, "https://other.example"),
            )
        }.exceptionOrNull()

        assertTrue("实际=$failure", failure is IllegalStateException && failure !is BookSourceNotFoundException)
        assertTrue(failure?.message.orEmpty().contains("https://other.example"))
    }

    /* ---------- 脚本分支（2d） ---------- */

    /** 脚本正文假件：实现 public 接缝 [ScriptContentParser] */
    private class FakeScriptContentParser(
        private val text: String,
        private val error: Exception? = null,
    ) : BookParser, ScriptContentParser {
        val contentRefCalls = mutableListOf<String>()

        override suspend fun searchBook(content: String, page: Int): List<SearchBookEntity> = emptyList()
        override suspend fun getBookInfo(bookShelf: BookShelfEntity): BookShelfEntity = bookShelf
        override suspend fun getChapterList(bookShelf: BookShelfEntity): WebChapterEntity<BookShelfEntity> =
            WebChapterEntity(data = bookShelf, next = false)
        override suspend fun getKindBook(url: String, page: Int): List<SearchBookEntity> = emptyList()
        override suspend fun fetchLibraryData(): LibraryEntity = LibraryEntity()

        override suspend fun fetchChapterText(contentRef: String): String {
            contentRefCalls += contentRef
            error?.let { throw it }
            return text
        }
    }

    /**
     * 让 `getParserFor` 把 [parser] 交给 [SCRIPT_SOURCE_URL] 这个归属，其余照既有 fixture 搭法。
     *
     * 假件与脚本 URL 绑死即可：本组用例锁的是「读取器怎么对待脚本正文」，不是「脚本规则怎么求值」
     * （后者在 lib_book_source 侧，见 ScriptBookParserTest）。
     */
    private fun readerWithParser(parser: BookParser): JsoupSourceReader =
        readerWith(FakeBookSourceManager(parsersBySourceUrl = mapOf(SCRIPT_SOURCE_URL to parser)))

    private fun scriptLocation() = BookLocation("book-1", BookFormat.NETWORK, SCRIPT_SOURCE_URL)

    @Test
    fun `脚本源正文抓取后落盘并按行切段`(): Unit = runTest {
        val parser = FakeScriptContentParser("第一段\n第二段")
        val reader = readerWithParser(parser)

        val content = reader.readChapter(
            ChapterEntry(index = 0, title = "第一章", contentRef = "https://script.example.com/c1.html"),
            scriptLocation(),
        )

        assertEquals(listOf("第一段", "第二段"), content.paragraphs)
        assertTrue(
            "抓完必须落盘，否则每页翻读都在重联网",
            store.hasChapter(scriptLocation(), 0),
        )
        assertEquals("取文用的定位符就是 entry 的 contentRef", listOf("https://script.example.com/c1.html"), parser.contentRefCalls)
    }

    /**
     * 脚本源空正文**不落盘**：与原生路径逐字同口径。
     *
     * 写下去就得到一个「看着已缓存」的空章文件，`hasChapter` 会让重试与后续阅读把它当成功短路，
     * 用户侧是「显示已下载、翻开是空白页」。
     */
    @Test
    fun `脚本源空正文不落盘返回空段落`(): Unit = runTest {
        val reader = readerWithParser(FakeScriptContentParser("   "))
        val location = scriptLocation()

        val content = reader.readChapter(
            ChapterEntry(index = 0, title = "空章", contentRef = "https://script.example.com/c1.html"),
            location,
        )

        assertTrue(content.paragraphs.isEmpty())
        assertFalse("空正文不得写成章文件（否则重试被 hasChapter 短路）", store.hasChapter(location, 0))
    }

    /**
     * 脚本求值的类型化异常**原样上抛**，不裹成「章节内容解析失败」。
     *
     * 类型化异常自带面向用户的中文消息（「需沙箱执行器」「本项目不支持的能力」），裹掉就等于把
     * 真话换成一句查不出根因的套话；原生路径的包裹是选择器失配的口径，两者不能互相套用。
     *
     * 本例不断言「错误 URL 已记进诊断清单」：[com.ebook.common.manager.ErrorAnalyzeContentManager]
     * 是即发即忘的进程级协程写外部文件，没有可观测接缝，硬测只能轮询临时文件（随机器负载而抖）。
     */
    @Test
    fun `脚本源类型化异常原样上抛不被裹成解析失败`(): Unit = runTest {
        val typed = RuntimeException("该规则含可执行脚本段，需脚本沙箱执行器才能求值：text.1@@js:xxx")
        val reader = readerWithParser(FakeScriptContentParser("", typed))

        val failure = runCatching {
            reader.readChapter(
                ChapterEntry(index = 0, title = "x", contentRef = "https://script.example.com/c1.html"),
                scriptLocation(),
            )
        }.exceptionOrNull()

        // 为什么不断言「同一个实例」：readChapter 跨了 withContext，协程调试的栈回溯增强会给出
        // 一个副本（实测 IllegalStateException 的副本还把原异常挂成 cause），实例身份在 runTest 下
        // 本就测不准，而它也不是本例要锁的东西。要锁的是「没有再套一层」——重裹的产物消息是
        // 「章节内容解析失败: <url>」，与这里逐字断言的原消息直接冲突，一眼可辨。
        assertEquals("消息必须一字不改地透出去（那就是给用户的真话）", typed.message, failure?.message)
        assertFalse(
            "不得裹成原生路径的「章节内容解析失败」（实际=${failure?.message}）",
            failure?.message.orEmpty().contains("章节内容解析失败"),
        )
    }
}
