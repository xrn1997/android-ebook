package com.ebook.me.mvvm.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.ebook.common.domain.UserSessionManager
import com.ebook.common.provider.ILoginProvider
import com.ebook.me.R
import com.ebook.me.repository.CacheModel
import com.ebook.me.repository.ReleaseCheckResult
import com.ebook.me.repository.ReleaseRepository
import com.ebook.me.repository.ReleaseStateStore
import com.ebook.common.util.formatSize
import com.ebook.me.util.AppVersion
import com.ebook.me.util.isOlderThan
import com.therouter.TheRouter
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import com.xrn1997.common.mvvm.viewmodel.Overlay
import com.xrn1997.common.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设置页 ViewModel。
 *
 * 职责：
 * - 缓存：展示 cacheDir 总占用（点击入口跳缓存管理页做分类清理，本页不再直接清理）
 * - 版本更新检查：主动（用户点「检查更新」即时）与静默（进设置页且距上次 ≥7 天）两种触发，
 *   结果分两种消费：更新弹窗（主动检查，以及静默在途被点击**升级**为可见的检查）与
 *   版本行角标（每次由上次检查到的 tag 现场派生）
 * - 退出登录：整条收尾（作废服务端 → 清本地 → 提示 + 关页）都在本类的
 *   [viewModelScope] 内跑完，页面只负责发起（见 [runLogout]）
 * - 登录态：控制「退出登录」区块的显隐
 *
 * 文案约定：检查更新的**弹窗文案不进本类**（"已是最新/发现新版本/检查失败"由 UI 层依据
 * [updateState] 的语义分支经字符串资源解析）；一次性提示（登出成功）经基类命令通道下发，
 * 文案在本类经字符串资源解析——与 CacheManageViewModel/ModifyViewModel 同一形态。
 * 分工：发布源顺序与 failover 归 [ReleaseRepository]，tag 解析与比较归
 * [com.ebook.me.util.AppVersion]，落盘与限频窗口归 [ReleaseStateStore]，
 * 本类只把它们串起来并决定 UI 状态。
 *
 * 一条贯穿检查链路的不变量：**判不出结论就不算检查成功**（远端 tag 解析不出版本、或本地
 * 版本读不到 → 按 [UpdateState.CheckError] 处置且不写成功时间戳），否则一个假结论会占满
 * 7 天限频窗口并把角标停在错误值上。
 */
@HiltViewModel
class SettingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cacheModel: CacheModel,
    private val userSessionManager: UserSessionManager,
    private val releaseRepository: ReleaseRepository,
    private val releaseStateStore: ReleaseStateStore,
) : BaseViewModel<CacheModel>(cacheModel) {

    private companion object {
        const val TAG = "SettingViewModel"
    }

    /** 当前登录态（控制退出登录区块显隐） */
    val isLoggedIn: StateFlow<Boolean> = userSessionManager.isLoggedIn

    /**
     * cacheDir 可读大小（如 "12.3 MB"）。
     *
     * 初始为空串表示「计算中」，占位文案（--）由 UI 层经字符串资源解析，
     * VM 不持有任何用户可见文本。
     */
    private val _cacheSize = MutableStateFlow("")
    val cacheSize: StateFlow<String> = _cacheSize.asStateFlow()

    /**
     * 版本检查的 UI 状态。初始为 [UpdateState.Idle] 表示「未检查/不展示弹窗」。
     */
    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    /**
     * 在途的版本检查任务（主动与静默共用一条，同一时刻最多一个请求在跑）。
     *
     * 两个用途：
     * - **单飞**：同一时刻最多一个请求；连点版本行不会并发第二个请求
     *   （两次请求各自落盘 tag 与角标，后回来的那个会覆盖前一个的结论）；
     * - **关窗即取消**：用户在「检查中」把弹窗关掉时取消任务，否则请求回来会把弹窗
     *   重新推到用户脸上。取消能真正掐断备用源请求，靠的是 `ReleaseRepository`
     *   把 [kotlinx.coroutines.CancellationException] 原样抛出（不当「该源失败」）。
     */
    private var checkJob: Job? = null

    /**
     * 在途检查完成后，结论是否推进 [updateState] 弹窗。
     *
     * 主动检查发起时为 true（结果要弹窗）；静默检查发起时为 false（只落盘不弹窗）。
     * **静默在途期间用户点了版本行**则就地升级为 true：这次点击不能没有反馈，
     * 但也不该并发第二个请求（单飞，理由见 [checkJob]），于是让在途请求的结果改道进弹窗。
     */
    private var resultToDialog = false

    /**
     * 版本行角标：是否「已有可更新的新版本」。
     *
     * 值由 [ReleaseStateStore.hasUpdateAvailable] **现场派生**（上次检查到的 tag vs 当前装机
     * 版本），不存结论布尔量——升级安装后重新派生就自动纠正。见 [refreshUpdateBadge]。
     */
    private val _hasUpdateAvailable = MutableStateFlow(releaseStateStore.hasUpdateAvailable)
    val hasUpdateAvailable: StateFlow<Boolean> = _hasUpdateAvailable.asStateFlow()

    /**
     * 版本行展示的本地版本号（读不到为空串，占位文案由 UI 层经字符串资源决定）。
     *
     * 由 VM 转发而不是页面自己读 PackageManager：比较基准与展示值必须同源，否则会出现
     * 「页面上显示 A、按 A' 判有无更新」的漂移。
     */
    val appVersionName: String = releaseStateStore.currentVersionName.orEmpty()

    init {
        refreshCacheSize()
        // 进设置页时按 7 天限频静默刷新「是否有新版本」的角标状态（不弹窗）
        startSilentRefresh()
    }

    /**
     * 重算缓存总占用。
     *
     * public 供 Activity 在 onResume 时刷新——从缓存管理页返回后，
     * 入口行的大小文案需要与实际清理结果同步。
     */
    fun refreshCacheSize() {
        viewModelScope.launch {
            _cacheSize.value = formatSize(cacheModel.cacheSizeBytes())
        }
    }

    /**
     * 重新派生角标。
     *
     * public 供 Activity 在 onResume 时刷新：本页可长期驻留在返回栈里，VM 由 ViewModelStore
     * 持有、比 Compose 页面活得更久，只在构造时算一次不足以反映「装机版本已经变了」——
     * 用户完全可能看到角标后去装新版本再回到本页。
     */
    fun refreshUpdateBadge() {
        _hasUpdateAvailable.value = releaseStateStore.hasUpdateAvailable
    }

    /**
     * 主动检查更新（用户点「检查更新」触发）：立即请求并展示结果弹窗。
     *
     * 「无法判定」（远端 tag 解析不出版本、或本地版本读不到）一律按 [UpdateState.CheckError]
     * 处置，且**不写成功时间戳**：把它当成「已是最新」会用一个假结论占满 7 天限频窗口，
     * 还会把角标停在错误值上——这正是「失败不覆盖旧结论」要求覆盖到的那条路径。
     *
     * 静默检查在途时不发第二个请求，而是把在途检查**升级**为用户可见（见 [resultToDialog]）：
     * 点击当场进入 Checking，请求回来后结论进弹窗。修复前这里直接 return，
     * 慢网下用户点版本行会毫无反馈。
     */
    fun checkUpdate() {
        launchCheck(manual = true)
    }

    /**
     * 进设置页时的静默刷新：距上次成功检查 ≥7 天才发起，否则角标沿用上次结论的派生值。
     * 静默刷新**不**改变 [updateState]（不弹窗），只更新角标；失败则无声忽略。
     * 在途期间若被用户手动点击，结果会升级进弹窗（见 [launchCheck]）。
     */
    private fun startSilentRefresh() {
        if (!releaseStateStore.shouldAutoRefresh()) return
        launchCheck(manual = false)
    }

    /**
     * 发起一次版本检查，主动与静默共用同一条在途任务（单飞）。
     *
     * - 无在途：按 [manual] 决定结论是否进弹窗，并发起请求；
     * - 有在途且 [manual]（静默在途时用户点了版本行）：把在途检查升级为用户可见——
     *   置 Checking、结论改道进弹窗，不并发第二个请求；
     * - 有在途且静默（主动弹窗已在，又来一次静默判窗）：维持现状。
     */
    private fun launchCheck(manual: Boolean) {
        if (checkJob?.isActive == true) {
            if (manual) {
                resultToDialog = true
                _updateState.value = UpdateState.Checking
            }
            return
        }
        resultToDialog = manual
        checkJob = viewModelScope.launch {
            if (resultToDialog) _updateState.value = UpdateState.Checking
            val result = releaseRepository.checkLatestRelease()
            val hasUpdate = result?.let(::recordConclusion)
            if (resultToDialog) {
                _updateState.value = when {
                    result == null || hasUpdate == null -> UpdateState.CheckError
                    hasUpdate -> UpdateState.HasUpdate(result)
                    else -> UpdateState.UpToDate
                }
            }
        }
    }

    /**
     * 比较远端 tag 与本地版本，并把检查到的 tag 落盘、重派生角标。
     *
     * @return `true`/`false` 表示是否落后于远端；`null` 表示无法判定——此时**不写盘**，
     *   既保有上次结论也保有上次检查时间，调用方按「检查失败」处置
     */
    private fun recordConclusion(result: ReleaseCheckResult): Boolean? {
        val remote = AppVersion.parse(result.remoteTag) ?: return null
        val current = releaseStateStore.currentVersion ?: return null
        releaseStateStore.markCheckSuccess(result.remoteTag)
        refreshUpdateBadge()
        return current.isOlderThan(remote)
    }

    /**
     * 主动检查弹窗关闭后，把状态复位回 Idle（下次点击重新进入 Checking）。
     *
     * 「检查中」关窗按**放弃本次检查**处理：取消在途任务，否则请求回来会把弹窗重新推到
     * 已经关掉它的用户脸上。角标不受影响——结论只在 [recordConclusion] 里落盘。
     *
     * 取消时把 [resultToDialog] 一并复位：升级旗标只属于被放弃的那次在途检查，
     * 跨任务残留会让未来的静默发起意外弹窗（当前调用图下静默只在 init 发起、观察不到，
     * 纯属防御）。
     */
    fun consumeUpdateDialog() {
        if (_updateState.value is UpdateState.Checking) {
            checkJob?.cancel()
            checkJob = null
            resultToDialog = false
        }
        _updateState.value = UpdateState.Idle
    }

    /** 登出在途标志：连点「退出登录」只放行一次（服务端作废与本地清理都不该重跑） */
    private var logoutInProgress = false

    /**
     * 退出登录入口：解析跨模块 provider 后交给 [runLogout] 收尾。
     *
     * 独立运行（isModule=true）时 module_login 不在依赖图内，解析结果为 null，
     * 登出只剩本地清理——调试宿主本就不连后端。
     */
    fun logout() = runLogout(TheRouter.get(ILoginProvider::class.java))

    /**
     * 登出编排：闸门 → 等待态 → 尽力作废服务端 → 无条件清本地 → 提示 + 关页。
     *
     * 整条链在 [viewModelScope] 内跑完，而不是交给页面用 `lifecycleScope` 串：登出要先挂一个
     * 网络请求，请求没回来时用户旋转屏幕会重建 Activity、连带取消页面作用域，
     * `clearSession()` 就永远轮不到执行——表现为「点了退出登录，页面转回来还是登录态，
     * 也没有任何提示」。ViewModel 由 ViewModelStore 持有、跨配置变更存活，收尾放它身上才落得实。
     *
     * 顺序与容错的取法：
     * - 服务端作废（`POST /api/auth/logout`，作废该用户全部 refresh token）经
     *   [ILoginProvider] 跨模块取用；**失败不阻塞本地清理**——救不回的凭证不该把用户
     *   锁在一个他已认为退出的会话里。
     * - 本地清理只走 [UserSessionManager.clearSession]，它一次覆盖用户会话的三处镜像
     *   （`user_session` SP、`spUtils` 兼容键、ProfileRepository 进程内身份流）。
     * - 提示与关页经基类命令通道下发，且**先 [sendToast] 后 [sendFinish]**：命令通道由
     *   `MvvmBinder` 在宿主 `lifecycleScope` 的 `repeatOnLifecycle(STARTED)` 里消费，
     *   页面 `finish()` 后采集器随 `onStop` 取消，还排在 `Channel` 里的命令就再没人取——
     *   顺序反过来就不是「可能不好看」，而是提示会真的丢。
     *
     * internal 是给测试留的最小接缝：生产 adapter 是 TheRouter 解析出的真 provider，
     * 测试 adapter 是假件——同一接缝两个 adapter，于是「先作废服务端再清本地」的顺序、
     * 连点只发一次、覆盖层复位这三件事都能在 JVM 下断言。
     */
    internal fun runLogout(provider: ILoginProvider?) {
        if (logoutInProgress) return
        logoutInProgress = true
        viewModelScope.launch {
            updateOverlay(Overlay.Loading)
            try {
                provider?.logout()?.onFailure {
                    Logger.w(TAG, "服务端登出失败，仍继续清本地会话：${it.message}")
                }
                userSessionManager.clearSession()
                sendToast(context.getString(R.string.setting_logout_success))
                sendFinish()
            } finally {
                updateOverlay(Overlay.None)
                logoutInProgress = false
            }
        }
    }
}

/**
 * 版本检查弹窗的 UI 状态（语义分支，不含任何用户可见文本）。
 */
sealed interface UpdateState {
    /** 未检查/弹窗已关闭 */
    data object Idle : UpdateState
    /** 检查中 */
    data object Checking : UpdateState
    /** 已是最新版本 */
    data object UpToDate : UpdateState
    /** 发现新版本，携带远端信息供弹窗展示与下载 */
    data class HasUpdate(val result: ReleaseCheckResult) : UpdateState
    /** 检查失败（网络/解析） */
    data object CheckError : UpdateState
}