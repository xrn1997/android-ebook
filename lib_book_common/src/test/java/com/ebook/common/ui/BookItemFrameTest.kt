package com.ebook.common.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.height
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [BookItemFrame] / [BookItemMetaRow] 的契约回归。这一族由**书架与找书两个模块**共用
 * （原先各有一份同构实现，见 ADR-0042 一族的共享件口径），故契约只能在本模块钉住：
 * 两个调用方都看不见对方的漂移，改了这里就是改了全仓这一族的卡片形态。
 *
 * 两条要防的错都不崩不报错，只在渲染时显形：
 * - 动作槽的有无改了卡片高度 → 两个列表同屏时参差；
 * - 动作槽为 null 还渲染出内容 → 多一个点不动/不该有的东西。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class BookItemFrameTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * 等高是这一族的硬约束：右上角有没有动作槽**不改变卡片高度**。
     * 顺带钉住绝对高度——列高 76dp（[BookItemLayout.columnHeight]）+ 上下内边距各 10dp = 96dp，
     * 两个模块的卡片都取这一档，改档就该先在这里红。
     */
    @Test
    fun `动作槽的有无不改变卡片高度`() {
        val showAction = mutableStateOf(true)
        val action: (@Composable BoxScope.() -> Unit)? = {
            Text(text = "动作", modifier = Modifier.align(Alignment.TopEnd).clickable { })
        }
        composeRule.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                BookItemFrame(
                    coverUrl = "",
                    coverDescription = null,
                    title = "山海拾遗",
                    action = if (showAction.value) action else null,
                ) {
                    BookItemMetaRow("笔墨三石", "共 800 章")
                }
            }
        }

        composeRule.waitForIdle()
        val withAction = composeRule.onRoot().getUnclippedBoundsInRoot().height
        showAction.value = false
        composeRule.waitForIdle()
        val withoutAction = composeRule.onRoot().getUnclippedBoundsInRoot().height

        assertEquals(
            "卡片高度随动作槽有无而变（$withAction → $withoutAction）",
            withAction.value,
            withoutAction.value,
            0.5f
        )
        assertEquals(
            "卡片高度不是列高 76dp + 上下各 10dp",
            96f,
            withAction.value,
            0.5f
        )
    }

    /**
     * 动作槽为 null 时那一块整个不渲染；给定时必须可点。
     * 「给不给」是编译期过得去、运行期静默失效的写法（少画一个入口/多画一个假入口），只能在这里拦。
     */
    @Test
    fun `动作槽为 null 时不渲染，给定时可点`() {
        var clicked = 0
        val showAction = mutableStateOf(false)
        val action: (@Composable BoxScope.() -> Unit)? = {
            Text(
                text = "动作",
                modifier = Modifier.align(Alignment.TopEnd).clickable { clicked++ }
            )
        }
        composeRule.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                BookItemFrame(
                    coverUrl = "",
                    coverDescription = null,
                    title = "山海拾遗",
                    action = if (showAction.value) action else null,
                ) {
                    BookItemMetaRow("笔墨三石", "共 800 章")
                }
            }
        }

        composeRule.onNodeWithText("动作").assertDoesNotExist()
        showAction.value = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("动作").assertIsDisplayed().performClick()
        assertEquals("动作槽点击未回调", 1, clicked)
    }

    /**
     * 底行右列为空串时不留空槽——与仓里「空字段不渲染槽位」的既有口径一致
     * （书架未读完时的「共 N 章」、找书条目缺书源时的那一格都靠它）。
     */
    @Test
    fun `底行右列为空串时不渲染`() {
        composeRule.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                BookItemMetaRow("笔墨三石", "")
            }
        }

        composeRule.onNodeWithText("笔墨三石").assertIsDisplayed()
        composeRule.onNodeWithText("共 800 章").assertDoesNotExist()
    }
}
