package com.ebook.login.mvvm.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * 发码按钮的 60 秒重发倒计时：注册发码与忘记密码发码共用同一套时间口径。
 *
 * 60 秒对齐服务端发码端点的频控窗口（A0241「尝试超限」）：倒计时期间按钮禁用，
 * 两侧各写一份递减循环时，一处改了秒数、另一处没改就会让某条路径绕过频控。
 *
 * 状态留在 ViewModel 侧（本类由 ViewModel 持有），不放 Compose 局部状态：
 * 配置变更（旋转）不丢倒计时进度，用户回来时按钮该锁多久还是多久。
 *
 * [start] 先取消旧任务再重置：重发成功会再次启动，两套递减并行会把剩余的秒数减双倍，
 * 按钮提前解禁。
 */
internal class CodeResendCountdown {

    private val _seconds = MutableStateFlow(0)

    /** 剩余秒数（0 = 可再次发码），对外只读 */
    val seconds: StateFlow<Int> = _seconds.asStateFlow()

    /** 递减任务句柄：重启或宿主销毁时取消，避免旧任务覆写新状态 */
    private var job: Job? = null

    /**
     * 启动（或重启）倒计时：立即置 [COUNTDOWN_SECONDS]，此后每秒减 1，归零后按钮恢复可点。
     *
     * 首次赋值在挂起前同步完成：按钮必须在点击后的同一帧就锁住，不能等第一次调度。
     */
    fun start(scope: CoroutineScope) {
        job?.cancel()
        _seconds.value = COUNTDOWN_SECONDS
        job = scope.launch {
            repeat(COUNTDOWN_SECONDS) {
                delay(1_000L.milliseconds)
                _seconds.value -= 1
            }
        }
    }

    private companion object {

        /** 重发倒计时秒数，与服务端发码频控窗口一致 */
        const val COUNTDOWN_SECONDS = 60
    }
}
