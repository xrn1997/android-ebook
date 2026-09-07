package com.ebook.common.analyze.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [BookFormat] 枚举的基础测试：确保新增的 [BookFormat.NETWORK] 存在，
 * 且 `fromExtension` 不会把它当成可解析的文件格式（网络书没有本地文件扩展名）。
 */
class BookFormatTest {

    @Test
    fun `NETWORK format exists with no file extension`() {
        val format = BookFormat.NETWORK
        assertEquals("network", format.extension)
    }

    @Test
    fun `fromExtension returns null for network`() {
        assertNull(BookFormat.fromExtension("network"))
    }

    @Test
    fun `fromExtension still resolves txt`() {
        assertEquals(BookFormat.TXT, BookFormat.fromExtension("txt"))
    }
}
