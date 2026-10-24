package com.ebook.book.reader

import android.app.Activity
import android.content.Context
import android.provider.Settings
import android.view.WindowManager
import androidx.core.content.edit
import com.xrn1997.common.util.Logger

/**
 * 阅读器亮度的唯一主人：SP 口径、系统亮度兜底、窗口亮度换算。
 *
 * 原先这三件事与亮度面板的 Composable 绑在同一个文件里（SP 键、`getSystemBrightness`、
 * `setWindowBrightness` 全是文件私有），于是「关闭再进入阅读器亮度还在不在」只能靠装机试。
 * 现在 [State] 是纯值、读写与窗口写入各有单一入口，面板只负责滑条与开关的长相。
 *
 * 单位：`light` 是 0..255（与 SP 里落盘的历史值同单位），窗口要的是 0f..1f，
 * 换算只在这里做一次。窗口亮度不跨 Activity 生命周期，故每次进入阅读器都要 [restore]。
 */
internal object ReaderBrightness {

    private const val SP_NAME = "CONFIG"
    private const val KEY_LIGHT = "light"
    private const val KEY_FOLLOW_SYS = "is_follow_sys"
    private const val TAG = "ReaderBrightness"

    private const val MIN_LIGHT = 0
    private const val MAX_LIGHT = 255

    /** 面板的单一事实源：手动亮度 + 是否跟随系统。 */
    data class State(val light: Int, val followSystem: Boolean)

    /** 读当前配置；从未设置过时跟随系统、亮度取系统值。 */
    fun load(context: Context): State {
        val preferences = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        return State(
            light = preferences.getInt(KEY_LIGHT, systemBrightness(context)),
            followSystem = preferences.getBoolean(KEY_FOLLOW_SYS, true),
        )
    }

    /** 关闭面板时落盘；键与原实现一致，升级无感。 */
    fun save(context: Context, state: State) {
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE).edit {
            putInt(KEY_LIGHT, state.light)
                .putBoolean(KEY_FOLLOW_SYS, state.followSystem)
        }
    }

    /** 读取系统亮度（0..255）；读取失败兜底 0（对齐原 WindowLightPop.screenBrightness）。 */
    fun systemBrightness(context: Context): Int = try {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    } catch (e: Settings.SettingNotFoundException) {
        Logger.w(TAG, "系统亮度设置不存在，兜底 0", e)
        0
    }

    /**
     * 把手动亮度写进当前窗口（不持久化）。
     *
     * 越界值钳到 0..255：滑条给的是合法区间，但恢复路径的值来自 SP，脏数据不该把窗口写成
     * 负亮度或亮到发白。
     */
    fun applyToWindow(activity: Activity, light: Int) {
        val params = activity.window.attributes
        params.screenBrightness = light.coerceIn(MIN_LIGHT, MAX_LIGHT) / MAX_LIGHT.toFloat()
        activity.window.attributes = params
    }

    /** 把窗口亮度交还系统（BRIGHTNESS_OVERRIDE_NONE）。 */
    fun followSystem(activity: Activity) {
        val params = activity.window.attributes
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        activity.window.attributes = params
    }

    /**
     * 进入阅读器时恢复已持久化的手动亮度；跟随系统时窗口默认即 OVERRIDE_NONE。
     *
     * 不恢复的症状是「调了亮度但不生效」：用户设过手动亮度后，下次进入阅读器仍是系统亮度。
     */
    fun restore(activity: Activity) {
        val state = load(activity)
        if (state.followSystem) {
            followSystem(activity)
        } else {
            applyToWindow(activity, state.light)
        }
    }
}
