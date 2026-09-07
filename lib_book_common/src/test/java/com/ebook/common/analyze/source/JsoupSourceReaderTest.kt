package com.ebook.common.analyze.source

import com.ebook.common.analyze.local.BookFormat
import com.ebook.common.analyze.local.BookLocation
import com.ebook.common.analyze.local.ChapterEntry
import com.ebook.common.store.BookStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JsoupSourceReaderTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    private lateinit var store: BookStore

    @Before
    fun setUp() {
        store = BookStore(tmpDir.root)
    }

    @Test
    fun `readChapterFromFile returns content when chapter file exists`() = runTest {
        val location = BookLocation(bookId = "test-book", format = BookFormat.NETWORK)
        val paragraphs = listOf("段落一", "段落二", "段落三")
        store.writeChapter(location, 0, paragraphs)

        val entry = ChapterEntry(index = 0, title = "第一章", contentRef = "https://example.com/ch1")
        val result = JsoupSourceReader.readChapterFromFile(entry, location, store)

        assertEquals("第一章", result.title)
        assertEquals(paragraphs, result.paragraphs)
    }

    @Test
    fun `readChapterFromFile returns empty paragraphs when no file exists`() = runTest {
        val location = BookLocation(bookId = "empty-book", format = BookFormat.NETWORK)
        val entry = ChapterEntry(index = 0, title = "空章", contentRef = "https://example.com/empty")

        val result = JsoupSourceReader.readChapterFromFile(entry, location, store)
        assertTrue(result.paragraphs.isEmpty())
    }
}
