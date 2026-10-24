package com.ebook.book.importer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImportScannerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun write(path: String, size: Int = 0): File =
        File(temp.root, path).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(size))
        }

    @Test
    fun `按扩展名与一百KB阈值筛选`() {
        val small = write("small.txt", 100 * 1024)
        val big = write("big.txt", 100 * 1024 + 1)
        val epub = write("book.epub")
        write("other.pdf", 200 * 1024)

        val result = ImportScanner().scan(temp.root).toSet()

        assertEquals(setOf(big, epub), result)
        assertTrue(small !in result)
    }

    @Test
    fun `嵌套目录里的合格文件也入选`() {
        val nested = write("sub/deep/book.txt", 100 * 1024 + 1)
        assertEquals(setOf(nested), ImportScanner().scan(temp.root).toSet())
    }

    @Test
    fun `跳过 Android data 与 obb 目录`() {
        write("Android/data/hidden.epub")
        write("Android/obb/hidden.txt", 200 * 1024)
        val visible = write("visible.epub")

        assertEquals(setOf(visible), ImportScanner().scan(temp.root).toSet())
    }

    @Test
    fun `取消后不再扫`() {
        write("book.epub")
        assertTrue(ImportScanner().scan(temp.root) { true }.isEmpty())
    }

    @Test
    fun `不存在的根返回空`() {
        assertTrue(ImportScanner().scan(File(temp.root, "missing")).isEmpty())
    }

    @Test
    fun `中途取消返回已收集的前缀`() {
        val a = write("a.epub")
        val b = write("b.epub")
        var calls = 0

        // 第 1 次查在进入目录前，第 2 次在第一个子项前（已收下它），第 3 次在第二个子项前取消；
        // 目录项顺序由文件系统决定，所以只断言前缀里恰好有一个文件。
        val result = ImportScanner().scan(temp.root) {
            calls++
            calls >= 3
        }

        assertEquals(1, result.size)
        assertTrue(result.single() in setOf(a, b))
    }

    @Test
    fun `大写扩展名同样入选`() {
        val epub = write("BOOK.EPUB")
        val txt = write("BIG.TXT", 100 * 1024 + 1)

        assertEquals(setOf(epub, txt), ImportScanner().scan(temp.root).toSet())
    }

    @Test
    fun `已存在的空目录返回空`() {
        assertTrue(ImportScanner().scan(temp.newFolder("empty")).isEmpty())
    }

    @Test
    fun `不可读的根目录返回空`() {
        val unreadable = object : File(temp.root, "unreadable") {
            override fun isDirectory(): Boolean = true
            override fun listFiles(): Array<File>? = null
        }

        assertTrue(ImportScanner().scan(unreadable).isEmpty())
    }
}
