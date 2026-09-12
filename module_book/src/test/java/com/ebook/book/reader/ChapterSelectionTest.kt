package com.ebook.book.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ChapterSelection.kt` 的纯逻辑用例：下载软上限与倒序索引换算。
 *
 * 这些语义算错既不会编译失败也不会闪退——只会静默给出错的内容（该弹的确认没弹、
 * 倒序时翻到错章），所以集中在纯 JVM 上锁住（本仓 Compose 页面不做装机级单测，见 AGENTS.md）。
 *
 * 历史：分组切分/组头三态/整组勾选/组头行号一族用例随「百章分组折叠」形态在 2026-09-12
 * 改平铺列表时一并移除（见 ADR-0034 修订说明）。
 */
class ChapterSelectionTest {

    @Test
    fun `下载软上限在五百章边界上放行`() {
        assertFalse(exceedsSelectionCap(0))
        assertFalse(exceedsSelectionCap(MAX_DOWNLOAD_SELECTION))
        assertTrue(exceedsSelectionCap(MAX_DOWNLOAD_SELECTION + 1))
    }

    @Test
    fun `倒序换算与正序换算互为逆运算`() {
        val count = 10

        for (position in 0 until count) {
            assertEquals(position, originalIndexAt(count, descending = false, position = position))
        }
        for (index in 0 until count) {
            assertEquals(
                index,
                displayPositionOf(count = count, descending = false, originalIndex = index),
            )
        }
        for (index in 0 until count) {
            assertEquals(
                index,
                originalIndexAt(
                    count = count,
                    descending = true,
                    position = displayPositionOf(count, descending = true, originalIndex = index),
                ),
            )
        }
    }

    @Test
    fun `倒序时首行是最新章`() {
        assertEquals(2999, originalIndexAt(count = 3000, descending = true, position = 0))
        assertEquals(0, originalIndexAt(count = 3000, descending = true, position = 2999))
    }
}