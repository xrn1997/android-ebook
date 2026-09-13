package com.ebook.book.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [fitLines] 的回归测试（纯函数，无排版引擎依赖）。
 *
 * 锁两条：块高取「最后一行的实测底部」而不是行数乘行高（后者会差出零点几像素×行数），
 * 以及视口放不下任何一行时返回 null（调用方据此不分块，而不是切出 0 行的空块）。
 */
class ReaderBlockMetricsTest {

    @Test
    fun `块高取放得下的最后一行的实测底部`() {
        // 每行 40px 高，视口 100px → 放得下 2 行，块高 80（不是 100，也不是 2×行高的估算值）
        val metrics = fitLines(intArrayOf(40, 80, 120, 160), viewportHeightPx = 100)
        assertEquals(ReaderBlockMetrics(lineCount = 2, heightPx = 80), metrics)
    }

    @Test
    fun `视口恰好等于行底部时该行算放得下`() {
        val metrics = fitLines(intArrayOf(40, 80), viewportHeightPx = 80)
        assertEquals(ReaderBlockMetrics(lineCount = 2, heightPx = 80), metrics)
    }

    @Test
    fun `一行都放不下时返回 null`() {
        assertNull(fitLines(intArrayOf(40, 80), viewportHeightPx = 20))
    }

    @Test
    fun `空布局返回 null`() {
        assertNull(fitLines(intArrayOf(), viewportHeightPx = 100))
    }
}
