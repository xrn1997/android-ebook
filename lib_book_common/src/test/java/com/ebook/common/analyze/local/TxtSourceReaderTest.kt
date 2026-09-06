package com.ebook.common.analyze.local

import com.ebook.common.store.BookStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [TxtSourceReader] 的往返测试：源文件 → 切分落盘 → 再读回。
 *
 * 这里锁的是**整条链**而不只是切分：章文件必须能被 `readChapter` 原样读回，
 * 且 `content_ref` 与 `BookStore.chapterRef` 严格一致（不一致会导致索引指向不存在的文件，
 * 症状是"导入成功但翻开是空白页"）。
 */
class TxtSourceReaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var booksRoot: File
    private lateinit var store: BookStore
    private lateinit var reader: TxtSourceReader

    private val bookId = "a".repeat(32)
    private val location = BookLocation(bookId, BookFormat.TXT)

    @Before
    fun setUp() {
        booksRoot = tmp.newFolder("books")
        store = BookStore(booksRoot)
        reader = TxtSourceReader(store)
    }

    @Test
    fun buildChaptersWritesFilesAndEmitsMatchingEntries() = runTest {
        val source = txt("第一章 起\n正文甲\n\n第二章 承\n正文乙")

        val entries = reader.buildChapters(BookSourceFile(source, "UTF-8"), sink(bookId)).toList()

        assertEquals(listOf(0, 1), entries.map { it.index })
        assertEquals(listOf("第一章 起", "第二章 承"), entries.map { it.title })
        assertEquals(
            listOf(store.chapterRef(bookId, 0), store.chapterRef(bookId, 1)),
            entries.map { it.contentRef }
        )
        assertEquals(listOf("正文甲"), store.readParagraphs(location, 0))
    }

    @Test
    fun readChapterReturnsParagraphsAndDisplayText() = runTest {
        val source = txt("第一章 起\n正文甲 保留空格\n正文乙")
        val entry = reader.buildChapters(BookSourceFile(source, "UTF-8"), sink(bookId)).toList().single()

        val content = reader.readChapter(entry, location)

        assertEquals("第一章 起", content.title)
        assertEquals(listOf("正文甲 保留空格", "正文乙"), content.paragraphs)
        assertEquals("\u3000\u3000正文甲 保留空格\n\u3000\u3000正文乙", content.displayText)
        assertFalse("段落数据里不该有缩进", content.paragraphs.any { it.startsWith("\u3000\u3000") })
    }

    @Test
    fun readChapterOnMissingFileYieldsEmptyParagraphs() = runTest {
        val content = reader.readChapter(ChapterEntry(5, "无", store.chapterRef(bookId, 5)), location)
        assertEquals(emptyList<String>(), content.paragraphs)
    }

    @Test
    fun readMetadataUsesFileNameParsing() {
        val source = File(booksRoot, "《星辰变》作者：我吃西红柿.txt").apply { writeText("第一章 x\n正文") }

        val meta = TxtSourceReader.readMetadataOf(source)

        assertEquals("星辰变", meta.title)
        assertEquals("我吃西红柿", meta.author)
    }

    private fun sink(bookId: String) = object : ChapterSink {
        override suspend fun write(index: Int, paragraphs: List<String>): String {
            store.writeChapter(BookLocation(bookId, BookFormat.TXT), index, paragraphs)
            return store.chapterRef(bookId, index)
        }
    }

    private fun txt(content: String): File =
        File(tmp.root, "in.txt").apply { writeText(content, Charsets.UTF_8) }
}
