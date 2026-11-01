package com.ebook.common.manager

import com.ebook.common.analyze.local.BookFormat
import com.ebook.common.analyze.local.ChapterReader
import com.ebook.common.analyze.source.FakeBookSourceManager
import com.ebook.common.analyze.source.RecordingBookParser
import com.ebook.common.repository.BookRepository
import com.ebook.common.repository.BookShelfEvent
import com.ebook.common.repository.DirectTransactionRunner
import com.ebook.common.repository.FakeDaos
import com.ebook.common.store.BookStore
import com.ebook.common.store.ChapterContentCache
import com.ebook.db.entity.BookInfoEntity
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.LibraryEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.db.entity.WebChapterEntity
import com.ebook.source.analyze.BookParser
import com.ebook.source.analyze.BookSourceNotFoundException
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [BookShelfManager.addFromSearch] 的测试（ADR-0016 P3-a「加入书架按书绑源」）。
 *
 * 锁两件事：
 * 1. 详情与目录两次解析都落在**这本书 `tag` 对应的那个 parser** 上，全局默认源一次都没被问起；
 *    多书源共存后搜索列表里可能混着多个源的条目，用默认源规则去解别的站的 `noteUrl` 不会闪退，
 *    只会拉回一本不相干的书——错数据比崩溃难查得多。
 * 2. `tag` 查不到源时以 [BookSourceNotFoundException] 失败，且**不写书架**：
 *    半途入库会留下一本没有目录、正文也抓不回来的书。
 *
 * 替身：[FakeBookSourceManager] 按 URL 各给一个 [RecordingBookParser] 实例，于是「请求落到了谁身上」
 * 是可以直接断言的身份问题；DAO 用同模块的 [FakeDaos]，全程不碰网络。
 */
class BookShelfManagerTest {

    @get:Rule
    val booksRoot = TemporaryFolder()

    private lateinit var daos: FakeDaos

    @Before
    fun setUp() {
        daos = FakeDaos()
    }

    private class Fixture(manager: FakeBookSourceManager, daos: FakeDaos, booksRoot: File) {
        val bookShelfManager = BookShelfManager(manager, repository(manager, daos, booksRoot))
        val sourceManager = manager

        private fun repository(
            manager: FakeBookSourceManager,
            daos: FakeDaos,
            booksRoot: File,
        ): BookRepository = BookRepository(
            bookShelfDao = daos.shelf,
            bookInfoDao = daos.info,
            chapterListDao = daos.chapter,
            bookGroupDao = daos.group,
            downloadChapterDao = daos.download,
            // 书架写入不经 reader，本用例不需要任何 ChapterReader 装配
            chapterReaders = emptyMap<BookFormat, ChapterReader>(),
            // 换源才用得到书源管理器；这里给的就是被测的那个假件，两处共用同一份源清单
            sourceResolver = manager,
            bookStore = BookStore(booksRoot),
            contentCache = ChapterContentCache(capacity = 3),
            transactions = DirectTransactionRunner,
        )
    }

    private fun fixture(parsers: Map<String, BookParser>): Fixture =
        Fixture(FakeBookSourceManager(parsersBySourceUrl = parsers), daos, booksRoot.root)

    private fun searchBookOf(sourceUrl: String) = SearchBookEntity(
        noteUrl = "$sourceUrl/book/1.html",
        tag = sourceUrl,
        name = "书",
    )

    /** 造一个能解出 `book_info` 的 parser（成功路径要过守卫，见 [ShelfEntryFetcher]） */
    private fun parserCarryingBookInfo(sourceUrl: String): RecordingBookParser {
        val noteUrl = "$sourceUrl/book/1.html"
        val withInfo = BookShelfEntity(noteUrl = noteUrl, tag = sourceUrl).apply {
            bookInfo = BookInfoEntity(name = "书", author = "作者", noteUrl = noteUrl)
        }
        return RecordingBookParser(sourceUrl, bookInfoResult = withInfo)
    }

    @Test
    fun `加入书架用的是该书 tag 对应的 parser`(): Unit = runTest {
        val parserA = parserCarryingBookInfo(SOURCE_A)
        val parserB = parserCarryingBookInfo(SOURCE_B)
        val f = fixture(mapOf(SOURCE_A to parserA, SOURCE_B to parserB))

        val result = f.bookShelfManager.addFromSearch(searchBookOf(SOURCE_B))

        assertTrue("应成功入库，实际失败：${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("只该按这本书的归属取源", listOf(SOURCE_B), f.sourceManager.parserForCalls)
        assertEquals("传给 getBookInfo 的归属就是该书的书源", SOURCE_B, parserB.bookInfoCalls.single().tag)
        assertEquals(1, parserB.chapterListCalls.size)
        assertEquals(
            "另一个源的 parser 一次都不该被调用",
            0,
            parserA.bookInfoCalls.size + parserA.chapterListCalls.size,
        )
        assertEquals(SOURCE_B, result.getOrNull()?.tag)
        assertEquals(1, daos.shelf.getAllBooks().size)
    }

    @Test
    fun `tag 查不到源时以书源失效失败且不写书架`(): Unit = runTest {
        val parserA = RecordingBookParser(SOURCE_A)
        val f = fixture(mapOf(SOURCE_A to parserA))

        val result = f.bookShelfManager.addFromSearch(searchBookOf("https://gone.example"))

        val failure = result.exceptionOrNull()
        assertTrue("应为 BookSourceNotFoundException，实际=$failure", failure is BookSourceNotFoundException)
        assertEquals("https://gone.example", (failure as BookSourceNotFoundException).sourceUrl)
        assertEquals("没有归属的书不该进书架", 0, daos.shelf.getAllBooks().size)
        assertEquals("更不该退回默认源去解析", 0, parserA.bookInfoCalls.size + parserA.chapterListCalls.size)
    }

    @Test
    fun `加书架时源没解出书籍信息则失败且一行都不写`(): Unit = runTest {
        // 脏 parser：getBookInfo 原样返回（不填 bookInfo），getChapterList 也带回不带 bookInfo 的条目
        val dirtyParser = RecordingBookParser(SOURCE_A)
        val f = fixture(mapOf(SOURCE_A to dirtyParser))

        val result = f.bookShelfManager.addFromSearch(searchBookOf(SOURCE_A))

        assertTrue("脏返回要报成失败，而不是把半截条目写进书架", result.isFailure)
        assertTrue(
            "守卫与换源同一口径：IllegalStateException",
            result.exceptionOrNull() is IllegalStateException,
        )
        assertEquals("书架行一行都不该写", 0, daos.shelf.getAllBooks().size)
        assertEquals("book_info 一行都不该写", 0, daos.info.storedValues().size)
        assertEquals("章节一行都不该写", 0, daos.chapter.storedValues().size)
        assertEquals("关联键一行都不该写", 0, daos.group.storedValues().size)
    }

    @Test
    fun `加书架途中取消原样上抛而不是转成失败`(): Unit = runTest {
        val f = fixture(mapOf(SOURCE_A to CancellingBookParser()))

        val error = runCatching { f.bookShelfManager.addFromSearch(searchBookOf(SOURCE_A)) }
            .exceptionOrNull()

        assertTrue(
            "取消必须原样上抛，不能被 catch 转成失败（否则调用方会按失败弹 Toast）",
            error is CancellationException,
        )
        assertEquals("取消不是一次入库，书架不该有变化", 0, daos.shelf.getAllBooks().size)
    }

    // region 书架快照（书架标记列表的唯一主人）

    @Test
    fun `refresh 从仓库装载书架快照`(): Unit = runTest {
        val f = fixture(emptyMap())
        daos.shelf.insert(BookShelfEntity().apply { noteUrl = "u1"; finalDate = 2L })
        daos.shelf.insert(BookShelfEntity().apply { noteUrl = "u2"; finalDate = 1L })

        f.bookShelfManager.refresh()

        assertEquals(
            "快照按仓库返回的顺序整体装载",
            listOf("u1", "u2"),
            f.bookShelfManager.shelves.value.map { it.noteUrl },
        )
    }

    @Test
    fun `同一本书的 Added 事件应用两次不产生重复行`(): Unit = runTest {
        val f = fixture(emptyMap())
        val shelf = BookShelfEntity().apply { noteUrl = "u1" }

        f.bookShelfManager.applyEvent(BookShelfEvent.Added(shelf))
        f.bookShelfManager.applyEvent(BookShelfEvent.Added(shelf))

        assertEquals("书架事件可能重放，快照必须幂等", 1, f.bookShelfManager.shelves.value.size)
    }

    @Test
    fun `Removed 事件按 noteUrl 移除快照行`(): Unit = runTest {
        val f = fixture(emptyMap())
        f.bookShelfManager.applyEvent(BookShelfEvent.Added(BookShelfEntity().apply { noteUrl = "u1" }))
        f.bookShelfManager.applyEvent(BookShelfEvent.Added(BookShelfEntity().apply { noteUrl = "u2" }))

        f.bookShelfManager.applyEvent(BookShelfEvent.Removed(BookShelfEntity().apply { noteUrl = "u1" }))

        assertEquals(listOf("u2"), f.bookShelfManager.shelves.value.map { it.noteUrl })
    }

    @Test
    fun `markShelfStatus 读 manager 自己的快照`(): Unit = runTest {
        val f = fixture(emptyMap())
        daos.shelf.insert(BookShelfEntity().apply { noteUrl = "u1" })
        f.bookShelfManager.refresh()
        val results = listOf(SearchBookEntity(noteUrl = "u1"), SearchBookEntity(noteUrl = "u2"))

        f.bookShelfManager.markShelfStatus(results)

        assertTrue("在架的那本应被标记", results[0].add)
        assertFalse("不在架的那本不该被标记", results[1].add)
    }

    @Test
    fun `与书架无关的事件不改变快照`(): Unit = runTest {
        val f = fixture(emptyMap())
        f.bookShelfManager.applyEvent(BookShelfEvent.Added(BookShelfEntity().apply { noteUrl = "u1" }))

        f.bookShelfManager.applyEvent(BookShelfEvent.ProgressUpdated(BookShelfEntity().apply { noteUrl = "u9" }))

        assertEquals(listOf("u1"), f.bookShelfManager.shelves.value.map { it.noteUrl })
    }

    // endregion

    /**
     * 只服务「取消上抛」用例的 parser：`getBookInfo` 抛 [CancellationException]，模拟解析途中调用方取消。
     *
     * 其余成员一调就抛——假件把未实现成员静默返回空值，会让「其实没走到那条路径」的断言假绿。
     */
    private class CancellingBookParser : BookParser {
        override suspend fun getBookInfo(bookShelf: BookShelfEntity): BookShelfEntity =
            throw CancellationException("调用方已取消")

        override suspend fun getChapterList(bookShelf: BookShelfEntity): WebChapterEntity<BookShelfEntity> =
            throw UnsupportedOperationException("详情都没拉到，不该问目录")

        private fun unsupported(who: String): Nothing =
            throw UnsupportedOperationException("CancellingBookParser 未实现 $who")

        override suspend fun searchBook(content: String, page: Int): List<SearchBookEntity> =
            unsupported("searchBook")

        override suspend fun getKindBook(url: String, page: Int): List<SearchBookEntity> =
            unsupported("getKindBook")

        override suspend fun fetchLibraryData(): LibraryEntity = unsupported("fetchLibraryData")
    }

    private companion object {
        const val SOURCE_A = "https://a.example"
        const val SOURCE_B = "https://b.example"
    }
}
