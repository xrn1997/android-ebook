package com.ebook.book.reader

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.ebook.db.event.DBCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 上下滚屏容器的**章界形态**回归：跨章是一条连续列表，不是一次「切章」。
 *
 * 控制器侧的模型（章段物化、扁平序号映射、锚点存语义值）由 ReaderScrollControllerTest 锁；
 * 这里锁的是容器把它画成什么——几件自动化够得到、且一旦回归就正好是用户报的那个症状的事：
 * 章末块的下一项就是下一章标题、下一章正文确实在同一条列表里、不再有「上一章/下一章」链接项、
 * 首次落点应用之前不把进度写到列表首项那一章、块高未知时占位不塌陷。
 *
 * 块高刻意给得矮（100dp），让两章 7 项全部落进视口：断言的是**列表结构**而不是滚动行为。
 * 滚动位置在「锚点上方插入 item」时是否保持，靠的是 LazyColumn 的按 key 锚定（平台行为，
 * 见 ReaderScrollController 类 KDoc），留给装机验证。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ReaderScrollSeamlessTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `本章末块之下直接接下一章标题与正文，且没有章界链接项`() {
        val book = FakeScrollBook(listOf(3, 2))
        val controller = newController(book)

        composeRule.setContent { ScrollBody(controller) }
        composeRule.waitForIdle()

        controller.setInitData(0, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        composeRule.waitForIdle()

        // 两章的内容都在同一条列表里，且都画出来了
        listOf("第1章", "正文 0-0", "正文 0-2", "第2章", "正文 1-0", "正文 1-1").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }

        // 章界处只有标题分隔：下一章标题在本章末块之下、下一章首块又在标题之下
        val lastBlockBottom = composeRule.onNodeWithText("正文 0-2").getUnclippedBoundsInRoot().bottom
        val nextTitleTop = composeRule.onNodeWithText("第2章").getUnclippedBoundsInRoot().top
        val nextBlockTop = composeRule.onNodeWithText("正文 1-0").getUnclippedBoundsInRoot().top
        assertTrue(
            "下一章标题没有接在本章末块之下（末块底 $lastBlockBottom，标题顶 $nextTitleTop）",
            nextTitleTop >= lastBlockBottom,
        )
        assertTrue(
            "下一章首块没有接在它的标题之下（标题顶 $nextTitleTop，首块顶 $nextBlockTop）",
            nextBlockTop > nextTitleTop,
        )

        // 跨章已经由列表本身承担，章界链接项是多余的第二个入口
        composeRule.onAllNodes(hasText("下一章", substring = true)).assertCountEquals(0)
        composeRule.onAllNodes(hasText("上一章", substring = true)).assertCountEquals(0)
    }

    @Test
    fun `容器重新进入组合时不把进度写到列表首项那一章`() {
        val book = FakeScrollBook(listOf(3, 4, 5))
        val progress = mutableListOf<Pair<Int, Int>>()
        val controller = newController(book) { c, p -> progress += c to p }

        // 模拟「切到左右翻页再切回上下滚屏」：控制器仍持有上一次的章段，
        // 而容器是全新的 LazyListState，首帧 firstVisibleItemIndex 恒为 0——
        // 0 号项是最早物化那一章（章 0）的标题，照报就会把进度写成 (0, 0)
        controller.setInitData(1, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        progress.clear()

        composeRule.setContent { ScrollBody(controller) }
        composeRule.waitForIdle()

        assertEquals("首次落点应用之前不回报滚动位置", 1, controller.anchorChapter)
        assertEquals(emptyList<Pair<Int, Int>>(), progress)
        composeRule.onNodeWithText("正文 1-0").assertIsDisplayed()
    }

    @Test
    fun `块高未知时画加载占位而不是空白`() {
        val book = FakeScrollBook(listOf(3))
        val controller = newController(book)

        composeRule.setContent {
            // 排版还没落定时 rePaginate 给不出块高，容器不得因此把加载态压成 0 高
            ScrollBody(controller, blockHeightPx = 0)
        }
        composeRule.waitForIdle()

        controller.setInitData(0, DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("第1章").assertIsDisplayed()
        composeRule.onNodeWithText("正文 0-0").assertIsDisplayed()
        val blockBounds = composeRule.onNodeWithText("正文 0-0").getUnclippedBoundsInRoot()
        val blockHeight = blockBounds.bottom - blockBounds.top
        assertTrue("块高未知时占位块塌成了 0 高（实际 $blockHeight）", blockHeight >= 1.dp)
    }

    private fun newController(
        book: FakeScrollBook,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ) = ReaderScrollController(
        scope = scope,
        context = ApplicationProvider.getApplicationContext<Context>(),
        chapterSize = { book.blocksPerChapter.size },
        chapterTitle = { "第${it + 1}章" },
        loadPage = { c, p -> book.load(c, p) },
        onProgress = onProgress,
    )

    @Composable
    private fun ScrollBody(controller: ReaderScrollController, blockHeightPx: Int = 200) {
        ReaderScroll(
            controller = controller,
            textColor = Color.Black,
            bgColor = Color.White,
            textSizeSp = 12f,
            lineHeight = 14.sp,
            blockHeightPx = blockHeightPx,
            canClickTurn = false,
            onCenterTap = {},
            onViewportSizeChanged = { _, _ -> },
        )
    }
}
