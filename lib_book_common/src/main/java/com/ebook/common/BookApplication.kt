package com.ebook.common

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import com.ebook.api.config.ServerAddressStore
import com.ebook.common.domain.ThemeMode
import com.ebook.common.domain.ThemeModeManager
import com.ebook.common.domain.UserSessionManager
import com.ebook.source.sandbox.SandboxProcess
import com.xrn1997.common.BaseApplication
import com.xrn1997.common.ui.theme.AppTheme
import com.xrn1997.common.ui.theme.MyApplicationTheme
import com.xrn1997.common.util.Logger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

private const val TAG = "BookApplication"

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
        // 启动兜底：换址/清除配置在盘上留下 session_reset_pending。设置页的 NonCancellable
        // 只保住本进程内的「写盘 → 清会话」，而协程在 IO 提交期间被取消、进程被杀、
        // 重启前在旧地址重新登录这三条窗口只能在这里补清。必须赶在首个请求之前：
        // 「首个请求 A0230 静默刷新」会拿旧地址的 refresh token 去新主机换凭证。
        // 顺序载荷：applyPendingAddressSessionReset 先清会话、后清标记（见其 KDOC）。
        // 会话管理器以 provider 传入：绝大多数启动根本没有标记，不该为此在主线程建出
        // AndroidUserSessionManager 及其 ProfileRepository/TokenHolder 与两次 SP 落盘。
        // 失败不阻断启动：标记留在盘上，下次启动重试。异常若从 onCreate 逃逸会形成
        // 「每次启动都崩在同一处、标记还没被消费」的启动崩溃循环。
        try {
            val sessionEntryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                ServerAddressSessionEntryPoint::class.java,
            )
            applyPendingAddressSessionReset(sessionEntryPoint.serverAddressStore()) {
                sessionEntryPoint.userSessionManager()
            }
        } catch (e: Exception) {
            // 只兜业务/IO 异常，不吞 Error（OOM/LinkageError 该按原样暴露）
            Logger.w(TAG, "换址兜底清会话失败，保留标记待下次启动重试", e)
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

    /**
     * Hilt entry point：启动兜底要用的两个单例。
     *
     * 与主题装配同一形态（用 [EntryPointAccessors] 取），不在 Application 上挂 eager `@Inject`
     * 字段——`:js` 进程会在 `super.onCreate()` 内完成字段注入，重对象不该跟着每个进程一起建。
     * [userSessionManager] 因此只在确有 [ServerAddressStore.hasSessionResetPending] 时才取。
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ServerAddressSessionEntryPoint {
        fun serverAddressStore(): ServerAddressStore
        fun userSessionManager(): UserSessionManager
    }
}

/**
 * 启动兜底：发现「地址已改、旧地址的会话尚未清」的落盘标记时，清掉会话并消费标记。
 *
 * **顺序载荷：先 [UserSessionManager.clearSession]，后 [ServerAddressStore.clearSessionResetPending]。**
 * 若进程死在两步之间，标记仍在，下次启动会再清一次（清会话幂等）；反过来先清标记，
 * 中途死亡就再也不会有人来清那份旧 refresh token——它会在重启后被发往新主机。
 *
 * **落盘失败不消费标记**：`clearSession()` 返回 false（两处 SP 的 commit 失败）或抛异常时，
 * 内存态虽已清空，盘上仍可能留着旧 refresh token；标记必须留给下次启动重试。返回 false
 * 只表示「本次没补干净」，不是启动失败，调用方不得据此中止启动。
 *
 * [sessionManager] 是 provider 而不是实例：没有标记的启动（绝大多数）不该为这件事把整个会话
 * 对象图（含 ProfileRepository/TokenHolder 与两次 SP 落盘）建到主线程上，判据只留 store 那一份
 * 极小的 SP 读。
 *
 * 提取为 top-level internal 是为让顺序、失败保留标记与「无标记不清」都能在 JVM 下断言，
 * 不必启动整个 Application。
 *
 * 清会话的同步 `commit` 发生在主线程——这是有意取舍：本函数必须赶在首个请求之前跑完
 * （慢一点也比让旧 refresh token 先发出去强），且触发条件极罕见（仅换址后的下次启动命中）。
 * 其余调用点的线程契约见 `AndroidUserSessionManager.clearSession`。
 */
internal fun applyPendingAddressSessionReset(
    store: ServerAddressStore,
    sessionManager: () -> UserSessionManager,
): Boolean {
    if (!store.hasSessionResetPending()) return true

    val cleared = try {
        sessionManager().clearSession()
    } catch (e: Exception) {
        Logger.w(TAG, "启动补清旧地址会话失败，保留标记待下次启动重试", e)
        false
    }
    if (!cleared) {
        Logger.w(TAG, "旧地址会话未确认清干净，保留标记待下次启动重试")
        return false
    }

    if (!store.clearSessionResetPending()) {
        Logger.w(TAG, "换址标记清除未落盘，下次启动将重复清理（幂等）")
        return false
    }
    Logger.i(TAG, "检测到服务器地址变更，已清除旧地址的会话")
    return true
}
