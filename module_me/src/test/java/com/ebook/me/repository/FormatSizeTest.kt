package com.ebook.me.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [formatSize] 与 [CacheBreakdown] 的纯函数单元测试。
 */
class FormatSizeTest {

    @Test
    fun `字节数小于 1KB 时格式化为 B`() {
        assertEquals("0 B", formatSize(0))
        assertEquals("1 B", formatSize(1))
        assertEquals("1023 B", formatSize(1023))
    }

    @Test
    fun `字节数达到 1KB 时格式化为 KB`() {
        assertEquals("1.0 KB", formatSize(1024))
        assertEquals("1.5 KB", formatSize(1536))
        assertEquals("999.9 KB", formatSize((999.9 * 1024).toLong()))
    }

    @Test
    fun `字节数达到 1MB 时格式化为 MB`() {
        assertEquals("1.0 MB", formatSize(1024L * 1024))
        assertEquals("12.3 MB", formatSize((12.3 * 1024 * 1024).toLong()))
    }

    @Test
    fun `字节数达到 1GB 时格式化为 GB 且保留两位小数`() {
        assertEquals("1.00 GB", formatSize(1024L * 1024 * 1024))
        assertEquals("2.50 GB", formatSize((2.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `CacheBreakdown 总字节为三分类之和`() {
        val breakdown = CacheBreakdown(imageBytes = 1L, tempBytes = 2L, otherBytes = 3L)
        assertEquals(6L, breakdown.totalBytes)
    }
}
