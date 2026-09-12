package com.ebook.book.mvvm.viewmodel

import androidx.test.core.app.ApplicationProvider
import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.SourceFormat
import com.ebook.api.entity.SourceDefinition
import com.ebook.common.analyze.local.BookFormat
import com.ebook.common.analyze.local.ChapterReader
import com.ebook.common.analyze.source.BookSourceItem
import com.ebook.common.analyze.source.BookSourceManager
import com.ebook.common.analyze.source.SourceExploreEntry
import com.ebook.common.repository.BookRepository
import com.ebook.common.store.BookStore
import com.ebook.common.store.ChapterContentCache
import com.ebook.common.store.WriteTransactionRunner
import com.ebook.db.dao.BookGroupDao
import com.ebook.db.dao.BookInfoDao
import com.ebook.db.dao.BookShelfDao
import com.ebook.db.dao.ChapterListDao
import com.ebook.db.dao.DownloadChapterDao
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.LibraryEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.db.entity.WebChapterEntity
import com.ebook.source.analyze.BookParser
import com.ebook.source.analyze.BookSourceNotFoundException
import com.xrn1997.common.BaseApplication
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [BookDetailViewModel] 的「按书绑源」测试（ADR-0016 P3-a）。
 *
 * 锁三条：
 * 1. 详情页的两次解析（详情 + 目录）都按**这本书的 `tag`** 取 parser，全局默认源一次都没被问起；
 * 2. `tag` 查不到源时页面必须落到 [BookDetailUiState.loadError] 且 `loading` 收掉——
 *    「静默返回成功」会让覆盖层永远转下去，用户既等不到结果也看不到原因；
 * 3. 详情拿到了、目录取不到源，同样是错误态（不能停在「半本已加载」的样子）。
 *
 * 用 Robolectric 的理由：书源失效那条分支要取 string 资源（`BaseApplication.context`），
 * 纯 JVM 下它是抛「Stub!」的桩实现；调度器仍全部挂在 [mainDispatcher] 上，`advanceUntilIdle` 即可驱动。
 * DAO 用「白名单空库」替身（见 [emptyDaoStub]）：本组用例只关心详情页选哪个 parser，
 * 仓库层取数由 lib_book_common 自己的用例锁，不在这里重做。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookDetailViewModelSourceTest {

    private companion object {
        const val SOURCE_A = "https://a.example"
        const val SOURCE_B = "https://b.example"
        const val NOTE_URL = "$SOURCE_B/book/1.html"
    }

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        // 失败提示走 string 资源，资源解析要真 Context（与 AndroidUserSessionManagerTest 同一套跑法）
        BaseApplication.context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 只喂一个空书架：详情页流程里仓库只被问 `getAllBooks` */
    private fun repository(manager: BookSourceManager): BookRepository = BookRepository(
        bookShelfDao = emptyDaoStub(BookShelfDao::class.java, "getAllBooks"),
        bookInfoDao = emptyDaoStub(BookInfoDao::class.java),
        chapterListDao = emptyDaoStub(ChapterListDao::class.java),
        bookGroupDao = emptyDaoStub(BookGroupDao::class.java),
        // 详情页不换源，仓库那张下载队列表在本用例里一次都不会被问（白名单为空即是断言）
        downloadChapterDao = emptyDaoStub(DownloadChapterDao::class.java),
        chapterReaders = emptyMap<BookFormat, ChapterReader>(),
        // 换源才用得上书源管理器；详情页不换源，但仓库构造要它，与 VM 共用同一份假清单
        bookSourceManager = manager,
        bookStore = BookStore(File(System.getProperty("java.io.tmpdir"), "book-detail-test-books")),
        contentCache = ChapterContentCache(capacity = 3),
        transactions = DirectRunner,
    )

    private fun viewModelWith(manager: FakeSourceManager): BookDetailViewModel =
        BookDetailViewModel(repository(manager), manager)

    /**
     * 轮询等到 [cond] 成立：交替推进测试调度器与真实时间。
     *
     * 为什么不能只用 `advanceUntilIdle`：详情页流程里的 `BookRepository.getAllBooks()` 走
     * `withContext(Dispatchers.IO)`，那跑在**真实 IO 线程**上、不受虚拟时钟控制，
     * 只推进调度器就会与之竞态（本组用例最初就是这样：取源记录还是空的）。
     * 等「可观察状态」而不是固定时长，超时才判失败。（同一写法见 module_me 的 SettingViewModelTest。）
     */
    private suspend fun TestScope.awaitUntil(message: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!cond()) {
            advanceUntilIdle()
            if (cond()) break
            assertTrue("$message（5s 内未达成）", System.currentTimeMillis() < deadline)
            withContext(Dispatchers.IO) { Thread.sleep(10) }
        }
        advanceUntilIdle()
    }

    @Test
    fun `详情与目录都按该书 tag 取 parser`(): Unit = runTest(mainDispatcher) {
        val parserA = FakeParser(SOURCE_A)
        val parserB = FakeParser(SOURCE_B)
        // 清单里同时挂着 A、B 两个源：解析一旦串到别的源，parserA 的计数会当场露馅
        val manager = FakeSourceManager(parsers = mapOf(SOURCE_A to parserA, SOURCE_B to parserB))
        val viewModel = viewModelWith(manager)
        viewModel.initFromSearch(SearchBookEntity(noteUrl = NOTE_URL, tag = SOURCE_B))

        viewModel.getBookShelfInfo()
        awaitUntil("详情与目录两次取源都已发生") { manager.parserForCalls.size == 2 }

        assertEquals("两次解析问的都是这本书的归属", listOf(SOURCE_B, SOURCE_B), manager.parserForCalls)
        assertEquals(1, parserB.bookInfoCalls)
        assertEquals(1, parserB.chapterListCalls)
        assertEquals(
            "另一个源的 parser 一次都不该被调用",
            0,
            parserA.bookInfoCalls + parserA.chapterListCalls,
        )

        val state = viewModel.detailState.value
        assertFalse("加载已结束，覆盖层不该还开着", state.loading)
        assertFalse("成功路径不该留错误态", state.loadError)
        assertNotNull(state.bookShelf)
        assertEquals(SOURCE_B, state.bookShelf?.tag)
    }

    @Test
    fun `书源已失效时进入错误态而不是永久 loading`(): Unit = runTest(mainDispatcher) {
        // 库里没有 SOURCE_B 这一行（用户删了那个书源），A 源的 parser 也不该被拿来顶
        val parserA = FakeParser(SOURCE_A)
        val manager = FakeSourceManager(parsers = mapOf(SOURCE_A to parserA))
        val viewModel = viewModelWith(manager)
        viewModel.initFromSearch(SearchBookEntity(noteUrl = NOTE_URL, tag = SOURCE_B))

        viewModel.getBookShelfInfo()
        awaitUntil("已按该书归属取过一次源") { manager.parserForCalls.isNotEmpty() }

        val state = viewModel.detailState.value
        assertTrue("归属查不到必须给出错误态", state.loadError)
        assertFalse("覆盖层必须收掉，否则页面永远在转圈", state.loading)
        assertEquals("只按该书归属取源", listOf(SOURCE_B), manager.parserForCalls)
        assertEquals("没有归属的书不该被默认源解析", 0, parserA.bookInfoCalls)
    }

    @Test
    fun `目录取不到源时同样落到错误态`(): Unit = runTest(mainDispatcher) {
        val parserB = FakeParser(SOURCE_B).apply { failChapterList = true }
        val manager = FakeSourceManager(parsers = mapOf(SOURCE_B to parserB))
        val viewModel = viewModelWith(manager)
        viewModel.initFromSearch(SearchBookEntity(noteUrl = NOTE_URL, tag = SOURCE_B))

        viewModel.getBookShelfInfo()
        awaitUntil("详情与目录两次取源都已发生") { manager.parserForCalls.size == 2 }

        val state = viewModel.detailState.value
        assertTrue("详情拿到了但目录取不到源，仍是错误态", state.loadError)
        assertFalse("覆盖层同样要收掉", state.loading)
        assertEquals(listOf(SOURCE_B, SOURCE_B), manager.parserForCalls)
        assertEquals(1, parserB.chapterListCalls)
    }

    /**
     * 「白名单空库」DAO 替身：只允许 [allowed] 里的方法被调用（一律返回空集），
     * 其余方法被调用即抛——静默返回 null 会让「其实走错了路径」的断言假绿。
     *
     * 用 [Proxy] 而不是手写 Fake：Room DAO 接口方法多，本用例只用到一条，
     * 手写会把「哪些方法真的被测到」这件事糊掉。suspend 方法在字节码里返回 Object，
     * 故按方法名而非返回类型分派。
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> emptyDaoStub(clazz: Class<T>, vararg allowed: String): T =
        Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz)) { _, method, _ ->
            when {
                method.name in allowed -> emptyList<BookShelfEntity>()
                method.name == "equals" -> false
                method.name == "hashCode" -> System.identityHashCode(clazz)
                method.name == "toString" -> "EmptyDaoStub<${clazz.simpleName}>"
                else -> throw UnsupportedOperationException(
                    "${clazz.simpleName}.${method.name} 不该被本用例调用（确需调用请加进白名单）"
                )
            }
        } as T

    private object DirectRunner : WriteTransactionRunner {
        override suspend fun <R> run(block: suspend () -> R): R = block()
    }

    /**
     * 按 URL 给 parser 的最小假件，只实现详情页用得到的成员。
     *
     * `getParserFor` **照抄真实现的两条短路**（空白与 `loc_book` 不查库直接 null），
     * 否则「本地书不会被报成书源失效」这类断言就成了假命题。
     */
    private class FakeSourceManager(
        parsers: Map<String, BookParser>,
    ) : BookSourceManager {
        private val map = LinkedHashMap(parsers)
        val parserForCalls = mutableListOf<String>()

        override suspend fun getParserFor(sourceUrl: String): BookParser? {
            parserForCalls += sourceUrl
            if (sourceUrl.isBlank() || sourceUrl == BookShelfEntity.LOCAL_TAG) return null
            return map[sourceUrl]
        }

        /** 本假件只服务详情页取源；聚合搜索的编排由 Manager 与 SearchViewModel 的用例锁 */
        override fun searchAcross(keyword: String, page: Int, skipSourceUrls: Set<String>) =
            unsupported("searchAcross")

        private fun unsupported(who: String): Nothing =
            throw UnsupportedOperationException("FakeSourceManager 未实现 $who")

        override suspend fun getAllSources(): List<BookSourceRule> = unsupported("getAllSources")
        override suspend fun getEnabledSources(): List<BookSourceRule> = unsupported("getEnabledSources")
        override suspend fun getSourceByUrl(url: String): BookSourceRule? = unsupported("getSourceByUrl")
        override suspend fun addSource(rule: BookSourceRule): Result<Unit> = unsupported("addSource")
        override suspend fun addScriptSource(rawJson: String): Result<Unit> = unsupported("addScriptSource")
        override suspend fun getFormatByUrl(url: String): SourceFormat? = unsupported("getFormatByUrl")
        override suspend fun getExploreEntries(sourceUrl: String): List<SourceExploreEntry> =
            unsupported("getExploreEntries")
        override suspend fun removeSource(url: String): Result<Unit> = unsupported("removeSource")
        override suspend fun setEnabled(url: String, enabled: Boolean) = unsupported("setEnabled")
        override suspend fun setDefaultSource(url: String) = unsupported("setDefaultSource")
        override fun observeSources(): Flow<List<BookSourceItem>> = unsupported("observeSources")
        override fun observeDefaultSource(): Flow<SourceDefinition?> = unsupported("observeDefaultSource")
        override fun importFromJson(jsonStr: String): BookSourceRule? = unsupported("importFromJson")
        override fun exportToJson(rule: BookSourceRule): String = unsupported("exportToJson")
    }

    /** 身份可辨的 parser 替身：`ownedSourceUrl` 就是「我是哪个源的 parser」 */
    private class FakeParser(val ownedSourceUrl: String) : BookParser {
        var bookInfoCalls = 0
        var chapterListCalls = 0

        /** 让目录解析抛「书源已失效」，用来锁「详情成功、目录失败」这条中间态 */
        var failChapterList: Boolean = false

        override suspend fun getBookInfo(bookShelf: BookShelfEntity): BookShelfEntity {
            bookInfoCalls++
            return bookShelf
        }

        override suspend fun getChapterList(bookShelf: BookShelfEntity): WebChapterEntity<BookShelfEntity> {
            chapterListCalls++
            if (failChapterList) throw BookSourceNotFoundException(ownedSourceUrl)
            return WebChapterEntity(data = bookShelf, next = false)
        }

        private fun unsupported(who: String): Nothing =
            throw UnsupportedOperationException("FakeParser 未实现 $who")

        override suspend fun searchBook(content: String, page: Int): List<SearchBookEntity> =
            unsupported("searchBook")

        override suspend fun getKindBook(url: String, page: Int): List<SearchBookEntity> =
            unsupported("getKindBook")

        override suspend fun fetchLibraryData(): LibraryEntity = unsupported("fetchLibraryData")
    }
}
