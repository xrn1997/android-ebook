package com.ebook.common.util

import org.junit.Assert.assertEquals
import java.io.File
import java.nio.file.Files
import org.junit.Test

/**
 * [treeSize] 的目录树求和测试。
 *
 * 这是「书籍内容到底占了多少磁盘」的唯一计算点：`BookStore`（`filesDir/books`）与
 * `CacheModel`（`cacheDir`）都要用它，两处各写一遍递归 walk 会分叉出两个「占用」口径。
 * 域无关，真正的家是外部库 `lib_common`，等下次联动窗口与 `DateUtil` 一并上移。
 *
 * 锁住：嵌套求和、只算普通文件（目录名不计入）、空目录与不存在的目录都归 0（展示层
 * 因此不必为「一本书都还没有」写特判）。
 */
class TreeSizeTest {

    private fun tempRoot(): File = Files.createTempDirectory("tree-size").toFile().apply { deleteOnExit() }

    @Test
    fun `递归累加嵌套目录里的全部文件字节`() {
        val root = tempRoot()
        File(root, "book-a/c00001.txt").apply { parentFile.mkdirs() }.writeText("x".repeat(1000))
        File(root, "book-a/c00002.txt").writeText("x".repeat(500))
        File(root, "book-b/nested/deep.txt").apply { parentFile.mkdirs() }.writeText("x".repeat(250))

        assertEquals(1750L, root.treeSize())
    }

    @Test
    fun `目录本身不计入字节，只有普通文件参与求和`() {
        val root = tempRoot()
        File(root, "only-dir/").mkdirs()

        assertEquals(0L, root.treeSize())
    }

    @Test
    fun `空目录与不存在的目录都归零`() {
        assertEquals(0L, tempRoot().treeSize())
        assertEquals(0L, File(tempRoot(), "not-created").treeSize())
    }
}
