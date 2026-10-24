package com.ebook.book.reader

import android.app.Activity
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ReaderBrightness] 的回归：SP 口径、跟随系统与手动亮度的窗口写入。
 *
 * 这段逻辑原先长在 `ReaderPanels.kt` 的亮度面板里（SP 键、系统亮度兜底、窗口换算三件事
 * 全绑在壳层 Composable 上），没有测试；抽成 module 后在这里锁住「关闭再进入阅读器亮度还在」
 * 与「跟随系统时窗口交还系统」两条用户可见行为。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderBrightnessTest {

    private lateinit var activity: Activity

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    @Test
    fun `从未设置过时默认跟随系统`() {
        val state = ReaderBrightness.load(activity)

        assertTrue(state.followSystem)
    }

    @Test
    fun `保存后原样读回`() {
        ReaderBrightness.save(activity, ReaderBrightness.State(light = 120, followSystem = false))

        val state = ReaderBrightness.load(activity)

        assertFalse(state.followSystem)
        assertEquals("light 是原样落盘的", 120, state.light)
    }

    @Test
    fun `恢复手动亮度时写进窗口`() {
        ReaderBrightness.save(activity, ReaderBrightness.State(light = 128, followSystem = false))

        ReaderBrightness.restore(activity)

        assertEquals(128 / 255f, activity.window.attributes.screenBrightness, 0.001f)
    }

    @Test
    fun `跟随系统时窗口亮度交还系统`() {
        ReaderBrightness.save(activity, ReaderBrightness.State(light = 64, followSystem = true))

        ReaderBrightness.restore(activity)

        assertEquals(
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
            activity.window.attributes.screenBrightness,
            0.001f,
        )
    }

    @Test
    fun `越界亮度钳到 0 到 255`() {
        ReaderBrightness.applyToWindow(activity, 999)
        assertEquals(1f, activity.window.attributes.screenBrightness, 0.001f)

        ReaderBrightness.applyToWindow(activity, -5)
        assertEquals(0f, activity.window.attributes.screenBrightness, 0.001f)
    }
}
