package com.ebook.common

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import com.ebook.common.domain.ThemeMode
import com.ebook.common.domain.ThemeModeManager
import com.ebook.source.sandbox.SandboxProcess
import com.xrn1997.common.BaseApplication
import com.xrn1997.common.ui.theme.AppTheme
import com.xrn1997.common.ui.theme.MyApplicationTheme
import com.xrn1997.common.util.Logger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

open class BookApplication : BaseApplication() {
    override fun onCreate() {
        super.onCreate()
        // 进程门：隔离进程里框架仍会实例化本类（ActivityThread 在隔离进程上只跳过 setupGraphicsSupport、
        // TrafficStats.init 与字体预加载，不跳过 makeApplicationInner）。下面两件事在沙箱里既没用处也做不成：
        // 主题装配不为一个不画 UI 的进程服务，而 ThemeModeManager 的持久化要读 SharedPreferences——
        // 隔离进程读不到本应用的数据目录，留它就是一次纯粹的 IO 失败与一串看不懂的日志。
        // 反过来，主进程这一侧绝不能被误判跳过：那等于整个 App 起来但没初始化。
        if (SandboxProcess.isInIsolatedProcess) {
            Logger.i(TAG, "沙箱进程：跳过应用级初始化")
            return
        }
        // 主题装配点接入（lib_common 侧 AppTheme 装配约定）：装配 MyApplicationTheme，
        // 深色模式按 ThemeModeManager 的持久化选择决定（浅色 / 深色 / 跟随系统）。
        // 品牌色策略待产品决策——将来固定品牌色只需替换 dynamicColor，页面零改动
        AppTheme.install { content ->
            val manager = ThemeModeManager.instance
            val modeState = manager?.themeMode?.collectAsState()
            val mode = modeState?.value ?: ThemeMode.SYSTEM
            val darkTheme = when (mode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            MyApplicationTheme(darkTheme = darkTheme, content = content)
        }
        // Hilt 注入完成后把 ThemeModeManager 挂到伴生对象，供上方 lambda 在 Compose 重组时读取
        val themeModeManager = EntryPointAccessors.fromApplication(
            applicationContext,
            ThemeModeManagerEntryPoint::class.java,
        ).themeModeManager()
        themeModeManager.installIntoCompanion()
    }

    /**
     * Hilt entry point：BookApplication 不走 @HiltAndroidApp 注入路径，
     * 用 [EntryPointAccessors] 从 [SingletonComponent] 取出 [ThemeModeManager] 单例。
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ThemeModeManagerEntryPoint {
        fun themeModeManager(): ThemeModeManager
    }

    private companion object {
        const val TAG = "BookApplication"
    }
}
