package com.ebook.book.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ebook.db.event.DBCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ReaderScrollController] 的回归测试（Robolectric 仅供 Context，不渲染任何 View）。
 *
 * 锁的是**跨章连续列表**这套模型的口径（翻页模式的窗口收敛见 ReaderPagerControllerTest）：
 * 章段由「进入某章即物化相邻两章」产出、章界处只有标题项分隔、扁平 item 序号与 (章, 块)
 * 双向映射、锚点存语义值故上方插入 item 不动进度、哨兵落点在块数落定后才解析、
 * 保留集按**章距**裁剪（不是按块半径）、进度经与翻页模式同一个回调签名上报。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderScrollControllerTest {

    @Test
    fun `块总数在首个加载结果到达前为 0`() = runTest {
        val book = FakeScrollBook(listOf(3, 4))
        book.gates[ReaderPageKey(0, 0)] = CompletableDeferred()
        val controller = newScroll(book)

        controller.setInitData(0, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        advanceUntilIdle()

        assertEquals("排版未完成时块数未知，列表只画一个占位块而不是一堆空块", 0, controller.blockCountOf(0))

        book.gates.getValue(ReaderPageKey(0, 0)).complete(Unit)
        advanceUntilIdle()

        assertEquals(3, controller.blockCountOf(0))
    }

    @Test
    fun `进入一章即物化相邻两章`() = runTest {
        val book = FakeScrollBook(listOf(3, 4, 5))
        val controller = newScroll(book)

        controller.setInitData(1, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        advanceUntilIdle()

        assertEquals(
            "相邻章的探测块已被请求，块数随之落定，跨章滚动才不必在章界等排版",
            listOf(3, 4, 5),
            (0..2).map { controller.blockCountOf(it) },
        )
        assertTrue(book.requests.containsKey(ReaderPageKey(0, 0)))
        assertTrue(book.requests.containsKey(ReaderPageKey(2, 0)))
    }

    @Test
    fun `整本书首末不物化不存在的相邻章`() = runTest {
        val book = FakeScrollBook(listOf(3, 4))
        val controller = newScroll(book)

        controller.setInitData(0, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        advanceUntilIdle()
        assertNull("第一章之前没有章", book.requests[ReaderPageKey(-1, 0)])

        controller.setInitData(1, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        advanceUntilIdle()
        assertNull("最后一章之后没有章", book.requests[ReaderPageKey(2, 0)])
    }

    @Test
    fun `章界处的 item 顺序是本章末块直接接下一章标题`() = runTest {
        val book = FakeScrollBook(listOf(3, 4))
        val controller = newScroll(book)

        controller.setInitData(0, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        advanceUntilIdle()

        assertEquals("章 0 = 标题 + 3 块，章 1 = 标题 + 4 块", 9, controller.itemCount)
        assertEquals(ScrollItem.Title(0), controller.itemAt(0))
        assertEquals(ScrollItem.Block(0, 2), controller.itemAt(3))
        assertEquals("章与章之间只有标题分隔，没有「下一章」链接项", ScrollItem.Title(1), controller.itemAt(4))
        assertEquals(ScrollItem.Block(1, 0), controller.itemAt(5))
        assertEquals(ScrollItem.Block(1, 3), controller.itemAt(8))
        assertNull("列表尽头就是全书尽头，不再有额外项", controller.itemAt(9))
    }

    @Test
    fun `扁平序号与章块双向映射，标题项算该章第 0 块`() = runTest {
        val book = FakeScrollBook(listOf(3, 4))
        val controller = newScroll(book)

        controller.setInitData(0, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        advanceUntilIdle()

        controller.onScrolledToItem(4)
        assertEquals(1, controller.anchorChapter)
        assertEquals("标题项是该章的入口，进度按第 0 块记", 0, controller.anchorBlock)

        controller.onScrolledToItem(6)
        assertEquals(1, controller.anchorChapter)
        assertEquals(1, controller.anchorBlock)
        assertEquals(
            "映射必须可逆：容器要靠它把滚动落点换算回扁平序号",
            6,
            controller.flatIndexOf(ScrollItem.Block(1, 1)),
        )
        assertEquals(4, controller.flatIndexOf(ScrollItem.Title(1)))
        assertEquals("未物化的章没有扁平序号", -1, controller.flatIndexOf(ScrollItem.Title(9)))
    }

    @Test
    fun `锚点上方插入 item 时阅读位置与进度都不动`() = runTest {
        val book = FakeScrollBook(listOf(3, 4, 5))
        val progress = mutableListOf<Pair<Int, Int>>()
        // 上一章的探测先挂住：它展开成一串 item 的时刻发生在锚点已落定之后
        book.gates[ReaderPageKey(0, 0)] = CompletableDeferred()
        val controller = newScroll(book, onProgress = { c, p -> progress += c to p })

        controller.setInitData(1, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        advanceUntilIdle()
        val itemCountBefore = controller.itemCount
        val anchorIndexBefore = controller.flatIndexOf(ScrollItem.Block(1, 0))
        progress.clear()

        book.gates.getValue(ReaderPageKey(0, 0)).complete(Unit)
        advanceUntilIdle()

        assertEquals(
            "章 0 由「标题 + 1 个占位块」展开成「标题 + 3 块」，净增 2 项",
            itemCountBefore + 2,
            controller.itemCount,
        )
        assertEquals(
            "锚点的扁平序号确实随上方插入整体平移，故控制器必须存语义锚点而不是序号",
            anchorIndexBefore + 2,
            controller.flatIndexOf(ScrollItem.Block(1, 0)),
        )
        // LazyColumn 按 key 锚定，firstVisibleItemIndex 会整体平移；容器把平移后的序号报回来
        controller.onScrolledToItem(controller.flatIndexOf(ScrollItem.Block(1, 0)))

        assertEquals(1, controller.anchorChapter)
        assertEquals(0, controller.anchorBlock)
        assertEquals("锚点上方的插入不是一次阅读推进，不该产生进度", emptyList<Pair<Int, Int>>(), progress)
    }

    @Test
    fun `末块下一屏跨过标题项落到下一章章首`() = runTest {
        val book = FakeScrollBook(listOf(3, 4))
        val controller = newScroll(book)

        controller.setInitData(0, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        advanceUntilIdle()
        controller.onScrolledToItem(controller.flatIndexOf(ScrollItem.Block(0, 2)))
        advanceUntilIdle()

        controller.scrollOneScreen(forward = true)
        advanceUntilIdle()

        assertEquals("章末的下一屏是下一章，不是弹「没有下一页」", 1, controller.anchorChapter)
        assertEquals(0, controller.anchorBlock)
        assertEquals(
            "落点是下一章的标题项：标题矮于一屏，滚过去正好露出标题加下一章首块",
            controller.flatIndexOf(ScrollItem.Title(1)),
            controller.jump?.itemIndex,
        )
        assertEquals("点击/按键滚一屏是动画滚动，跳章才是瞬移", true, controller.jump?.animate)
    }

    @Test
    fun `首块上一屏落到上一章末块`() = runTest {
        val book = FakeScrollBook(listOf(3, 4))
        val controller = newScroll(book)

        controller.setInitData(1, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        advanceUntilIdle()
        assertEquals("跳章落在章首时锚点是标题项", ScrollItem.Title(1), controller.anchorItem)

        controller.scrollOneScreen(forward = false)
        advanceUntilIdle()

        assertEquals(0, controller.anchorChapter)
        assertEquals("回退跨过标题项落在上一章最后一块", 2, controller.anchorBlock)
    }

    @Test
    fun `标题项与该章首块之间来回滚不重复写进度`() = runTest {
        val book = FakeScrollBook(listOf(3, 4))
        val progress = mutableListOf<Pair<Int, Int>>()
        val controller = newScroll(book, onProgress = { c, p -> progress += c to p })

        controller.setInitData(0, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        advanceUntilIdle()
        assertEquals("跳章落章首时锚点是标题项", ScrollItem.Title(0), controller.anchorItem)

        controller.scrollOneScreen(forward = true)
        assertEquals(ScrollItem.Block(0, 0), controller.anchorItem)
        controller.scrollOneScreen(forward = false)
        advanceUntilIdle()

        assertEquals(
            "标题项与第 0 块是同一屏，来回滚一屏不该各写一次进度",
            listOf(0 to 0),
            progress,
        )
        assertEquals(ScrollItem.Title(0), controller.anchorItem)
    }

    @Test
    fun `整本书末尾再下一屏不推进也不发跳转`() = runTest {
        val book = FakeScrollBook(listOf(3))
        val controller = newScroll(book)

        controller.setInitData(0, 2)
        advanceUntilIdle()
        val tokenBefore = controller.jump?.token

        controller.scrollOneScreen(forward = true)
        advanceUntilIdle()

        assertEquals(0, controller.anchorChapter)
        assertEquals("整本书末尾不推进", 2, controller.anchorBlock)
        assertEquals("不推进就不该让容器再滚一次", tokenBefore, controller.jump?.token)
    }

    @Test
    fun `章末哨兵在块数落定后解析成末块并补报进度`() = runTest {
        val book = FakeScrollBook(listOf(3, 4))
        val progress = mutableListOf<Pair<Int, Int>>()
        book.gates[ReaderPageKey(1, 0)] = CompletableDeferred()
        val controller = newScroll(book, onProgress = { c, p -> progress += c to p })

        controller.setInitData(1, DBCode.BookContentView.DUR_PAGE_INDEX_END)
        advanceUntilIdle()
        assertEquals("块数未知时 END 无从解析，只能先落在章首", 0, controller.anchorBlock)

        book.gates.getValue(ReaderPageKey(1, 0)).complete(Unit)
        advanceUntilIdle()

        assertEquals(3, controller.anchorBlock)
        assertEquals(
            controller.flatIndexOf(ScrollItem.Block(1, 3)),
            controller.jump?.itemIndex,
        )
        assertEquals(
            "落点是块数落定后才算出来的，必须补报一次，否则冷启动重进回到章首",
            listOf(1 to 0, 1 to 3),
            progress,
        )
    }

    @Test
    fun `越界落点钳到章内`() = runTest {
        val book = FakeScrollBook(listOf(3, 4))
        val controller = newScroll(book)

        controller.setInitData(0, 99)
        advanceUntilIdle()

        assertEquals(2, controller.anchorBlock)
    }

    @Test
    fun `进度按章内块号经与翻页模式同一个回调上报`() = runTest {
        val book = FakeScrollBook(listOf(3, 4))
        val progress = mutableListOf<Pair<Int, Int>>()
        val controller = newScroll(book, onProgress = { c, p -> progress += c to p })

        controller.setInitData(0, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        advanceUntilIdle()
        controller.onScrolledToItem(controller.flatIndexOf(ScrollItem.Block(0, 1)))
        advanceUntilIdle()

        assertEquals(listOf(0 to 0, 0 to 1), progress)
    }

    @Test
    fun `块总数不因某块加载失败而改变`() = runTest {
        val book = FakeScrollBook(listOf(3, 4))
        book.fail(ReaderPageKey(0, 1))
        val controller = newScroll(book)

        controller.setInitData(0, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        advanceUntilIdle()
        controller.onScrolledToItem(controller.flatIndexOf(ScrollItem.Block(0, 1)))
        advanceUntilIdle()

        assertEquals("失败块仍占一屏高，滚动位置不塌陷", 3, controller.blockCountOf(0))
        assertEquals(ReaderPageUi.Error, controller.uiOf(0, 1))
    }

    @Test
    fun `往返滚动不重复请求已加载过的块`() = runTest {
        val book = FakeScrollBook(listOf(12))
        val controller = newScroll(book)

        controller.setInitData(0, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        advanceUntilIdle()
        (1..6).forEach {
            controller.onScrolledToItem(controller.flatIndexOf(ScrollItem.Block(0, it)))
            advanceUntilIdle()
        }
        (5 downTo 0).forEach {
            controller.onScrolledToItem(controller.flatIndexOf(ScrollItem.Block(0, it)))
            advanceUntilIdle()
        }

        // 回滚到读过的块不该再走一次加载：正文没变，重走一遍只是让 UI 闪一下加载态
        assertEquals(
            "回滚时这些块被重新请求了",
            emptySet<ReaderPageKey>(),
            book.requests.filterValues { it > 1 }.keys,
        )
    }

    @Test
    fun `块自请求兜住预取覆盖不到的块且不重复抓`() = runTest {
        val book = FakeScrollBook(listOf(12))
        val controller = newScroll(book)

        controller.setInitData(0, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        advanceUntilIdle()
        // 模拟 fling：滚动位置没报过第 9 块，它从未进过预取半径，只靠组合时自请求
        controller.ensureLoaded(0, 9)
        controller.ensureLoaded(0, 9)
        controller.ensureLoaded(0, 12)
        advanceUntilIdle()

        assertEquals("重复自请求被仓库的在途/已加载去重挡下", 1, book.requests[ReaderPageKey(0, 9)])
        assertTrue(controller.uiOf(0, 9) is ReaderPageUi.Loaded)
        assertNull("越界块号不发请求", book.requests[ReaderPageKey(0, 12)])
    }

    @Test
    fun `保留集按章距裁剪，远章正文被清而近章留着`() = runTest {
        val book = FakeScrollBook(listOf(4, 4, 4, 4, 4, 4))
        val controller = newScroll(book)

        controller.setInitData(0, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        advanceUntilIdle()
        // 逐章往前读：每进一章就物化它的相邻章，故章段一路累积、正文不能跟着累积
        (1..4).forEach { chapter ->
            controller.onScrolledToItem(controller.flatIndexOf(ScrollItem.Title(chapter)))
            advanceUntilIdle()
        }

        assertEquals("锚点章 ±2 之外的正文被清掉", ReaderPageUi.Loading, controller.uiOf(0, 0))
        assertTrue("近章正文留着，回滚不该闪加载态", controller.uiOf(2, 0) is ReaderPageUi.Loaded)
        assertTrue(controller.uiOf(4, 0) is ReaderPageUi.Loaded)
        assertEquals(
            "清的是正文不是章段：章段仍然连续，往回滚不必重新排版",
            listOf(0, 1, 2, 3, 4, 5),
            controller.materializedChapters,
        )
    }

    @Test
    fun `某章探测失败只让该章停在错误占位，相邻章照常物化`() = runTest {
        val book = FakeScrollBook(listOf(3, 4, 5))
        book.fail(ReaderPageKey(0, 0))
        val controller = newScroll(book)

        controller.setInitData(1, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        advanceUntilIdle()

        assertEquals("失败章的块数仍未知，列表里只占一个错误项", 0, controller.blockCountOf(0))
        assertEquals(ReaderPageUi.Error, controller.uiOf(0, 0))
        assertEquals("另一侧相邻章不受影响", 5, controller.blockCountOf(2))
        assertEquals(
            "章 0 = 标题 + 1 占位，章 1 = 标题 + 4 块，章 2 = 标题 + 5 块",
            2 + 5 + 6,
            controller.itemCount,
        )
    }

    @Test
    fun `item key 全局唯一`() = runTest {
        val book = FakeScrollBook(listOf(3, 4, 5))
        val controller = newScroll(book)

        controller.setInitData(1, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        advanceUntilIdle()
        val keys = (0 until controller.itemCount).map { controller.itemKey(it) }

        assertEquals(
            "重复 key 会让 LazyColumn 的按 key 锚定认到另一项，滚动位置静默跳变",
            keys.size,
            keys.toSet().size,
        )
    }
}

private fun TestScope.newScroll(
    book: FakeScrollBook,
    onProgress: (Int, Int) -> Unit = { _, _ -> },
) = ReaderScrollController(
    scope = this,
    context = ApplicationProvider.getApplicationContext<Context>(),
    chapterSize = { book.blocksPerChapter.size },
    chapterTitle = { "第${it + 1}章" },
    loadPage = { c, p -> book.load(c, p) },
    onProgress = onProgress,
)
