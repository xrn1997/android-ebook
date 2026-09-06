package com.ebook.common.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CommentKey] 单测（spec §9.1、§9.5 约束 1）。
 *
 * 要锁住四件事：同书不同来源算出同键、同名不同作者算出不同键、作者占位词不参与键计算、
 * 结果带算法版本前缀。评论是**用户产生的不可再生**数据，归一化一改就是换键空间，这些断言
 * 是防回归的最后一道闸。
 */
class CommentKeyTest {

    @Test
    fun `键带 ck1 算法版本前缀`() {
        assertTrue(CommentKey.compute("星辰变", "我吃西红柿").startsWith("ck1:"))
    }

    @Test
    fun `书名号与多余空白不改变键`() {
        assertEquals(
            CommentKey.compute("星辰变", "我吃西红柿"),
            CommentKey.compute("《 星辰变 》", "我吃西红柿")
        )
    }

    @Test
    fun `全角形态在哈希前折叠为半角`() {
        assertEquals(
            CommentKey.compute("agent 007", "x"),
            CommentKey.compute("ａｇｅｎｔ ００７", "Ｘ")
        )
    }

    @Test
    fun `同名不同作者必须算出不同键`() {
        assertNotEquals(
            CommentKey.compute("星辰变", "我吃西红柿"),
            CommentKey.compute("星辰变", "另一个人")
        )
    }

    @Test
    fun `作者占位词一律视同缺省`() {
        val empty = CommentKey.compute("剑来", null)
        listOf("佚名", "侠名", "未知", "不详", "N/A", "unknown", "  ").forEach { placeholder ->
            assertEquals("占位词「$placeholder」不该改变键", empty, CommentKey.compute("剑来", placeholder))
        }
    }

    @Test
    fun `作者为 null 与空串算出同键`() {
        assertEquals(CommentKey.compute("剑来", null), CommentKey.compute("剑来", ""))
    }

    @Test
    fun `书名与作者的边界不可挪移`() {
        // 「AB」+「C」与「A」+「BC」必须不同键，靠分隔符保证
        assertNotEquals(CommentKey.compute("AB", "C"), CommentKey.compute("A", "BC"))
    }

    @Test
    fun `前缀后的键体是定长小写十六进制`() {
        val key = CommentKey.compute("某书", "某作者")
        assertEquals(4 + 64, key.length)
        assertTrue(key.substringAfter(':').all { it in "0123456789abcdef" })
    }
}
