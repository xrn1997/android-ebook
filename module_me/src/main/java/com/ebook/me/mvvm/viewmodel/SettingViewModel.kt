package com.ebook.me.mvvm.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.ebook.api.config.ServerAddress
import com.ebook.api.config.ServerAddressStore
import com.ebook.common.analyze.source.SourceCatalog
import com.ebook.common.domain.ThemeMode
import com.ebook.common.domain.ThemeModeManager
import com.ebook.common.domain.UserSessionManager
import com.ebook.common.provider.ILoginProvider
import com.ebook.me.R
import com.ebook.me.repository.CacheModel
import com.ebook.me.repository.ReleaseRepository
import com.ebook.me.repository.ReleaseStateStore
import com.ebook.common.util.formatSize
import com.therouter.TheRouter
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import com.xrn1997.common.mvvm.viewmodel.Overlay
import com.xrn1997.common.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * 设置页 ViewModel。
 *
 * 职责：
 * - 缓存：展示 cacheDir 总占用（点击入口跳缓存管理页做分类清理，本页不再直接清理）
 * - 版本更新检查：主动（用户点「检查更新」即时）与静默（进设置页且距上次 ≥7 天）两种触发，
 *   结果分两种消费：更新弹窗（主动检查，以及静默在途被点击**升级**为可见的检查）与
 *   版本行角标（每次由上次检查到的 tag 现场派生）
 * - 外观主题：暴露 [ThemeMode] 状态流（浅色 / 深色 / 跟随系统）与写入入口，由设置页 UI 调用；
 *   实际持久化与主题装配由 [ThemeModeManager] 与 [com.ebook.common.BookApplication] 协作完成
 * - 退出登录：整条收尾（作废服务端 → 清本地 → 提示 + 关页）都在本类的
 *   [viewModelScope] 内跑完，页面只负责发起（见 [runLogout]）
 * - 登录态：控制「退出登录」区块的显隐
 * - 服务器地址：暴露当前地址、是否自定义与「待重启」（[serverAddressState]，全部从 store 现读派生），
 *   保存/清除配置时写 store、以 commit 结果为准并清会话（[saveServerAddress] / [resetServerAddress]）
 * - 书源入口计数：给「通用」组的「书源管理」行提供副标题（见 [sourcesCount]）。
 *   本类只读不写——书源的增删改与导入导出全在书源管理页（`BookSourceViewModel`），
 *   两处都要写就必然出现「设置页数字与实际清单不同步」的第二个事实源
 *
 * 文案约定：检查更新的**弹窗文案不进本类**（"已是最新/发现新版本/检查失败"由 UI 层依据
 * [updateState] 的语义分支经字符串资源解析）；一次性提示（登出成功）经基类命令通道下发，
 * 文案在本类经字符串资源解析——与 CacheManageViewModel/ModifyViewModel 同一形态。
 * 分工：发布源顺序与 failover 归 [ReleaseRepository]，tag 解析与比较归
 * [com.ebook.me.util.AppVersion]，落盘与限频窗口归 [ReleaseStateStore]，单飞与结论处置归
 * [ReleaseCheckController]，本类只转发页面动作与状态。
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
    private val themeModeManager: ThemeModeManager,
    sourceCatalog: SourceCatalog,
    private val serverAddressStore: ServerAddressStore,
) : BaseViewModel<CacheModel>(cacheModel) {

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
     * 版本检查会话：单飞、静默→弹窗升级、关窗取消与结论落盘都归它（见 [ReleaseCheckController]）。
     *
     * VM 只转发页面动作与状态；原先这里的 `checkJob` / `resultToDialog` 两个私有字段与三段分支
     * 已收进会话，判据因此可在纯 JVM 下锁死。
     */
    private val checkController = ReleaseCheckController(
        state = releaseStateStore,
        checkLatestRelease = releaseRepository::checkLatestRelease,
        scope = viewModelScope,
    )

    /** 版本检查的弹窗状态（初始 [UpdateState.Idle] 表示「未检查/不展示弹窗」）。 */
    val updateState: StateFlow<UpdateState> = checkController.uiState

    /** 版本行角标：由上次成功检查的 tag 与当前装机版本现场派生。 */
    val hasUpdateAvailable: StateFlow<Boolean> = checkController.hasUpdateAvailable

    /**
     * 版本行展示的本地版本号（读不到为空串，占位文案由 UI 层经字符串资源决定）。
     *
     * 由 VM 转发而不是页面自己读 PackageManager：比较基准与展示值必须同源，否则会出现
     * 「页面上显示 A、按 A' 判有无更新」的漂移。
     */
    val appVersionName: String = releaseStateStore.currentVersionName.orEmpty()

    /**
     * 当前外观主题模式（浅色 / 深色 / 跟随系统）。
     *
     * 直接转发 [ThemeModeManager.themeMode]，由 UI 层经 `collectAsState` 观察并用于
     * 「外观」行尾的当前值文案与选择弹窗的预选态。
     */
    val themeMode: StateFlow<ThemeMode> = themeModeManager.themeMode

    /**
     * 书源入口行的副标题：当前书源**总数**（含被禁用的）。
     *
     * 数全部而不是数启用：这一行回答的是「你手上有几个源」，启用与否要进书源管理页才看得清、
     * 也不是这一行的动作能改的。全部禁用时这里仍显示条数，不是 bug——禁用项仍在清单里、
     * 仍可在页面上打开，入口行报「0」反而会让人以为书源丢了。
     *
     * [SourceCatalog.observeSources] 是**冷流**（收集时才查 DAO），故首帧的初值 0 只是「还没读到库」的占位，
     * 不代表一个源都没有；真值到达后这一行自动刷新（Room 失效追踪，无需 onResume 重算）。
     * 该流给的是 `BookSourceItem`（规则 + `format` 出身），本行只取条数、不读元数据——
     * 「这条能不能删」「导出入口给不给」是书源管理页的判断，入口行不该长出第二个口径。
     * WhileSubscribed(5s) 与 [MePageViewModel] 同形态：设置页可能被长期压在返回栈里，
     * 无订阅者时不该继续挂着一个 Room 失效监听。
     */
    val sourcesCount: StateFlow<Int> = sourceCatalog.observeSources()
        .map { it.size }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0,
        )

    /**
     * 更新外观主题模式。
     *
     * 写入由 [ThemeModeManager] 持久化并推进 [themeMode]，
     * [com.ebook.common.BookApplication] 的主题装配 lambda 观察到变化后触发重组，
     * 整 App 的主题配色随之切换。
     */
    fun setThemeMode(mode: ThemeMode) {
        themeModeManager.setThemeMode(mode)
    }

    /**
     * 设置页「服务器地址」行与服务器地址页的展示态：当前地址（null=未配置）、是否已配置
     * （决定「清除配置」是否出现），
     * 以及是否「已改地址待重启」（决定不可取消的重启提示是否可见）。
     *
     * 三档全部从 store 现读派生，不拿请求值自证：写盘失败时 SP 仍是旧值，若用请求值拼状态，
     * 页面会显示一个实际没生效的地址。
     */
    private val _serverAddressState = MutableStateFlow(currentServerAddressUi())
    val serverAddressState: StateFlow<ServerAddressUi> = _serverAddressState.asStateFlow()

    /**
     * 地址写入是否在途：服务器地址页据此禁用保存/清除配置，重复调用不再静默丢回调。
     */
    private val _addressChangeInProgress = MutableStateFlow(false)
    val addressChangeInProgress: StateFlow<Boolean> = _addressChangeInProgress.asStateFlow()

    /**
     * 保存服务器地址。
     *
     * 四档处置：非法输入回 [ServerAddressSaveOutcome.Invalid]；与当前相同回
     * [ServerAddressSaveOutcome.Unchanged]；写入在途回 [ServerAddressSaveOutcome.InProgress]；
     * 真正变更才写 store 并清会话。写盘失败（`commit()` 返回 false）回
     * [ServerAddressSaveOutcome.Failed]——不清会话、不催重启，磁盘上仍是旧地址。
     *
     * 清会话不是附带动作而是安全要求：旧服务器的 refresh token 不得随新地址发出（ADR-0052）。
     * **以 store 的 commit 返回值为准，不靠「写后回读」**：SharedPreferences 先改内存再写盘，
     * 写盘失败时回读仍是新值，判不出来。
     *
     * 「写盘 → 清会话」整段在 [Dispatchers.IO] + [NonCancellable] 里跑：
     * 清会话的两处 SP 写是同步 `commit`（见 `AndroidUserSessionManager.clearSession`），
     * 同步磁盘写不得落在主线程；页面在 IO 提交期间被销毁
     * （Back / finish / 进程清理）时协程会被取消，若让取消落进这段，就会出现「地址已换、
     * 会话未清」——重启后旧 refresh token 被发往新主机。一次性回调留在段外：它丢了也不要紧，
     * 落盘标记与 `pendingRestart` 会在下次进设置页 / 下次启动时重新驱动处置。
     *
     * 地址已落盘但清会话的 SP commit 失败时仍按 [ServerAddressSaveOutcome.Saved] 收尾：
     * 地址与待清标记在同一笔 commit 里，启动兜底会在下次启动补清（见 `BookApplication`）；
     * 本进程内存态已清空，这里只记日志。
     *
     * 生效依赖重启（Retrofit 基址与 token 白名单都取 store 的进程启动快照 `effective()`），重启提示由 UI 依据
     * [ServerAddressUi.pendingRestart] 给出，不靠一次性回调传信号。
     * 在途闸门（[addressChangeInProgress]，同 [runLogout]）：连点或与 [resetServerAddress]
     * 交叉触发并发两笔只会双份落盘、双份清会话；重复触发回 [ServerAddressSaveOutcome.InProgress]，
     * 由 UI 保持对话框并禁用按钮。
     */
    fun saveServerAddress(raw: String, onOutcome: (ServerAddressSaveOutcome) -> Unit) {
        val parsed = ServerAddress.parse(raw)
        when {
            parsed == null -> {
                onOutcome(ServerAddressSaveOutcome.Invalid)
                return
            }
            parsed == serverAddressStore.current() -> {
                onOutcome(ServerAddressSaveOutcome.Unchanged)
                return
            }
            !addressChangeGate.compareAndSet(false, true) -> {
                onOutcome(ServerAddressSaveOutcome.InProgress)
                return
            }
        }
        launchAddressChange(
            write = { serverAddressStore.save(parsed) },
            onOutcome = onOutcome,
        )
    }

    /**
     * 清除配置（回到未配置）：未配置时是空操作（不清会话、不提示重启）；已配置时与换址同处置。
     *
     * 同样把「写盘 → 清会话」包进 [Dispatchers.IO] + [NonCancellable]：
     * 取消窗口不因操作方向而不同，同步落盘也不因操作方向而可以回主线程。
     * store 在 `reset()` 的同一笔 commit 里置 `session_reset_pending`，进程被杀或重启前
     * 重新登录由启动兜底补清（见 `BookApplication`）。
     * 在途闸门与 [saveServerAddress] 共用 [addressChangeGate]：两条入口写的是同一笔地址。
     */
    fun resetServerAddress(onOutcome: (ServerAddressSaveOutcome) -> Unit) {
        when {
            !serverAddressStore.isConfigured -> {
                onOutcome(ServerAddressSaveOutcome.Unchanged)
                return
            }
            !addressChangeGate.compareAndSet(false, true) -> {
                onOutcome(ServerAddressSaveOutcome.InProgress)
                return
            }
        }
        launchAddressChange(
            write = { serverAddressStore.reset() },
            onOutcome = onOutcome,
        )
    }

    /**
     * 地址写入的唯一收尾：落盘 → 清会话 → 推进状态。
     * 闸门由调用方以 CAS 取得（见 [addressChangeGate]），本方法在收尾后复位。
     *
     * [write] 是同步阻塞的 SP 操作，本方法保证它在 IO 上执行；整段用 [NonCancellable] 包住，
     * 页面销毁不会留下「地址已换、会话未清」。落盘是否成功**只看 [write] 的 commit 返回值**，
     * 不做「写后回读」自证：store 的配置面只随成功落盘推进，写成功后回读恒真，
     * 回读是死重防御，而一旦可及为假还会把已落盘的改动误报成失败。
     */
    private fun launchAddressChange(
        write: () -> Boolean,
        onOutcome: (ServerAddressSaveOutcome) -> Unit,
    ) {
        _addressChangeInProgress.value = true
        viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO + NonCancellable) {
                    if (!write()) {
                        return@withContext ServerAddressSaveOutcome.Failed
                    }
                    if (!userSessionManager.clearSession()) {
                        Logger.w(TAG, "地址已保存，但会话未确认清干净；启动兜底将按标记重试")
                    }
                    _serverAddressState.value = currentServerAddressUi()
                    ServerAddressSaveOutcome.Saved
                }
                onOutcome(outcome)
            } finally {
                addressChangeGate.set(false)
                _addressChangeInProgress.value = false
            }
        }
    }

    /** 从 store 现读三档，作为状态流的唯一来源（初始与每次写后都走这里）。 */
    private fun currentServerAddressUi() = ServerAddressUi(
        display = serverAddressStore.current()?.display,
        isConfigured = serverAddressStore.isConfigured,
        pendingRestart = serverAddressStore.pendingRestart,
    )

    init {
        refreshCacheSize()
        // 进设置页时按 7 天限频静默刷新「是否有新版本」的角标状态（不弹窗）
        checkController.startSilentRefresh()
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
        checkController.refreshBadge()
    }

    /**
     * 主动检查更新（用户点「检查更新」触发）：立即请求并展示结果弹窗。
     *
     * 「无法判定」（远端 tag 解析不出版本、或本地版本读不到）一律按 [UpdateState.CheckError]
     * 处置，且**不写成功时间戳**：把它当成「已是最新」会用一个假结论占满 7 天限频窗口，
     * 还会把角标停在错误值上——这正是「失败不覆盖旧结论」要求覆盖到的那条路径。
     *
     * 静默检查在途时不发第二个请求，而是把在途检查**升级**为用户可见：点击当场进入 Checking，
     * 请求回来后结论进弹窗（判据见 [ReleaseCheckController.check]）。
     */
    fun checkUpdate() {
        checkController.check(manual = true)
    }

    /**
     * 主动检查弹窗关闭后复位回 Idle（下次点击重新进入 Checking）。
     *
     * 「检查中」关窗按**放弃本次检查**处理：取消在途任务，否则请求回来会把弹窗重新推到
     * 已经关掉它的用户脸上。角标不受影响——结论只在 [ReleaseCheckController] 里落盘。
     */
    fun consumeUpdateDialog() {
        checkController.consumeDialog()
    }

    /**
     * 登出在途标志：连点「退出登录」只放行一次（服务端作废与本地清理都不该重跑）。
     *
     * [AtomicBoolean] CAS 而不是普通 Boolean：「检查 + 占用」必须一步完成，
     * 且 `finally` 复位要对任何线程的下一次占用可见（同 [addressChangeGate]）。
     */
    private val logoutInProgress = AtomicBoolean(false)

    /**
     * 地址写入在途标志：[saveServerAddress] 与 [resetServerAddress] 共用一笔闸门——
     * 两个入口写的是同一笔地址，并发只会双份落盘、双份清会话。入口以 CAS 原子取得闸门，
     * 取得失败即回 `InProgress`；对外经 [addressChangeInProgress] 暴露给 UI 禁用按钮。
     * [AtomicBoolean] 而不是普通 Boolean：取得/复位可能跨线程（复位在协程 `finally` 里），
     * JMM 可见性与「检查后被抢先占用」都在 CAS 这一处收口。
     */
    private val addressChangeGate = AtomicBoolean(false)

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
     * `endSession()` 就永远轮不到执行——表现为「点了退出登录，页面转回来还是登录态，
     * 也没有任何提示」。ViewModel 由 ViewModelStore 持有、跨配置变更存活，收尾放它身上才落得实。
     *
     * 顺序与容错的取法：
     * - 服务端作废（`POST /api/auth/logout`，作废该用户全部 refresh token）经
     *   [ILoginProvider] 跨模块取用；**失败不阻塞本地清理**——救不回的凭证不该把用户
     *   锁在一个他已认为退出的会话里。
     * - 本地清理只走 [UserSessionManager.endSession]（挂起出口，内部单点经 `clearSession`
     *   覆盖用户会话的三处镜像：`user_session` SP、`spUtils` 兼容键、ProfileRepository
     *   进程内身份流），线程与取消契约由接口收口（ADR-0056）。
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
        if (!logoutInProgress.compareAndSet(false, true)) return
        viewModelScope.launch {
            updateOverlay(Overlay.Loading)
            try {
                provider?.logout()?.onFailure {
                    Logger.w(TAG, "服务端登出失败，仍继续清本地会话：${it.message}")
                }
                // 清会话走挂起出口：IO 派发、NonCancellable 段与失败日志都收在接口里（ADR-0056）
                userSessionManager.endSession()
                sendToast(context.getString(R.string.setting_logout_success))
                sendFinish()
            } finally {
                updateOverlay(Overlay.None)
                logoutInProgress.set(false)
            }
        }
    }
}

/** 设置页「服务器地址」行与服务器地址页的展示态。 */
data class ServerAddressUi(
    /** 当前配置的完整地址；null = 未配置（应用不带默认地址，ADR-0053）。 */
    val display: String?,
    /** 是否已配置：false 即未配置，决定行徽标（未配置/自定义）与「清除配置」按钮显隐。 */
    val isConfigured: Boolean,
    /** 已改地址（含清除）但本进程尚未重启：[com.ebook.api.config.ServerAddressStore.pendingRestart] 的转发。 */
    val pendingRestart: Boolean,
)

/** 保存/清除配置的一次性结果，由 UI 决定关窗、行内报错或弹重启提示。 */
sealed interface ServerAddressSaveOutcome {
    data object Invalid : ServerAddressSaveOutcome
    data object Unchanged : ServerAddressSaveOutcome

    /** 写入在途期间的重复触发：保持对话框，不重复落盘/清会话。 */
    data object InProgress : ServerAddressSaveOutcome

    /** 落盘失败：不清会话、不催重启，UI 留在对话框并提示重试。 */
    data object Failed : ServerAddressSaveOutcome
    data object Saved : ServerAddressSaveOutcome
}
