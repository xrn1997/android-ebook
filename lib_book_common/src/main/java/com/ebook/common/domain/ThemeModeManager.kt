package com.ebook.common.domain

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 外观主题模式。
 *
 * 三态：浅色 / 深色 / 跟随系统。由用户在设置页选择，[ThemeModeManager] 持久化并暴露为 [StateFlow]，
 * [com.ebook.common.BookApplication] 在装配 [com.xrn1997.common.ui.theme.AppTheme] 时据此决定
 * `darkTheme` 入参，使整 App 的主题随用户选择立即生效。
 */
enum class ThemeMode {
    /** 强制浅色 */
    LIGHT,

    /** 强制深色 */
    DARK,

    /** 跟随系统深色状态（默认） */
    SYSTEM,
}

/**
 * 外观主题模式管理器。
 *
 * 职责：
 * - 把用户选择的 [ThemeMode] 持久化到 SharedPreferences（`theme_mode` 文件，单键 `mode`）
 * - 以 [StateFlow] 暴露当前值，供 [com.ebook.common.BookApplication] 在主题装配点读取
 * - 提供 [setThemeMode] 写入入口，由设置页 ViewModel 调用
 *
 * 默认值为 [ThemeMode.SYSTEM]，与 Compose 的 `isSystemInDarkTheme()` 默认语义一致，
 * 也与 [com.xrn1997.common.ui.theme.MyApplicationTheme] 的默认 `darkTheme = isSystemInDarkTheme()` 对齐。
 *
 * 单例：Hilt 在 [com.ebook.common.di.ThemeModule] 绑定到 [SingletonComponent]；
 * [com.ebook.common.BookApplication] 通过 Hilt entry point 取出后调用 [installIntoCompanion]
 * 挂到 [Companion.instance]，主题装配 lambda 在 Compose 重组时经此读取（lambda 在 Composable
 * 作用域外调用，无法走 Hilt 注入）。
 */
class ThemeModeManager(
    private val application: Application,
) {
    private val sp by lazy {
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _themeMode = MutableStateFlow(loadMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /**
     * 更新主题模式并持久化。
     *
     * 写入后立即推进 [_themeMode]，主题装配 lambda 通过 `collectAsState` 观察到变化并触发重组，
     * 整 App 的主题配色随之切换。
     */
    fun setThemeMode(mode: ThemeMode) {
        sp.edit { putString(KEY_MODE, mode.name) }
        _themeMode.value = mode
    }

    private fun loadMode(): ThemeMode =
        runCatching {
            val name = sp.getString(KEY_MODE, null) ?: return ThemeMode.SYSTEM
            runCatching { ThemeMode.valueOf(name) }.getOrDefault(ThemeMode.SYSTEM)
        }.getOrDefault(ThemeMode.SYSTEM)

    /**
     * 由 [com.ebook.common.BookApplication] 在 Hilt 注入完成后调用，把本实例挂到伴生对象，
     * 供主题装配 lambda 读取（lambda 在 Compose 重组时调用，无法走 Hilt 注入）。
     */
    fun installIntoCompanion() {
        instance = this
    }

    companion object {
        private const val PREFS_NAME = "theme_mode"
        private const val KEY_MODE = "mode"

        /**
         * 主题装配 lambda 的读取入口。[com.ebook.common.BookApplication.onCreate] 完成后赋值；
         * 在此之前（理论上不会出现：主题装配只在 `setContent` 内调用，晚于 Application.onCreate）
         * 返回 null，调用方按「跟随系统」兜底。
         */
        @Volatile
        var instance: ThemeModeManager? = null
            private set
    }
}
