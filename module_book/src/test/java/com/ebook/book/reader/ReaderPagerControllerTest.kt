package com.ebook.book.reader

import android.content.Context
import androidx.compose.runtime.MonotonicFrameClock
import androidx.test.core.app.ApplicationProvider
import com.ebook.db.event.DBCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ReaderPagerController] 三页窗口状态机的回归测试（JVM，无设备依赖）。
 *
 * 锁定的核心口径是"提交翻页时目标页尚未/无法 Loaded，窗口如何收敛"。修复前的实现
 * 只在目标页已 Loaded 时调 refreshWindow，翻到加载中/失败的页会得到一个坏窗口：
 * - nextKey 停在当前页上 → 向后翻是空转（两层槽位渲染同一个 key，滑完画面毫无推进）；
 * - prevKey 被无条件清空 → 向前翻被判定"没有上一页"，刚读过的那页也被 prune 掉；
 * - 失败页的 job 完成后即从 jobs 注销，不会再有回调来救窗口 → 只剩中央那个重试胶囊能救。
 *
 * 修复后的不变量（见 ReaderPagerController 类 KDoc「窗口收敛规则」）：三键互不相同、
 * 未知方向收敛为 null、来路页保留为相邻方向，且翻回去再翻过来会自动重试失败页。
 *
 * 运行方式说明：Robolectric 仅提供 [Context] 与资源（无上一页/下一页走 Toast 提示），
 * 不渲染任何 View；`Animatable` 的动画由 [VirtualFrameClock] 按虚拟帧收敛，不挂
 * Choreographer、不消耗墙上时间。控制器必须挂在**前台** TestScope 上（而不是
 * backgroundScope）：`advanceUntilIdle()` 一旦只剩 backgroundScope 的任务就不再推进虚拟
 * 时间，那样页面加载永远不会完成，窗口也就无从重算。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderPagerControllerTest {

    @Test
    fun `正常翻页时窗口按加载结果重算`() = runTest(VirtualFrameClock()) {
        val book = FakeBook(listOf(3, 3))
        val controller = newPager(book)

        controller.setInitData(0, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        advanceUntilIdle()

        assertNull("书首页无上一页", controller.prevKey)
        assertEquals(ReaderPageKey(0, 1), controller.nextKey)

        turnNext(controller)
        assertEquals(ReaderPageKey(0, 1), controller.durKey)
        assertEquals(ReaderPageKey(0, 0), controller.prevKey)
        assertEquals(ReaderPageKey(0, 2), controller.nextKey)

        turnNext(controller)
        assertEquals(ReaderPageKey(0, 2), controller.durKey)
        assertEquals(ReaderPageKey(0, 1), controller.prevKey)
        // 章末页的下一页是下一章的「章节首页」哨兵
        assertEquals(ReaderPageKey(1, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN), controller.nextKey)
    }

    @Test
    fun `翻到加载失败的页时 来路页仍是上一页 去向收敛为空`() = runTest(VirtualFrameClock()) {
        val book = FakeBook(listOf(3, 3))
        book.fail(ReaderPageKey(0, 2))
        val controller = newPager(book)

        controller.setInitData(0, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        advanceUntilIdle()
        turnNext(controller)
        assertEquals(ReaderPageKey(0, 1), controller.durKey)

        turnNext(controller) // → (0, 2)，该页加载失败

        assertEquals(ReaderPageKey(0, 2), controller.durKey)
        assertEquals(ReaderPageUi.Error, controller.uiOf(controller.durKey))
        assertNull("nextKey 不得停在当前页自身：自指即向后翻空转", controller.nextKey)
        assertEquals("来路页必须保留，否则失败页成为回不去的死页", ReaderPageKey(0, 1), controller.prevKey)
    }

    @Test
    fun `失败页上仍能翻回刚读过的那页并重发失败页请求`() = runTest(VirtualFrameClock()) {
        val book = FakeBook(listOf(3, 3))
        book.fail(ReaderPageKey(0, 2))
        val controller = newPager(book)

        controller.setInitData(0, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        advanceUntilIdle()
        turnNext(controller)
        turnNext(controller) // 停在失败页
        assertEquals(1, book.requests[ReaderPageKey(0, 2)])

        // 向前方向未被夹死：右滑能真正拖动（修复前 hasPre=false，dragBy 原样不动）
        controller.dragBy(300f, PAGE_WIDTH_PX)
        advanceUntilIdle()
        assertEquals(300f, controller.drag.value, 0.01f)

        turnPrev(controller)

        assertEquals(ReaderPageKey(0, 1), controller.durKey)
        assertTrue("回翻后当前页应是已加载的正文", controller.uiOf(controller.durKey) is ReaderPageUi.Loaded)
        assertEquals(ReaderPageKey(0, 0), controller.prevKey)
        assertEquals(ReaderPageKey(0, 2), controller.nextKey)
        // 失败页回到窗口内即被重新加载（其 job 已注销，ensureLoad 必然重发），等价自动重试
        assertEquals(2, book.requests[ReaderPageKey(0, 2)])
    }

    @Test
    fun `失败页上向后翻不空转 当前页与动画锁都不残留`() = runTest(VirtualFrameClock()) {
        val book = FakeBook(listOf(3, 3))
        book.fail(ReaderPageKey(0, 2))
        val controller = newPager(book)

        controller.setInitData(0, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        advanceUntilIdle()
        turnNext(controller)
        turnNext(controller)

        // 点击右三分区/音量下走的是 turnNext：去向已收敛为 null → 提示"没有下一页"后原样返回，
        // 不会再跑一次滑完却毫无变化的动画（修复前 nextKey 自指，动画演完仍停在同一页）
        controller.turnNext()
        advanceUntilIdle()

        assertEquals(ReaderPageKey(0, 2), controller.durKey)
        assertNull(controller.nextKey)
        assertFalse(controller.isMoving)
        assertEquals(0f, controller.drag.value, 0.01f)
    }

    @Test
    fun `翻向加载失败的上一页时 来路页保留为下一页`() = runTest(VirtualFrameClock()) {
        val book = FakeBook(listOf(3, 3))
        book.fail(ReaderPageKey(0, 0))
        val controller = newPager(book)

        controller.setInitData(0, 1)
        advanceUntilIdle()
        assertEquals(ReaderPageUi.Error, controller.uiOf(controller.prevKey))

        turnPrev(controller) // → (0, 0)，该页加载失败

        assertEquals(ReaderPageKey(0, 0), controller.durKey)
        assertNull(controller.prevKey)
        assertEquals("来路页保留为下一页，向前翻失败时仍能退回", ReaderPageKey(0, 1), controller.nextKey)
    }

    @Test
    fun `翻向仍在加载的页时来路页保留 加载完成后窗口补全`() = runTest(VirtualFrameClock()) {
        val book = FakeBook(listOf(3, 3))
        book.gates[ReaderPageKey(0, 1)] = CompletableDeferred()
        val controller = newPager(book)

        controller.setInitData(0, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        advanceUntilIdle() // 首页就绪，(0, 1) 被闸门挡在 Loading

        turnNext(controller)

        assertEquals(ReaderPageKey(0, 1), controller.durKey)
        assertEquals(ReaderPageUi.Loading, controller.uiOf(controller.durKey))
        assertNull("在途页的去向未知，不得自指", controller.nextKey)
        assertEquals(
            "来路页（含哨兵键）保留，加载期间也能翻回去",
            ReaderPageKey(0, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN),
            controller.prevKey
        )

        book.gates.getValue(ReaderPageKey(0, 1)).complete(Unit)
        advanceUntilIdle() // 完成回调命中 key == durKey → refreshWindow

        assertEquals(ReaderPageKey(0, 0), controller.prevKey)
        assertEquals(ReaderPageKey(0, 2), controller.nextKey)
    }

    @Test
    fun `窗口重算不重抓已加载的页`() = runTest(VirtualFrameClock()) {
        val book = FakeBook(listOf(3, 3))
        val controller = newPager(book)

        controller.setInitData(0, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        advanceUntilIdle()
        turnNext(controller) // → (0, 1)：prevKey=(0, 0)、nextKey=(0, 2) 各首次加载
        assertEquals(1, book.requests[ReaderPageKey(0, 0)])
        assertEquals(1, book.requests[ReaderPageKey(0, 1)])

        turnPrev(controller) // 回到 (0, 0)：窗口重算把已 Loaded 的 (0, 1) 重新登记为 nextKey

        assertEquals(
            "已 Loaded 的页不该被重抓：job 完成即注销，只看 jobs 去重会让回翻时刚读过的页闪一下加载态",
            1, book.requests[ReaderPageKey(0, 1)]
        )
        assertTrue(controller.uiOf(controller.nextKey) is ReaderPageUi.Loaded)
    }
}

/**
 * 假书：章节页数由入参给定，[fail] 标记的页在 [load] 里返回 null（失败态）。
 *
 * - [requests] 记每个 key 的请求次数，是"翻回去再翻过来 = 自动重试失败页"的判据；
 * - [gates] 是挂起闸门，用来确定性地制造"翻页提交时目标页仍是 Loading"的窗口，
 *   不靠线程调度碰运气。
 */
private class FakeBook(val pagesPerChapter: List<Int>) {

    val requests = mutableMapOf<ReaderPageKey, Int>()
    val gates = mutableMapOf<ReaderPageKey, CompletableDeferred<Unit>>()
    private val failures = mutableSetOf<ReaderPageKey>()

    fun fail(vararg keys: ReaderPageKey) {
        failures += keys
    }

    /** 模拟 ReadBookActivity.loadPage：哨兵页码在分页结果里解析，失败返回 null。 */
    suspend fun load(chapterIndex: Int, pageIndex: Int): ReaderPageUi.Loaded? {
        val key = ReaderPageKey(chapterIndex, pageIndex)
        requests[key] = (requests[key] ?: 0) + 1
        gates[key]?.await()
        if (key in failures) return null
        val resolved = resolve(chapterIndex, pageIndex)
        return ReaderPageUi.Loaded(
            title = "第${chapterIndex + 1}章",
            chapterIndex = chapterIndex,
            durPageIndex = resolved,
            pageAll = pagesPerChapter[chapterIndex],
            text = "正文 $chapterIndex-$resolved"
        )
    }

    /** 与生产代码同一套哨兵语义：BEGIN → 章首页、END → 章末页（见 ReaderPageKey KDoc）。 */
    private fun resolve(chapterIndex: Int, pageIndex: Int): Int = when (pageIndex) {
        DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN -> 0
        DBCode.BookContentView.DUR_PAGE_INDEX_END -> pagesPerChapter[chapterIndex] - 1
        else -> pageIndex
    }
}

/**
 * 虚拟帧时钟：每次取帧固定推进 16ms。
 *
 * `Animatable.animateTo` 靠 `withFrameNanos` 驱动，纯 JVM 单测里没有 Choreographer，
 * 不提供帧时钟会直接抛"No MonotonicFrameClock"。这里给一个只按虚拟时间前进的实现：
 * spring 动画在数十帧内收敛，且完全不消耗墙上时间。
 */
private class VirtualFrameClock : MonotonicFrameClock {

    private var frameTimeNanos = 0L

    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        frameTimeNanos += FRAME_STEP_NANOS
        return onFrame(frameTimeNanos)
    }

    private companion object {
        /** 约 60fps 的帧间隔 */
        const val FRAME_STEP_NANOS = 16_000_000L
    }
}

private const val PAGE_WIDTH_PX = 1000f

/** 建控制器：宽度与 30dp 阈值先写入，手势收尾判定与程序化翻页都依赖它们。 */
private fun TestScope.newPager(book: FakeBook): ReaderPagerController =
    ReaderPagerController(
        // 前台 TestScope：advanceUntilIdle() 不推进 backgroundScope 的任务（见类 KDoc），
        // 挂到 background 上会让页面加载永远不完成；测试里每个用例都会把闸门放行，
        // 不会留下未结束的协程让 runTest 报错
        scope = this,
        context = ApplicationProvider.getApplicationContext<Context>(),
        chapterSize = { book.pagesPerChapter.size },
        chapterTitle = { "第${it + 1}章" },
        loadPage = { chapterIndex, pageIndex -> book.load(chapterIndex, pageIndex) },
        onProgress = { _, _ -> }
    ).apply {
        pageWidthPx = PAGE_WIDTH_PX
        turnThresholdPx = 30f
    }

/** 手势向后翻一页：越过阈值 → commitNext，并把动画与随后的加载跑完。 */
private fun TestScope.turnNext(controller: ReaderPagerController) {
    controller.settle(-PAGE_WIDTH_PX / 2)
    advanceUntilIdle()
}

/** 手势向前翻一页：越过阈值 → commitPrev。 */
private fun TestScope.turnPrev(controller: ReaderPagerController) {
    controller.settle(PAGE_WIDTH_PX / 2)
    advanceUntilIdle()
}
