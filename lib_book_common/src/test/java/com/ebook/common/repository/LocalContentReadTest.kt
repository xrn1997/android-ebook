package com.ebook.common.repository

import com.ebook.common.analyze.local.BookFormat
import com.ebook.common.analyze.local.BookLocation
import com.ebook.common.analyze.local.ChapterContent
import com.ebook.common.analyze.local.ChapterEntry
import com.ebook.common.analyze.local.ChapterReader
import com.ebook.common.store.BookStore
import com.ebook.common.store.ChapterContentCache
import com.ebook.db.entity.BookShelfEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * [BookRepository.loadChapter] 的单元测试（本地书路径）。
 *
 * 锁三件事：
 * 1. 本地书走 reader 不走数据库：返回的 `ChapterContent` 来自 reader
 * 2. 同一章翻多页只读盘一次（内存缓存兜住了第二次起）
 * 3. 未知格式回落到 TXT（resolveFormat 的 getOrDefault 行为）
 */
class LocalContentReadTest {

    private lateinit var daos: FakeDaos
    private lateinit var reader: RecordingReader
    private lateinit var repository: BookRepository

    @Before
    fun setUp() {
        daos = FakeDaos()
        reader = RecordingReader(ChapterContent("章标题", listOf("段落一", "段落二")))
        val cache = ChapterContentCache(capacity = 3)
        val store = BookStore(File(System.getProperty("java.io.tmpdir"), "test-books"))
        repository = BookRepository(
            bookShelfDao = daos.shelf,
            bookInfoDao = daos.info,
            chapterListDao = daos.chapter,
            bookGroupDao = daos.group,
            chapterReaders = mapOf(BookFormat.TXT to reader),
            bookStore = store,
            contentCache = cache,
            transactions = DirectTransactionRunner,
        )
    }

    @Test
    fun `loadsLocalChapterThroughReaderNotDatabase`() = runTest {
        val shelf = BookShelfEntity(
            noteUrl = "local-book-id",
            tag = BookShelfEntity.LOCAL_TAG,
            bookFormat = "TXT",
        )

        val result = repository.loadChapter(shelf, index = 0, title = "第一章")

        assertEquals(listOf("段落一", "段落二"), result?.paragraphs)
        assertEquals("章标题", result?.title)
        assertEquals(1, reader.callCount)
    }

    @Test
    fun `secondPageTurnHitsCacheAndDoesNotCallReaderAgain`() = runTest {
        val shelf = BookShelfEntity(
            noteUrl = "local-book-id",
            tag = BookShelfEntity.LOCAL_TAG,
            bookFormat = "TXT",
        )

        // 模拟翻页：同一章连续读 3 次（翻页时每次取正文）
        repeat(3) { repository.loadChapter(shelf, index = 0, title = "第一章") }

        // reader 只该被调一次——第二次起命中内存缓存
        assertEquals(1, reader.callCount)
    }

    @Test
    fun `unknownFormatFallsBackToTxtForLocalBooks`() = runTest {
        val shelf = BookShelfEntity(
            noteUrl = "some-book",
            tag = BookShelfEntity.LOCAL_TAG,
            bookFormat = "MOBI",
        )

        val result = repository.loadChapter(shelf, index = 0, title = "第一章")

        // MOBI is not a valid BookFormat, so resolveFormat falls back to TXT
        assertEquals(1, reader.callCount)
        assertEquals(listOf("段落一", "段落二"), result?.paragraphs)
    }

    @Test
    fun `loadChapter normalizes raw chapter text before caching`() = runTest {
        val rawReader = RecordingReader(
            ChapterContent("第一章", listOf("　　正文  双空格  ", "　　第二段"))
        )
        val repository = repositoryWith(rawReader)
        val shelf = BookShelfEntity(
            noteUrl = "local-book-id",
            tag = BookShelfEntity.LOCAL_TAG,
            bookFormat = "TXT",
        )

        val result = repository.loadChapter(shelf, index = 0, title = "第一章")

        // 章文件里是原文；出 loadChapter 必须是清洗后的段落数据（渲染与段评锚点的输入）
        assertEquals(listOf("正文 双空格", "第二段"), result?.paragraphs)
        // 缩进由渲染层补，且只补一份（原文自带的已被吸收）
        assertEquals("　　正文 双空格\n　　第二段", result?.displayText)
    }

    @Test
    fun `blank-only chapter is treated as missing content`() = runTest {
        val blankReader = RecordingReader(ChapterContent("第一章", listOf("   ", "　　")))
        val repository = repositoryWith(blankReader)
        val shelf = BookShelfEntity(
            noteUrl = "local-book-id",
            tag = BookShelfEntity.LOCAL_TAG,
            bookFormat = "TXT",
        )

        assertNull(repository.loadChapter(shelf, index = 0, title = "第一章"))
        // 空判定在规范化之后：整章空白清洗完就是空集，按内容缺失处理
        assertEquals(1, blankReader.callCount)
    }

    /** 换一个 reader 实例重建仓库：规范化用例要喂原文段落，公共 setUp 的 reader 返回已清洗数据 */
    private fun repositoryWith(chapterReader: ChapterReader): BookRepository = BookRepository(
        bookShelfDao = daos.shelf,
        bookInfoDao = daos.info,
        chapterListDao = daos.chapter,
        bookGroupDao = daos.group,
        chapterReaders = mapOf(BookFormat.TXT to chapterReader),
        bookStore = BookStore(File(System.getProperty("java.io.tmpdir"), "test-books")),
        contentCache = ChapterContentCache(capacity = 3),
        transactions = DirectTransactionRunner,
    )

    /**
     * 记录调用的假 reader：返回固定 [ChapterContent]，统计 `readChapter` 被调用次数。
     */
    private class RecordingReader(private val content: ChapterContent) : ChapterReader {
        var callCount = 0
            private set

        override suspend fun readChapter(entry: ChapterEntry, location: BookLocation): ChapterContent {
            callCount++
            return content
        }
    }
}
