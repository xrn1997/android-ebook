package com.ebook.me.mvvm.viewmodel

import com.ebook.me.repository.ReleaseCheckResult
import com.ebook.me.repository.ReleaseState
import com.ebook.me.util.AppVersion
import com.ebook.me.util.isOlderThan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 一次版本检查的会话：单飞、静默→弹窗升级、关窗取消与结论落盘。
 *
 * 原先这些判据长在 `SettingViewModel` 的两个私有字段（`checkJob` / `resultToDialog`）与
 * 三段分支里，只能经 Robolectric 测接线。抽成纯 JVM 会话后，环境面（[ReleaseState]）与
 * 检查请求（`suspend () -> ReleaseCheckResult?`）都以依赖注入，单飞与「判不出不落盘」
 * 这类边界可以直接锁死。
 *
 * 线程：协程跑在注入的 [scope]（生产是 `viewModelScope`），本类不持线程。
 */
internal class ReleaseCheckController(
    private val state: ReleaseState,
    private val checkLatestRelease: suspend () -> ReleaseCheckResult?,
    private val scope: CoroutineScope,
) {

    private val _uiState = MutableStateFlow<UpdateState>(UpdateState.Idle)

    /** 版本检查弹窗状态；静默检查全程保持 [UpdateState.Idle]。 */
    val uiState: StateFlow<UpdateState> = _uiState.asStateFlow()

    private val _hasUpdateAvailable = MutableStateFlow(state.hasUpdateAvailable)

    /** 版本行角标：由上次成功检查的 tag 与当前装机版本现场派生。 */
    val hasUpdateAvailable: StateFlow<Boolean> = _hasUpdateAvailable.asStateFlow()

    /**
     * 在途任务：主动与静默共用一条，同一时刻最多一个请求。
     *
     * 取消能真正掐断备用源请求，靠的是 `ReleaseRepository` 把
     * [kotlinx.coroutines.CancellationException] 原样抛出（不当「该源失败」）。
     */
    private var checkJob: Job? = null

    /**
     * 在途结论是否推进弹窗。
     *
     * 主动检查发起时为 true；静默为 false。静默在途时用户点版本行则就地升级为 true：
     * 这次点击不能没有反馈，但也不该并发第二个请求。
     */
    private var resultToDialog = false

    /** 进设置页时按限频窗口静默刷新角标；不在窗口内则什么都不做。 */
    fun startSilentRefresh() {
        if (!state.shouldAutoRefresh()) return
        check(manual = false)
    }

    /**
     * 发起一次检查，主动与静默共用同一条在途任务（单飞）。
     *
     * - 无在途：按 [manual] 决定结论是否进弹窗，并发起请求；
     * - 有在途且 [manual]：把在途检查升级为用户可见（置 Checking、结论改道进弹窗）；
     * - 有在途且静默：维持现状。
     */
    fun check(manual: Boolean) {
        if (checkJob?.isActive == true) {
            if (manual) {
                resultToDialog = true
                _uiState.value = UpdateState.Checking
            }
            return
        }
        resultToDialog = manual
        checkJob = scope.launch {
            if (resultToDialog) _uiState.value = UpdateState.Checking
            val result = checkLatestRelease()
            val hasUpdate = result?.let(::recordConclusion)
            if (resultToDialog) {
                _uiState.value = when {
                    result == null || hasUpdate == null -> UpdateState.CheckError
                    hasUpdate -> UpdateState.HasUpdate(result)
                    else -> UpdateState.UpToDate
                }
            }
        }
    }

    /**
     * 主动检查弹窗关闭后复位回 Idle。
     *
     * 「检查中」关窗按**放弃本次检查**处理：取消在途任务，否则请求回来会把弹窗重新推到
     * 已经关掉它的用户脸上。角标不受影响——结论只在 [recordConclusion] 里落盘。
     */
    fun consumeDialog() {
        if (_uiState.value is UpdateState.Checking) {
            checkJob?.cancel()
            checkJob = null
            resultToDialog = false
        }
        _uiState.value = UpdateState.Idle
    }

    /**
     * 重新派生角标：页面可长期驻留，装机版本可能已经变了，构造时算一次不够。
     */
    fun refreshBadge() {
        _hasUpdateAvailable.value = state.hasUpdateAvailable
    }

    /**
     * 比较远端 tag 与本地版本，并把检查到的 tag 落盘。
     *
     * @return 是否落后于远端；`null` 表示无法判定——此时**不写盘**，既保有上次结论也保有
     *   上次检查时间，调用方按「检查失败」处置。判不出结论却写成功时间戳，会让一个假结论
     *   占满限频窗口并把角标停在错误值上。
     */
    private fun recordConclusion(result: ReleaseCheckResult): Boolean? {
        val remote = AppVersion.parse(result.remoteTag) ?: return null
        val current = state.currentVersion ?: return null
        state.markCheckSuccess(result.remoteTag)
        refreshBadge()
        return current.isOlderThan(remote)
    }
}

/**
 * 版本检查弹窗的 UI 状态（语义分支，不含任何用户可见文本）。
 *
 * 定义在输出方（[ReleaseCheckController]）旁边：它只说「这一轮检查走到了哪一步」，
 * 文案由 UI 层按分支经字符串资源解析；`SettingViewModel` 只转发这个流。
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
