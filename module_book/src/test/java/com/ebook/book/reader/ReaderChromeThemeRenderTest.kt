package com.ebook.book.reader

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
 * 阅读控制器（chrome 层）的深浅色渲染回归：顶栏与底栏的底色只能来自当前 `colorScheme`。
 *
 * 为什么要专门锁这一条：两条栏的底色曾被一页**固定浅色调板**钉死（原 View 版
 * `ll_menu_top` / `ll_menu_bottom` 硬编码 #ffffff，迁移时 1:1 移植了这份"始终全白"），
 * 暗环境下打开菜单就是一块刺眼的白。现在 chrome 层继承全局主题（外观主题模式三态），
 * 深色调板下必须翻成深色底；正文纸张色另属阅读背景主题层，不在此列。
 *
 * 为什么数像素而不是断言语义/布局：要防的失败形态正是"某个角色被写回常量"——
 * 写回之后布局、文案、语义全对，只有像素是白的，只有渲染能看见。
 *
 * 探针色：把 `surfaceContainer` 换成画面里不可能自然出现的两个颜色，命中即等于
 * "这一条栏确实按调板取色"；另一套调板的探针色必须零命中（窗口底色是纯白，
 * 拿真实的 M3 浅色 surface 做判据会和它撞色，故不能直接断言默认调板色值）。
 *
 * 取图走 `decorView.draw(Canvas)`：compose 的 captureToImage 依赖 PixelCopy + frame commit
 * 回调，Robolectric 的 paused looper 不驱动该回调，2s 后必抛超时。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ReaderChromeThemeRenderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** 变体开关：两套调板共用一次 setContent（重复 setContent 会抛 already set content） */
    private val darkChrome = mutableStateOf(false)

    @Composable
    private fun ChromeBars() {
        Column(modifier = Modifier.fillMaxSize()) {
            ReaderTopBar(
                title = "第一章",
                subtitle = "测试书",
                isLocalBook = false,
                onBack = {},
                onDownload = {},
                onRefresh = {},
                onSwitchSource = {},
                onComment = {}
            )
            Spacer(modifier = Modifier.weight(1f))
            ReaderBottomBar(
                chapterAll = 12,
                sliderValue = 3f,
                activePanel = ReaderPanel.NONE,
                onSliderChange = {},
                onSliderFinished = {},
                prevEnabled = true,
                nextEnabled = true,
                onPrevChapter = {},
                onNextChapter = {},
                onCatalog = {},
                onLight = {},
                onFont = {},
                onSetting = {}
            )
        }
    }

    @Test
    fun `控制器底色随深浅色调板翻转`(): Unit {
        composeRule.setContent {
            MaterialTheme(
                colorScheme = if (darkChrome.value) DarkChromeProbe else LightChromeProbe
            ) {
                ChromeBars()
            }
        }

        composeRule.waitForIdle()
        assertChromeFollowsScheme(expectLight = true)

        darkChrome.value = true
        composeRule.waitForIdle()
        assertChromeFollowsScheme(expectLight = false)
    }

    /**
     * 两条栏都要翻：探针色的外接矩形必须同时够到上四分之一与下四分之一，
     * 只画出一条栏（另一条仍写死）时它会落在同一侧。
     */
    private fun assertChromeFollowsScheme(expectLight: Boolean) {
        val canvas = captureDecor(composeRule.activity.window!!.decorView)
        val expected = if (expectLight) ProbeBarLight else ProbeBarDark
        val unexpected = if (expectLight) ProbeBarDark else ProbeBarLight
        val box = canvas.pixelBox(expected)
        val report = "当前为" + (if (expectLight) "浅" else "深") + "色调板，画布 " +
            canvas.width + "x" + canvas.height +
            "，探针色 " + box.describe() +
            "，另一调板探针 " + canvas.pixelBox(unexpected).describe()

        assertTrue("两条栏未按调板取色，探针色零命中。" + report, box.count >= MinChromePixels)
        assertTrue(
            "探针色纵向只覆盖 top=" + box.top + " bottom=" + box.bottom +
                "，顶栏与底栏没有同时翻色。" + report,
            box.top < canvas.height / 4 && box.bottom > canvas.height * 3 / 4
        )
        assertTrue("另一套调板的探针色漏了出来。" + report, canvas.pixelBox(unexpected).count == 0)
    }

    private companion object {
        /** 两套调板各自把 chrome 底色换成一眼可辨的探针色 */
        val ProbeBarLight = Color(0xFF2E9BD6)
        val ProbeBarDark = Color(0xFF7B3FA0)

        val LightChromeProbe = lightColorScheme(surfaceContainer = ProbeBarLight)
        val DarkChromeProbe = darkColorScheme(surfaceContainer = ProbeBarDark)

        /** 单条栏满铺即约 820x110 像素，取两栏合计的四分之一做下限，够咬住「只翻了一条」 */
        const val MinChromePixels = 30_000
    }
}
