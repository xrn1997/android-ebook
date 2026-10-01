package com.ebook.common.ui

import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.height
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [EmptyState] 的契约回归。组件在 lib_book_common，故测试也放这里——共享件的可省字段与
 * 动作槽语义一旦漂移，全仓十几个空态/失败态会一起歪，只有在本模块才能把契约钉住。
 *
 * 这些用例刻意不用像素探针（`module_book` 的 `DownloadQueueActionRenderTest` 走像素，是另一类目标）：
 * 这里要防的不是"画出来被裁掉"，而是**该渲染的没渲染 / 不该渲染的渲染了**，
 * 语义树与几何量比像素更直接。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class EmptyStateTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `主副文案与动作槽一起渲染，点击动作回调生效`() {
        var clicked = 0
        composeRule.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                EmptyState(
                    icon = Icons.Filled.Warning,
                    title = "加载失败",
                    hint = "网络好像不太稳定",
                    actionText = "重试",
                    onAction = { clicked++ }
                )
            }
        }

        composeRule.onNodeWithText("加载失败").assertIsDisplayed()
        composeRule.onNodeWithText("网络好像不太稳定").assertIsDisplayed()
        composeRule.onNodeWithText("重试").performClick()

        assertEquals("动作槽点击未回调", 1, clicked)
    }

    /**
     * 只给文案不给回调时必须整块不渲染：渲染出来就是一个点不动的按钮。
     * （调用方漏传 onAction 是编译期过得去、运行期静默失效的写法，只能在这里拦。）
     */
    @Test
    fun `只给动作文案不给回调时不渲染动作`() {
        composeRule.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                EmptyState(
                    icon = Icons.Filled.Warning,
                    title = "书架还是空的",
                    actionText = "去书城找书"
                )
            }
        }

        composeRule.onNodeWithText("书架还是空的").assertIsDisplayed()
        composeRule.onNodeWithText("去书城找书").assertDoesNotExist()
    }

    /**
     * 副文案为空串时不留空槽——与仓里「空字段不渲染槽位」的既有口径一致。
     * 用高度差判定：多一行 bodySmall 必然更高，直接比总高即可，不必猜字号。
     */
    @Test
    fun `副文案为空串时不留空槽`() {
        val withHint = mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                EmptyState(
                    icon = Icons.Filled.Warning,
                    title = "书架还是空的",
                    hint = if (withHint.value) "去书城挑一本" else ""
                )
            }
        }

        composeRule.waitForIdle()
        val tall = composeRule.onRoot().getUnclippedBoundsInRoot().height
        withHint.value = false
        composeRule.waitForIdle()
        val short = composeRule.onRoot().getUnclippedBoundsInRoot().height

        assertTrue(
            "去掉副文案后高度没有变化（$tall → $short），说明空串仍占了槽位",
            short < tall
        )
    }
}
