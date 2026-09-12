package com.ebook.common.manager

import com.ebook.common.analyze.local.BookFormat
import com.ebook.common.analyze.local.ChapterReader
import com.ebook.common.analyze.source.FakeBookSourceManager
import com.ebook.common.analyze.source.RecordingBookParser
import com.ebook.common.repository.BookRepository
import com.ebook.common.repository.DirectTransactionRunner
import com.ebook.common.repository.FakeDaos
import com.ebook.common.store.BookStore
import com.ebook.common.store.ChapterContentCache
import com.ebook.db.entity.SearchBookEntity
import com.ebook.source.analyze.BookParser
import com.ebook.source.analyze.BookSourceNotFoundException
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
            bookSourceManager = manager,
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

    @Test
    fun `加入书架用的是该书 tag 对应的 parser`(): Unit = runTest {
        val parserA = RecordingBookParser(SOURCE_A)
        val parserB = RecordingBookParser(SOURCE_B)
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

    private companion object {
        const val SOURCE_A = "https://a.example"
        const val SOURCE_B = "https://b.example"
    }
}
