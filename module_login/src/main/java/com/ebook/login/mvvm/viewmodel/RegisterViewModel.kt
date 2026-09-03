package com.ebook.login.mvvm.viewmodel

import android.os.Bundle
import android.text.TextUtils
import androidx.lifecycle.viewModelScope
import com.ebook.api.utils.CoroutineAdapter
import com.ebook.common.event.KeyCode
import com.ebook.login.R
import com.ebook.login.repository.UserRepository
import com.therouter.TheRouter.build
import com.xrn1997.common.BaseApplication.Companion.context
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import com.xrn1997.common.mvvm.viewmodel.Overlay
import com.xrn1997.common.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 注册 ViewModel：对齐 ebook-server ADR-0002 的三步注册
 * （按邮箱发码 → register(邮箱+验证码+密码) → 用户主动登录）。
 *
 * 注册即激活但不发 token，因此注册成功不保存会话、直接引导去登录页。
 */
@HiltViewModel
class RegisterViewModel @Inject constructor(
    private val userRepository: UserRepository
) : BaseViewModel<UserRepository>(userRepository) {

    /** 发码倒计时内部状态，对外只暴露只读 [codeCountdown] */
    private val _codeCountdown = MutableStateFlow(0)

    /**
     * 发码倒计时秒数（0 = 可再次发码）。
     *
     * 与服务端发码端点的 60 秒频控对齐：倒计时期间禁用「获取验证码」，
     * 避免客户端连点触发 A0241（尝试超限）报错。
     */
    val codeCountdown: StateFlow<Int> = _codeCountdown.asStateFlow()

    /** 倒计时任务句柄：重发/页面销毁时取消，避免旧任务覆写新状态 */
    private var countdownJob: Job? = null

    /**
     * 发送注册验证码（注册专用发码端点，与忘记密码发码分离）。
     * 仅在请求成功后启动倒计时：失败（邮箱为空/网络错误等）不锁按钮，用户可立即重试。
     */
    fun sendCode(email: String) {
        if (TextUtils.isEmpty(email)) {
            sendToast(context.getString(R.string.email_empty))
            return
        }
        viewModelScope.launch {
            updateOverlay(Overlay.Loading)
            try {
                val result = userRepository.sendRegisterCode(email)
                result.onSuccess {
                    sendToast(context.getString(R.string.code_sent))
                    startResendCountdown()
                }.onFailure { exception ->
                    toastFailure(exception)
                }
            } finally {
                updateOverlay(Overlay.None)
            }
        }
    }

    /**
     * 启动 60 秒重发倒计时（秒级递减，归零后按钮恢复可点）。
     * 倒计时驻留 ViewModel 而非 Compose 局部状态：配置变更（旋转）不丢倒计时进度。
     */
    private fun startResendCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (remain in CODE_RESEND_COUNTDOWN downTo 1) {
                _codeCountdown.value = remain
                delay(1_000L)
            }
            _codeCountdown.value = 0
        }
    }

    /**
     * 注册：邮箱 + 验证码 + 密码。
     *
     * 客户端只做完整性校验（非空/6 位/两次一致），邮箱格式与验证码正确性
     * 交给服务端业务码；成功后跳登录页并预填邮箱（注册不发 token，需主动登录）。
     */
    fun register(email: String, code: String, firstPwd: String, secondPwd: String) {
        if (TextUtils.isEmpty(email)) { //邮箱为空
            sendToast(context.getString(R.string.email_empty))
            return
        }
        if (code.trim().length != 6) { //验证码应为 6 位
            sendToast(context.getString(R.string.verify_code_invalid))
            return
        }
        if (TextUtils.isEmpty(firstPwd) || TextUtils.isEmpty(secondPwd)) {
            sendToast(context.getString(R.string.pwd_incomplete))
            return
        }
        if (!TextUtils.equals(firstPwd, secondPwd)) { //两次密码不一致
            sendToast(context.getString(R.string.pwd_mismatch))
            return
        }
        viewModelScope.launch {
            updateOverlay(Overlay.Loading)
            try {
                val result = userRepository.register(email, code.trim(), firstPwd)
                result.onSuccess {
                    sendToast(context.getString(R.string.register_success))
                    // 注册不发 token：跳登录页并预填邮箱，由用户主动登录
                    val bundle = Bundle().apply { putString("email", email) }
                    build(KeyCode.Login.LOGIN_PATH)
                        .with(bundle)
                        .navigation()
                    sendFinish()
                }.onFailure { exception ->
                    toastFailure(exception)
                }
            } finally {
                updateOverlay(Overlay.None)
            }
        }
    }

    /**
     * 统一失败提示：会话过期已全局处置则只记日志不重复弹 Toast（Q4：事件唯一出口）；
     * 业务异常走业务文案，其余走原始 message。
     */
    private fun toastFailure(exception: Throwable) {
        if (CoroutineAdapter.isSessionExpiredHandled(exception)) {
            Logger.w(TAG, "会话过期已由全局处置，本调用点静默（仅日志）：${exception.message}")
            return
        }
        if (exception is CoroutineAdapter.ApiException) {
            sendToast(exception.message())
        } else {
            sendToast("${exception.message}")
        }
    }

    companion object {
        private const val TAG = "RegisterViewModel"

        /** 重发倒计时秒数，与服务端发码频控窗口一致 */
        private const val CODE_RESEND_COUNTDOWN = 60
    }
}
