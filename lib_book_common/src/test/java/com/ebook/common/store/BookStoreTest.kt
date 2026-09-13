package com.ebook.common.store

import com.ebook.common.analyze.local.BookLocation
import com.ebook.common.analyze.local.BookFormat
import com.ebook.db.entity.BookShelfEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    private val location = BookLocation(bookId, BookFormat.TXT, BookShelfEntity.LOCAL_TAG)

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
    fun `reconcile 不误删在册网络书的章缓存`() {
        // 网络书的 bookId 就是 noteUrl，含路径分隔符，落盘后是嵌套目录（真机形态
        // `books/https:/www.bqquge.com/30/c00000.txt`），而 reconcile 只拿 booksRoot 的
        // **第一层目录名**与在册 bookId 全串比对——首段（`www.bqquge.com`）永远不等于
        // noteUrl，于是整棵被当无主目录删掉，每次启动都把网络书的章缓存清空。
        // 这里刻意用不含冒号的 noteUrl：Windows 上 `https:` 不是合法目录名（mkdirs 静默
        // 失败、writeChapter 抛 FileNotFoundException），红的是主机差异而不是本缺陷。
        // 分隔符才是机制所在，真机形态由设备侧回路验。
        val noteUrl = "www.bqquge.com/30/"
        val network = BookLocation(noteUrl, BookFormat.NETWORK, noteUrl)
        store.writeChapter(network, 0, listOf("甲"))
        assertTrue("前置：网络书章文件已落盘", store.hasChapter(network, 0))

        store.reconcile(setOf(noteUrl))

        assertTrue("在册网络书的章缓存不得被对账回收", store.hasChapter(network, 0))
    }

    @Test
    fun `含分隔符的 bookId 派生出单段目录名且不同 URL 不撞目录`() {
        val noteUrl = "www.bqquge.com/30/"

        val ref = store.chapterRef(noteUrl, 3)

        assertEquals("content_ref 必须仍是 books/<单段目录名>/<章文件> 三段", 3, ref.split('/').size)
        assertTrue(ref.startsWith("${BookStore.DIR_NAME}/"))
        assertTrue(ref.endsWith("/c00003.txt"))
        assertFalse("URL 不得原样进目录名", ref.contains("bqquge"))
        // 换成「替换非法字符」的实现会让这两条撞成同一个目录 = 串书，md5 不会
        assertNotEquals(
            store.chapterRef("www.x.com/a/b/", 0),
            store.chapterRef("www.x.com/a_b/", 0),
        )
    }

    @Test
    fun `storageUsage 每本网络书各算一册`() {
        store.writeChapter(BookLocation("www.a.com/1/", BookFormat.NETWORK, "www.a.com/1/"), 0, listOf("甲"))
        store.writeChapter(BookLocation("www.a.com/2/", BookFormat.NETWORK, "www.a.com/2/"), 0, listOf("乙"))

        assertEquals("旧布局下两本网络书共用一个顶层目录，会被数成 1 册", 2, store.storageUsage().bookCount)
    }

    @Test
    fun `deleteBook 不牵连 URL 路径互为前缀的另一本网络书`() {
        val short = BookLocation("www.a.com/book/", BookFormat.NETWORK, "www.a.com/book/")
        val nested = BookLocation("www.a.com/book/30/", BookFormat.NETWORK, "www.a.com/book/30/")
        store.writeChapter(short, 0, listOf("甲"))
        store.writeChapter(nested, 0, listOf("乙"))

        store.deleteBook(nested)

        assertFalse(store.hasChapter(nested, 0))
        assertTrue("删一本书不得连带删掉 URL 前缀相同的另一本", store.hasChapter(short, 0))
    }

    @Test
    fun `deleteBook 删除整本书目录`() {
        store.writeChapter(location, 0, listOf("甲"))
        store.writeChapter(location, 1, listOf("乙"))

        store.deleteBook(location)

        assertFalse(File(root, bookId).exists())
    }

    @Test
    fun `storageUsage 一次遍历同时给出字节与册数`() {
        File(root, "$bookId/c00000.txt").apply { parentFile?.mkdirs(); writeText("甲") }
        File(root, "cccccccccccccccccccccccccccccccc/c00000.txt").apply { parentFile?.mkdirs(); writeText("乙") }
        File(root, "dddddddddddddddddddddddddddddddd.tmp").apply { parentFile?.mkdirs(); writeText("导入中") }
        File(root, "stray.txt").writeText("散落文件")

        val usage = store.storageUsage()

        assertEquals("册数不含 .tmp 半成品与散落文件", 2, usage.bookCount)
        assertEquals("字节是全部章文件之和（含半成品与散落文件）",
            "甲".toByteArray().size + "乙".toByteArray().size + "导入中".toByteArray().size + "散落文件".toByteArray().size,
            usage.bytes.toInt())
    }

    @Test
    fun `空内容仓库的 storageUsage 两项都归零`() {
        val usage = store.storageUsage()

        assertEquals(0L, usage.bytes)
        assertEquals(0, usage.bookCount)
    }

    @Test
    fun `内容仓库目录不存在时 storageUsage 也不抛异常`() {
        val usage = BookStore(File(root, "not-created")).storageUsage()

        assertEquals(0L, usage.bytes)
        assertEquals(0, usage.bookCount)
    }
}
