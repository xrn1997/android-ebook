package com.ebook.common.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [CommonListItem] 尾部值的契约回归（本模块是全仓共享 UI 组件的唯一产地，
 * 共享件契约只能在组件自己的模块里锁住——两个调用方都看不见对方的漂移）。
 *
 * 尾部值是外部数据（昵称、自定义主机名、缓存大小…）。Row 里不带 weight 的子项先按
 * 「剩余全宽」测量：不加限宽的话长值照样把带 weight(1f) 的标题压到最小内在宽度，
 * 标题逐字换行、行高失控。要钉的两件事都不崩不报错，只在渲染时显形：
 * - 超宽尾部值被限在 [CommonUiTokens.listItemTrailingMaxWidth] 内展示（超出省略）；
 * - 尾部值长短**不改变行高**——标题没被挤垮的可观察形态。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class CommonListItemTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `超宽尾部值限宽展示且不抬高行高`() {
        val longValue = "http://192.168.100.200:9090/a-suffix-long-enough-to-overflow"
        composeRule.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Column {
                    CommonListItem(
                        icon = Icons.Filled.Settings,
                        title = "缓存大小",
                        iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        trailingText = "12.3 MB",
                        modifier = Modifier.testTag("shortRow"),
                        onClick = {},
                    )
                    CommonListItem(
                        icon = Icons.Filled.Settings,
                        title = "服务器地址",
                        iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        trailingText = longValue,
                        modifier = Modifier.testTag("longRow"),
                        onClick = {},
                    )
                }
            }
        }

        // 行的 clickable 会合并子节点语义，量尾部值的真实布局宽窄必须走未合并树
        val trailing = composeRule.onNodeWithText(longValue, useUnmergedTree = true)
        trailing.assertIsDisplayed()
        val trailingWidth = trailing.getUnclippedBoundsInRoot().width
        assertTrue(
            "超宽尾部值必须限宽在 ${CommonUiTokens.listItemTrailingMaxWidth} 内（实际 $trailingWidth）",
            trailingWidth <= CommonUiTokens.listItemTrailingMaxWidth,
        )

        // 标题仍占得到自己的位置（没被长值挤出同行）
        composeRule.onNodeWithText("服务器地址").assertIsDisplayed()
        val shortRow = composeRule.onNodeWithTag("shortRow").getUnclippedBoundsInRoot().height
        val longRow = composeRule.onNodeWithTag("longRow").getUnclippedBoundsInRoot().height
        assertEquals(
            "尾部值长短不得挤高行高（短值行 $shortRow → 超宽值行 $longRow）",
            shortRow.value,
            longRow.value,
            0.5f,
        )
    }
}
