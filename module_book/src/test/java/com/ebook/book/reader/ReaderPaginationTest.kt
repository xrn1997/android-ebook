package com.ebook.book.reader

import com.ebook.db.event.DBCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ReaderPageWindow] 的纯函数回归：切片边界、哨兵页码与结尾换行处置。
 *
 * 这些判定原先埋在 [com.ebook.book.ReadBookActivity.loadPage] 里，只有经过 Activity
 * 才能触发；提成值对象后可以在纯 JVM 下锁住「末页不越界」与「页首尾相接不丢字」。
 */
class ReaderPageWindowTest {

    @Test
    fun `最后一页按剩余行切片而不是越界`() {
        val window = ReaderPageWindow("abcdefghij", listOf(0, 2, 4, 6, 8), pageLineCount = 2)

        assertEquals("ij", window.slice(4))
    }

    @Test
    fun `哨兵页码 BEGIN 落到第一页 END 落到最后一页`() {
        val window = ReaderPageWindow("abcdefghij", listOf(0, 2, 4, 6, 8), pageLineCount = 2)

        assertEquals(0, window.resolvePageIndex(DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN))
        assertEquals(2, window.resolvePageIndex(DBCode.BookContentView.DUR_PAGE_INDEX_END))
    }

    @Test
    fun `普通页码越界时钳到最后一页`() {
        val window = ReaderPageWindow("abcdefghij", listOf(0, 2, 4, 6, 8), pageLineCount = 2)

        assertEquals(2, window.resolvePageIndex(99))
        assertEquals(0, window.resolvePageIndex(-3))
    }

    @Test
    fun `页文本去掉结尾换行但保留段间换行`() {
        val window = ReaderPageWindow("ab\ncd\n", listOf(0, 3), pageLineCount = 2)

        assertEquals("ab\ncd", window.slice(0))
    }

    @Test
    fun `行集为空时页数为零且切片为空`() {
        val window = ReaderPageWindow("", emptyList(), pageLineCount = 2)

        assertEquals(0, window.pageCount)
        assertEquals("", window.slice(0))
    }
}

/**
 * [ReaderPagination] 的状态机回归：一次实测落定行数与块高、排版缓存只算一次、
 * 换模式换算按行号而不是屏号。
 */
class ReaderPaginationTest {

    private class FakeLayoutEngine(
        private val metrics: ReaderBlockMetrics? = ReaderBlockMetrics(lineCount = 10, heightPx = 100),
        private val lineStarts: List<Int> = (0 until 10).map { it * 10 },
        override val fontSizeSp: Float = 18f,
    ) : ReaderLayoutEngine {
        var measureCalls = 0
        var layoutCalls = 0

        override fun measureBlock(widthPx: Int, viewportHeightPx: Int): ReaderBlockMetrics? {
            measureCalls++
            return metrics
        }

        override fun lineStartOffsets(text: String, widthPx: Int): List<Int> {
            layoutCalls++
            return lineStarts
        }
    }

    @Test
    fun `measure 一次落定行数与块高`() {
        val pagination = ReaderPagination()
        pagination.updateViewport(100, 200)

        val metrics = pagination.measure(FakeLayoutEngine())

        assertEquals(ReaderBlockMetrics(lineCount = 10, heightPx = 100), metrics)
        assertEquals(10, pagination.pageLineCount)
        assertEquals(100, pagination.blockHeightPx)
    }

    @Test
    fun `未测量视口时 measure 返回 null`() {
        val pagination = ReaderPagination()

        assertNull(pagination.measure(FakeLayoutEngine()))
        assertEquals(0, pagination.pageLineCount)
    }

    @Test
    fun `measure 返回 null 时不覆盖上次结果`() {
        val pagination = ReaderPagination()
        pagination.updateViewport(100, 200)
        pagination.measure(FakeLayoutEngine())
        val failingEngine = object : ReaderLayoutEngine {
            override val fontSizeSp: Float = 18f
            override fun measureBlock(widthPx: Int, viewportHeightPx: Int): ReaderBlockMetrics? = null
            override fun lineStartOffsets(text: String, widthPx: Int): List<Int> = emptyList()
        }

        assertNull(pagination.measure(failingEngine))
        assertEquals("已可用的分页不能被中间帧打回 0", 10, pagination.pageLineCount)
        assertEquals(100, pagination.blockHeightPx)
    }

    @Test
    fun `paginate 对同一章同一字号只调用一次排版引擎`() {
        val engine = FakeLayoutEngine(metrics = ReaderBlockMetrics(lineCount = 2, heightPx = 100))
        val pagination = ReaderPagination()
        pagination.updateViewport(100, 200)
        pagination.measure(engine)

        val first = pagination.paginate("0123456789", "chapter-1")
        val second = pagination.paginate("0123456789", "chapter-1")

        assertEquals(1, engine.layoutCalls)
        assertEquals(5, first?.pageCount)
        assertEquals(5, second?.pageCount)
    }

    @Test
    fun `paginate 内容为空时返回 null`() {
        val engine = FakeLayoutEngine()
        val pagination = ReaderPagination()
        pagination.updateViewport(100, 200)
        pagination.measure(engine)

        assertNull(pagination.paginate("", "chapter-1"))
        assertEquals(0, engine.layoutCalls)
    }

    @Test
    fun `convertPageIndex 按行号换算并钳住非负`() {
        val pagination = ReaderPagination()

        assertEquals(4, pagination.convertPageIndex(2, 10, 5))
        assertEquals(0, pagination.convertPageIndex(-1, 10, 5))
        assertEquals(0, pagination.convertPageIndex(2, 0, 5))
    }
}
