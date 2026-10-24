package com.ebook.book.reader

import com.ebook.db.event.DBCode
import com.ebook.source.analyze.BookSourceNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [ReaderPageLoader] 的测试：装页组合与失败分档。
 *
 * 这段逻辑原先长在 `ReadBookActivity.loadPage` 里（取正文 → 后台分页 → 哨兵解析 → 切片 →
 * 失败分档），只有起一个 Activity 才能触发，因此零测试；而「哪种异常该弹用户提示」正是
 * 最容易写错的判据——书源失效要提示用户去换源/重导，排版未就绪只该静默留着重试按钮。
 * 测试在 loader 的 interface 上打：正文来源是一支 lambda，分页用真状态机 + 假测量引擎。
 */
class ReaderPageLoaderTest {

    private class FakeEngine(
        private val lineStarts: List<Int> = listOf(0, 5),
        override val fontSizeSp: Float = 18f,
    ) : ReaderLayoutEngine {
        override fun measureBlock(widthPx: Int, viewportHeightPx: Int): ReaderBlockMetrics =
            ReaderBlockMetrics(lineCount = 1, heightPx = 100)

        override fun lineStartOffsets(text: String, widthPx: Int): List<Int> = lineStarts
    }

    private fun readyPagination(): ReaderPagination = ReaderPagination().apply {
        updateViewport(100, 200)
        measure(FakeEngine())
    }

    private fun chapter(text: String = "abcdefghij") =
        ReaderChapterText(title = "第1章", contentRef = "c1", text = text)

    private fun loader(
        chapterSource: suspend (Int) -> ReaderChapterText?,
        pagination: ReaderPagination = readyPagination(),
        failures: MutableList<Throwable> = mutableListOf(),
    ) = ReaderPageLoader(chapterSource, pagination) { failures += it }

    @Test
    fun `正常路径按哨兵解析页码并切片`() = runTest {
        val loader = loader({ chapter() })

        val loaded = loader.load(0, DBCode.BookContentView.DUR_PAGE_INDEX_END)

        assertNotNull(loaded)
        assertEquals("END 应落到最后一页", 1, loaded!!.durPageIndex)
        assertEquals(2, loaded.pageAll)
        assertEquals("fghij", loaded.text)
        assertEquals("第1章", loaded.title)
    }

    @Test
    fun `按传入的章索引取正文`() = runTest {
        var asked = -1
        val loader = loader({ index -> asked = index; chapter() })

        loader.load(7, 0)

        assertEquals(7, asked)
    }

    @Test
    fun `书源失效时上报并返回 null`() = runTest {
        val failures = mutableListOf<Throwable>()
        val boom = BookSourceNotFoundException("https://gone.example", "detail")
        val loader = loader({ throw boom }, failures = failures)

        val loaded = loader.load(0, 0)

        assertNull(loaded)
        assertEquals("这类失败用户能自己处置，必须开口", listOf<Throwable>(boom), failures)
    }

    @Test
    fun `其他异常只记日志不上报`() = runTest {
        val failures = mutableListOf<Throwable>()
        val loader = loader({ throw IllegalStateException("解析失败") }, failures = failures)

        val loaded = loader.load(0, 0)

        assertNull(loaded)
        assertTrue("解析失败按静默降级，不该弹提示", failures.isEmpty())
    }

    @Test
    fun `取不到正文时返回 null 且不上报`() = runTest {
        val failures = mutableListOf<Throwable>()
        val loader = loader({ null }, failures = failures)

        assertNull(loader.load(0, 0))
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `排版未就绪时返回 null 且不上报`() = runTest {
        val failures = mutableListOf<Throwable>()
        val loader = loader({ chapter() }, pagination = ReaderPagination(), failures = failures)

        assertNull(loader.load(0, 0))
        assertTrue("测量还没落定不是故障", failures.isEmpty())
    }

    @Test
    fun `取消原样上抛，不当成加载失败`() = runTest {
        val loader = loader({ throw CancellationException("用户翻走了") })

        try {
            loader.load(0, 0)
            fail("取消必须上抛，否则协程无法收敛")
        } catch (_: CancellationException) {
            // 期望路径
        }
    }
}
