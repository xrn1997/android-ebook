package com.ebook.book.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [ReaderNavigation] 的模式路由与单进度出口。
 *
 * 原先「当前是翻页还是滚屏」同时由 `turnModeIndex` 与「哪个控制器 ref 非空」两套编码表达，
 * 音量键、启动、换源、滑条各写一处分支。这里锁住一个判据、一条路由。
 */
class ReaderNavigationTest {

    private class FakePaged : ReaderTurnController {
        var position: Pair<Int, Int>? = null
        var reported: Boolean? = null
        var next = 0
        var prev = 0

        override fun setPosition(chapterIndex: Int, pageIndex: Int, reportProgress: Boolean) {
            position = chapterIndex to pageIndex
            reported = reportProgress
        }

        override fun turnNext() {
            next++
        }

        override fun turnPrev() {
            prev++
        }
    }

    private class FakeScroll : ReaderTurnController {
        var position: Pair<Int, Int>? = null
        var reported: Boolean? = null
        var scrolls = mutableListOf<Boolean>()

        override fun setPosition(chapterIndex: Int, pageIndex: Int, reportProgress: Boolean) {
            position = chapterIndex to pageIndex
            reported = reportProgress
        }

        override fun turnNext() {
            scrolls += true
        }

        override fun turnPrev() {
            scrolls += false
        }
    }

    private fun navigation(
        mode: ReaderTurnMode = ReaderTurnMode.PAGED,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Triple<ReaderNavigation, FakePaged, FakeScroll> {
        val paged = FakePaged()
        val scroll = FakeScroll()
        val nav = ReaderNavigation(initialMode = mode, onProgress = onProgress)
        nav.attach(paged, scroll)
        return Triple(nav, paged, scroll)
    }

    @Test
    fun `翻页模式只把 turnNext 路由给 paged`() {
        val (nav, paged, scroll) = navigation()

        nav.turnNext()
        nav.turnPrev()

        assertEquals(1, paged.next)
        assertEquals(1, paged.prev)
        assertEquals(emptyList<Boolean>(), scroll.scrolls)
    }

    @Test
    fun `滚屏模式把 turnNext 路由给 scrollOneScreen`() {
        val (nav, paged, scroll) = navigation(ReaderTurnMode.SCROLL)

        nav.turnNext()
        nav.turnPrev()

        assertEquals(0, paged.next)
        assertEquals(0, paged.prev)
        assertEquals(listOf(true, false), scroll.scrolls)
    }

    @Test
    fun `setPosition 只定位当前模式的控制器`() {
        val (nav, paged, scroll) = navigation()

        nav.setPosition(3, 7)
        assertEquals("翻页模式只该驱动 paged，另一侧会白加载并用未解析页码覆盖进度", 3 to 7, paged.position)
        assertNull(scroll.position)

        nav.setMode(ReaderTurnMode.SCROLL)
        nav.setPosition(4, 2)
        assertEquals(4 to 2, scroll.position)
        assertEquals("切到滚屏后 paged 不再被驱动", 3 to 7, paged.position)
    }

    /**
     * 冷启动与重分页走 [ReaderNavigation.converge]：当前模式照常定位并报进度，另一侧只放落点。
     *
     * 另一侧此刻没有渲染，不预热就带着「章号 0、块数 0」的初值躺着——用户一换模式，新容器成形的
     * 那一帧落点是错的，而 pendingTurnModeSwitch 的换算赶在这一帧之后。让它连进度一起报回来同样
     * 不行：滚屏的屏号是章内块号，与翻页的屏号不是同一个量，会把当前模式刚写的进度盖掉（ADR-0037）。
     */
    @Test
    fun `converge 预热另一侧落点但不让它报进度`() {
        val (nav, paged, scroll) = navigation()

        nav.converge(3, 7)

        assertEquals(3 to 7, paged.position)
        assertEquals(true, paged.reported)
        assertEquals("另一侧必须拿到同一个落点", 3 to 7, scroll.position)
        assertEquals("另一侧不许回报进度", false, scroll.reported)
    }

    @Test
    fun `converge 在滚屏模式下预热的是翻页侧`() {
        val (nav, paged, scroll) = navigation(ReaderTurnMode.SCROLL)

        nav.converge(2, 4)

        assertEquals(2 to 4, scroll.position)
        assertEquals(true, scroll.reported)
        assertEquals(false, paged.reported)
    }

    @Test
    fun `模式切换后路由判据立即改变`() {
        val (nav, paged, scroll) = navigation()

        nav.setMode(ReaderTurnMode.SCROLL)
        nav.turnNext()
        nav.setMode(ReaderTurnMode.PAGED)
        nav.turnNext()

        assertEquals(1, paged.next)
        assertEquals(listOf(true), scroll.scrolls)
    }

    @Test
    fun `进度出口就是构造时传入的 sink`() {
        val sink: (Int, Int) -> Unit = { _, _ -> }
        val nav = ReaderNavigation(initialMode = ReaderTurnMode.PAGED, onProgress = sink)

        assertSame("两个控制器要共用同一个出口，导航不得再包一层转调", sink, nav.onProgress)
    }
}
