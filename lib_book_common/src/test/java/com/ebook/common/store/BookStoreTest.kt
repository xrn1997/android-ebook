package com.ebook.common.store

import com.ebook.common.analyze.local.BookLocation
import com.ebook.common.analyze.local.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [BookStore] 单测（spec §4）。用 [TemporaryFolder] 冒充 `filesDir/books`，
 * 因此不需要 Robolectric 或设备。
 *
 * 重点锁三件容易做错的事：章文件往返**无损**（不掺入表现层字符）、`.tmp` 改名是唯一
 * 提交点、对账只删"DB 里已不存在的书"而绝不误删在册书。
 */
class BookStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var store: BookStore

    private val bookId = "3f9a1c7d5e6f7a8b9c0d1e2f3a4b5c6d"
    private val location = BookLocation(bookId, BookFormat.TXT)

    @Before
    fun setUp() {
        root = tmp.newFolder("books")
        store = BookStore(root)
    }

    @Test
    fun `chapterRef 是自足的相对路径`() {
        val ref = store.chapterRef(bookId, 42)
        assertEquals("books/$bookId/c00042.txt", ref)
    }

    @Test
    fun `章文件往返无损且不掺入渲染层缩进`() {
        val paragraphs = listOf("第一段 保留空格", "第二段")
        store.writeChapter(location, 0, paragraphs)

        assertEquals(paragraphs, store.readParagraphs(location, 0))
        // 存储层不清洗也不加工：文件里不得出现渲染层缩进
        val raw = File(root, "$bookId/c00000.txt").readText(Charsets.UTF_8)
        assertFalse("章文件不得含全角缩进", raw.contains("\u3000\u3000"))
    }

    @Test
    fun `段落间以单个换行符分隔落盘`() {
        store.writeChapter(location, 7, listOf("甲", "乙"))
        assertEquals("甲\n乙", File(root, "$bookId/c00007.txt").readText(Charsets.UTF_8))
    }

    @Test
    fun `章文件缺失时 readParagraphs 返回空且 hasChapter 为假`() {
        assertEquals(emptyList<String>(), store.readParagraphs(location, 99))
        assertFalse(store.hasChapter(location, 99))
    }

    @Test
    fun `commitImport 把 tmp 暂存目录改名为正式目录`() {
        val staging = store.beginImport(bookId)
        assertTrue(staging.name.endsWith(".tmp"))
        store.writeChapterRaw(staging, 0, "内容")

        store.commitImport(staging, bookId)

        assertFalse(staging.exists())
        assertTrue(File(root, bookId).exists())
    }

    @Test
    fun `abortImport 删除暂存目录`() {
        val staging = store.beginImport(bookId)
        store.writeChapterRaw(staging, 0, "半本")

        store.abortImport(staging)

        assertFalse(staging.exists())
    }

    @Test
    fun `deleteChapter 只失效该章不牵连其余章`() {
        store.writeChapter(location, 0, listOf("甲"))
        store.writeChapter(location, 1, listOf("乙"))

        store.deleteChapter(location, 0)

        assertFalse("被刷新的那章缓存必须失效", store.hasChapter(location, 0))
        assertTrue("强刷一章不得牵连其余已缓存章节", store.hasChapter(location, 1))
    }

    @Test
    fun `reconcile 只回收不在册目录与半成品残留不误删在册书`() {
        File(root, "$bookId/c00000.txt").apply { parentFile?.mkdirs(); writeText("在册") }
        File(root, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/c00000.txt").apply { parentFile?.mkdirs(); writeText("孤儿") }
        File(root, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.txt").writeText("半成品")

        store.reconcile(setOf(bookId))

        assertTrue(File(root, bookId).exists())
        assertFalse(File(root, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").exists())
        assertFalse(File(root, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.txt").exists())
    }

    @Test
    fun `deleteBook 删除整本书目录`() {
        store.writeChapter(location, 0, listOf("甲"))
        store.writeChapter(location, 1, listOf("乙"))

        store.deleteBook(location)

        assertFalse(File(root, bookId).exists())
    }
}
