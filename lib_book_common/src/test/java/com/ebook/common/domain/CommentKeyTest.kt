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
    fun keyCarriesAlgorithmVersion() {
        assertTrue(CommentKey.compute("星辰变", "我吃西红柿").startsWith("ck1:"))
    }

    @Test
    fun bookMarksAndRedundantWhitespaceDoNotChangeKey() {
        assertEquals(
            CommentKey.compute("星辰变", "我吃西红柿"),
            CommentKey.compute("《 星辰变 》", "我吃西红柿")
        )
    }

    @Test
    fun fullWidthFormsFoldToHalfWidthBeforeHashing() {
        assertEquals(
            CommentKey.compute("agent 007", "x"),
            CommentKey.compute("ａｇｅｎｔ ００７", "Ｘ")
        )
    }

    @Test
    fun sameTitleDifferentAuthorMustNotCollide() {
        assertNotEquals(
            CommentKey.compute("星辰变", "我吃西红柿"),
            CommentKey.compute("星辰变", "另一个人")
        )
    }

    @Test
    fun authorPlaceholdersAreTreatedAsAbsent() {
        val empty = CommentKey.compute("剑来", null)
        listOf("佚名", "侠名", "未知", "不详", "N/A", "unknown", "  ").forEach { placeholder ->
            assertEquals("占位词「$placeholder」不该改变键", empty, CommentKey.compute("剑来", placeholder))
        }
    }

    @Test
    fun absentAndEmptyAuthorYieldSameKey() {
        assertEquals(CommentKey.compute("剑来", null), CommentKey.compute("剑来", ""))
    }

    @Test
    fun titleAndAuthorBoundariesCannotBeShifted() {
        // 「AB」+「C」与「A」+「BC」必须不同键，靠分隔符保证
        assertNotEquals(CommentKey.compute("AB", "C"), CommentKey.compute("A", "BC"))
    }

    @Test
    fun keyIsFixedLengthLowercaseHexAfterPrefix() {
        val key = CommentKey.compute("某书", "某作者")
        assertEquals(4 + 64, key.length)
        assertTrue(key.substringAfter(':').all { it in "0123456789abcdef" })
    }
}
