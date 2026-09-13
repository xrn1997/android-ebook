package com.ebook.book.reader

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 翻页方式单选行的深浅色渲染回归：图标块底色只能来自当前 `colorScheme`。
 *
 * 与 ReaderChromeThemeRenderTest 防的是同一种失败形态（某个颜色角色被写回常量），
 * 判据同一套：把 `primaryContainer`/`secondaryContainer` 换成画面里不可能自然出现的
 * 探针色，命中即等于「这一行确实按调板取色」，另一套调板的探针色必须零命中。
 *
 * 数像素而不断言布局/文案的理由同前：写回常量之后布局、文案、语义全对，只有像素是错的。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ReaderTurnModeRowRenderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** 变体开关：两套调板共用一次 setContent（重复 setContent 会抛 already set content） */
    private val darkChrome = mutableStateOf(false)

    @Composable
    private fun TurnModeRows() {
        Column(modifier = Modifier.fillMaxSize()) {
            PanelChoiceRow(
                icon = Icons.Outlined.SwapHoriz,
                label = "左右翻页",
                description = "横向滑动，一屏一屏地翻",
                selected = true,
                onSelect = {},
                iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            PanelChoiceRow(
                icon = Icons.Outlined.SwapVert,
                label = "上下滚屏",
                description = "竖向连续滚动，手指滚到哪读到哪",
                selected = false,
                onSelect = {},
                iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                iconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }

    @Test
    fun `翻页方式单选行的图标块底色随深浅色调板翻转`(): Unit {
        composeRule.setContent {
            MaterialTheme(
                colorScheme = if (darkChrome.value) DarkProbe else LightProbe
            ) {
                TurnModeRows()
            }
        }

        composeRule.waitForIdle()
        assertRowsFollowScheme(expectLight = true)

        darkChrome.value = true
        composeRule.waitForIdle()
        assertRowsFollowScheme(expectLight = false)
    }

    /**
     * 两行都要翻：两个探针色各自的命中数都必须过下限。
     * 只翻一行（另一行写死）时对应探针色零命中。
     */
    private fun assertRowsFollowScheme(expectLight: Boolean) {
        val bitmap = captureDecor(composeRule.activity.window!!.decorView)
        val primary = if (expectLight) ProbePrimaryLight else ProbePrimaryDark
        val secondary = if (expectLight) ProbeSecondaryLight else ProbeSecondaryDark
        val foreign = if (expectLight) {
            listOf(ProbePrimaryDark, ProbeSecondaryDark)
        } else {
            listOf(ProbePrimaryLight, ProbeSecondaryLight)
        }
        val report = "当前为" + (if (expectLight) "浅" else "深") + "色调板，画布 " +
            bitmap.width + "x" + bitmap.height +
            "，左右翻页行 " + bitmap.pixelBox(primary).describe() +
            "，上下滚屏行 " + bitmap.pixelBox(secondary).describe()

        assertTrue(
            "左右翻页行未按调板取色，探针色零命中。" + report,
            bitmap.pixelBox(primary).count >= MinIconPixels
        )
        assertTrue(
            "上下滚屏行未按调板取色，探针色零命中。" + report,
            bitmap.pixelBox(secondary).count >= MinIconPixels
        )
        foreign.forEach {
            assertTrue("另一套调板的探针色漏了出来。" + report, bitmap.pixelBox(it).count == 0)
        }
    }

    private companion object {
        val ProbePrimaryLight = Color(0xFF2E9BD6)
        val ProbeSecondaryLight = Color(0xFF3FA07B)
        val ProbePrimaryDark = Color(0xFF7B3FA0)
        val ProbeSecondaryDark = Color(0xFFA07B3F)

        val LightProbe = lightColorScheme(
            primaryContainer = ProbePrimaryLight,
            secondaryContainer = ProbeSecondaryLight,
        )
        val DarkProbe = darkColorScheme(
            primaryContainer = ProbePrimaryDark,
            secondaryContainer = ProbeSecondaryDark,
        )

        /** 单个 36dp 图标块在 xhdpi 下约 72x72px，取其一半做下限，够咬住「图标块没画出来」 */
        const val MinIconPixels = 2_500
    }
}
