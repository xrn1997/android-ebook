package com.ebook.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ebook.common.ui.preview.AppPreview
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [MainBottomBar] 的渲染冒烟：把预览画的那几档状态在 JVM 上跑一遍并断言语义树。
 *
 * 分工照旧——预览面板看观感，这一层锁「渲染得出 + 高亮落在对的那一格 + 点下去报的是对的路由」。
 * 底部导航是无状态根（只吃 `selectedRoute` 与 `onSelect`），于是这三件事都能脱离
 * `MainScreen` 的 `BackHandler` / `NavHost`（两者在无人提供 owner 的组合环境里直接抛
 * `IllegalStateException`，整页预览不了）在 JVM 上验。
 *
 * 断言刻意不写中文文案，一律从 `R.string.title_*` 取——文案与资源 id 一旦漂移，
 * 硬编码的那份只会红得莫名其妙。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class MainBottomBarRenderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val bookshelf get() = composeRule.activity.getString(R.string.title_bookshelf)
    private val bookstore get() = composeRule.activity.getString(R.string.title_bookstore)
    private val me get() = composeRule.activity.getString(R.string.title_me)

    /**
     * 高亮跟路由走：命中哪一格就只有那一格是选中态。
     *
     * 传错路由（比如把 `Screen.Bookshelf.route` 写成常亮的）既不崩也不报错，只是用户停在书城、
     * 底栏却指着书架——编译检查抓不到，只有语义树里的 `Selected` 能抓。
     */
    @Test
    fun `路由命中书城时只有书城那一格是选中态`() {
        composeRule.setContent {
            AppPreview {
                MainBottomBar(selectedRoute = Screen.Bookstore.route, onSelect = {})
            }
        }

        composeRule.onNodeWithText(bookstore).assertIsSelected()
        composeRule.onNodeWithText(bookshelf).assertIsNotSelected()
        composeRule.onNodeWithText(me).assertIsNotSelected()
    }

    /**
     * 不知道在哪一档时三格都不高亮，而不是兜底点亮第一格。
     *
     * 这一档是首帧（回退栈还没有条目）与「路由不属于本页」的共用形态：
     * 亮第一格会让「高亮在书架、内容却是别的 Tab」的错位出现在屏幕上。
     */
    @Test
    fun `路由为 null 时三格都渲染但没有一格高亮`() {
        composeRule.setContent {
            AppPreview {
                MainBottomBar(selectedRoute = null, onSelect = {})
            }
        }

        composeRule.onNodeWithText(bookshelf).assertIsDisplayed().assertIsNotSelected()
        composeRule.onNodeWithText(bookstore).assertIsDisplayed().assertIsNotSelected()
        composeRule.onNodeWithText(me).assertIsDisplayed().assertIsNotSelected()
    }

    /**
     * 三格各自上报自己的路由，且组件自身不导航（这里没有 NavController，能报出正确的串就是全部职责）。
     *
     * 「点书架上报 bookstore」这类错位是真机上手感的问题（切过去的不是刚点的 Tab），
     * 而回退栈那四个选项只住在宿主的 `switchTab` 里（见 ADR-0041），本组件只负责报路由。
     */
    @Test
    fun `逐格点击按声明顺序上报各自的路由`() {
        val reported = mutableListOf<String>()
        composeRule.setContent {
            AppPreview {
                MainBottomBar(selectedRoute = null, onSelect = { reported.add(it) })
            }
        }

        composeRule.onNodeWithText(bookshelf).performClick()
        composeRule.onNodeWithText(bookstore).performClick()
        composeRule.onNodeWithText(me).performClick()

        assertEquals(
            listOf(Screen.Bookshelf.route, Screen.Bookstore.route, Screen.Me.route),
            reported
        )
    }
}
