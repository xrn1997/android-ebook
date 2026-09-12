package com.ebook.book.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ChapterSelection.kt` 的纯逻辑用例：下载软上限、范围解析、倒序索引换算与书头主操作三态。
 *
 * 这些语义算错既不会编译失败也不会闪退——只会静默给出错的内容（该弹的确认没弹、
 * 倒序时翻到错章、范围勾错区段），所以集中在纯 JVM 上锁住（本仓 Compose 页面不做装机级单测，
 * 见 AGENTS.md）。
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
    fun `队列在跑时主按钮是暂停`() {
        // 勾选为空也要给暂停：一级入口进来不带勾选，但队列正在跑，这一格必须能刹车
        assertEquals(BookDownloadAction.Pause, bookDownloadActionOf(hasQueue = true, paused = false, effectiveCount = 0))
        // 正在跑又新勾了几章，仍然优先「停住正在发生的」——想下那批新勾选再点一次即可
        assertEquals(BookDownloadAction.Pause, bookDownloadActionOf(hasQueue = true, paused = false, effectiveCount = 50))
    }

    @Test
    fun `已暂停且没有新勾选时主按钮是继续`() {
        assertEquals(BookDownloadAction.Resume, bookDownloadActionOf(hasQueue = true, paused = true, effectiveCount = 0))
    }

    @Test
    fun `已暂停但有新勾选时主按钮变成下载`() {
        // 暂停态下勾了新章，同一枚按钮改口「下载」：入库路径本就顺带解除暂停，
        // 若这时还写着「继续」，用户点下去只会把旧队列重新跑起来、新勾选被吞掉
        assertEquals(BookDownloadAction.Download, bookDownloadActionOf(hasQueue = true, paused = true, effectiveCount = 30))
    }

    @Test
    fun `没有队列时主按钮是下载`() {
        assertEquals(BookDownloadAction.Download, bookDownloadActionOf(hasQueue = false, paused = false, effectiveCount = 20))
        assertEquals(BookDownloadAction.Download, bookDownloadActionOf(hasQueue = false, paused = false, effectiveCount = 0))
    }

    @Test
    fun `暂停标记残留但队列已清空时不给继续`() {
        // 取消本书/下完出队后可能还留着暂停记录，此时「继续」按下去没有任何任务可续，
        // 该退回「下载」
        assertEquals(BookDownloadAction.Download, bookDownloadActionOf(hasQueue = false, paused = true, effectiveCount = 0))
    }

    @Test
    fun `三态里只有下载态依赖勾选`() {
        assertFalse(BookDownloadAction.Download.isEnabledWith(effectiveCount = 0))
        assertTrue(BookDownloadAction.Download.isEnabledWith(effectiveCount = 1))
        // 暂停/继续面对的是既有队列，与本次勾选无关——勾选为空时把它们置灰会锁死刹车
        assertTrue(BookDownloadAction.Pause.isEnabledWith(effectiveCount = 0))
        assertTrue(BookDownloadAction.Resume.isEnabledWith(effectiveCount = 0))
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

    @Test
    fun `范围选择按闭区间换算成零基索引`() {
        assertEquals(0..9, parseChapterRange(from = "1", to = "10", total = 100))
        assertEquals(4..7, parseChapterRange(from = "5", to = "8", total = 100))
    }

    @Test
    fun `范围选择的首尾两端都是合法输入`() {
        assertEquals(0..0, parseChapterRange(from = "1", to = "1", total = 1))
        assertEquals(0..99, parseChapterRange(from = "1", to = "100", total = 100))
        assertEquals(99..99, parseChapterRange(from = "100", to = "100", total = 100))
    }

    @Test
    fun `范围选择容忍章号前后的空白`() {
        assertEquals(4..9, parseChapterRange(from = " 5 ", to = "\t10\n", total = 100))
    }

    @Test
    fun `范围选择对空串与非数字一律返回空`() {
        assertNull(parseChapterRange(from = "", to = "10", total = 100))
        assertNull(parseChapterRange(from = "1", to = "", total = 100))
        assertNull(parseChapterRange(from = "abc", to = "10", total = 100))
        assertNull(parseChapterRange(from = "1", to = "十", total = 100))
        assertNull(parseChapterRange(from = "1.5", to = "10", total = 100))
    }

    @Test
    fun `范围选择对越界章号返回空`() {
        assertNull(parseChapterRange(from = "0", to = "10", total = 100))
        assertNull(parseChapterRange(from = "1", to = "101", total = 100))
        assertNull(parseChapterRange(from = "-1", to = "10", total = 100))
    }

    @Test
    fun `范围选择起大于止时返回空而不是空区间`() {
        assertNull(parseChapterRange(from = "10", to = "5", total = 100))
    }

    @Test
    fun `总章数为零或负数时任何输入都返回空`() {
        assertNull(parseChapterRange(from = "1", to = "1", total = 0))
        assertNull(parseChapterRange(from = "1", to = "1", total = -3))
    }

    @Test
    fun `默认窗口是焦点章起五十章且封顶到最后一章`() {
        assertEquals(100..150, defaultChapterWindow(total = 3000, focusChapter = 100))
        // 焦点章离末尾不足 50 章时封顶，不能越界
        assertEquals(2990..2999, defaultChapterWindow(total = 3000, focusChapter = 2990))
        assertEquals(2999..2999, defaultChapterWindow(total = 3000, focusChapter = 2999))
    }

    @Test
    fun `没有有效焦点章时默认窗口为空`() {
        assertNull(defaultChapterWindow(total = 3000, focusChapter = -1))
        assertNull(defaultChapterWindow(total = 3000, focusChapter = 3000))
        assertNull(defaultChapterWindow(total = 0, focusChapter = 0))
    }

    @Test
    fun `范围默认值优先取当前勾选的边界`() {
        assertEquals(4..7, defaultRangeSelection(total = 3000, selected = setOf(4, 7), focusChapter = 100))
        // 勾选非空时不再看焦点章：用户圈的那段就是他要改的那段
        assertEquals(0..0, defaultRangeSelection(total = 3000, selected = setOf(0), focusChapter = -1))
    }

    @Test
    fun `无勾选时范围默认值退回焦点章的五十章窗口`() {
        assertEquals(
            100..150,
            defaultRangeSelection(total = 3000, selected = emptySet(), focusChapter = 100),
        )
    }

    @Test
    fun `一级入口无焦点章时范围默认值是第一章起五十章`() {
        assertEquals(0..49, defaultRangeSelection(total = 3000, selected = emptySet(), focusChapter = -1))
        // 全书不足 50 章时退化为整本
        assertEquals(0..29, defaultRangeSelection(total = 30, selected = emptySet(), focusChapter = -1))
        assertEquals(0..0, defaultRangeSelection(total = 1, selected = emptySet(), focusChapter = -1))
    }

    @Test
    fun `没有章节时范围默认值为空`() {
        assertNull(defaultRangeSelection(total = 0, selected = emptySet(), focusChapter = -1))
    }

    @Test
    fun `范围默认值本身一定能被解析器接受`() {
        // 默认值是「确定」可直接按下的区间：若与解析口径分叉，用户一进对话框就看见灰按钮
        for (total in intArrayOf(1, 30, 3000)) {
            val range = defaultRangeSelection(total, selected = emptySet(), focusChapter = -1)!!
            assertEquals(
                range,
                parseChapterRange(
                    from = (range.first + 1).toString(),
                    to = (range.last + 1).toString(),
                    total = total,
                ),
            )
        }
    }
}