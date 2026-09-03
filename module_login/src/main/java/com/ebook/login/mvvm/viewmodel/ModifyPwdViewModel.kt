package com.ebook.login.mvvm.viewmodel

import android.text.TextUtils
import androidx.lifecycle.viewModelScope
import com.ebook.api.utils.CoroutineAdapter
import com.ebook.common.domain.UserSessionManager
import com.ebook.common.event.KeyCode
import com.ebook.common.repository.ProfileRepository
import com.ebook.login.ModifyPwdActivity
import com.ebook.login.R
import com.ebook.login.repository.UserRepository
import com.therouter.TheRouter.build
import com.xrn1997.common.BaseApplication.Companion.context
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
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
 * 密码管理 ViewModel：承载双路径（对齐 ebook-server ADR-0002）。
 *
 * - 忘记密码路径：[sendForgotCode] 按邮箱发码 → [toResetPage] 携 email+验证码进入
 *   [ModifyPwdActivity] 的 RESET 模式 → [reset] 由服务端校验验证码并重置；
 * - 已登录改密路径：[modify] 携旧密码调改密端点（旧密码由服务端校验，A0210）。
 *
 * 页间跳转一律 TheRouter 同步直跳，不走「sendFinish + sendNavigate」事件组合
 * （事件先后消费顺序曾导致导航丢失）。
 */
@HiltViewModel
class ModifyPwdViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val profileRepository: ProfileRepository,
    private val userSessionManager: UserSessionManager
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
     * 忘记密码发码：纯邮箱入参，服务端发 6 位验证码邮件（频控命中返回 A0241）。
     * 仅在请求成功后启动倒计时：失败（邮箱为空/网络错误等）不锁按钮，用户可立即重试。
     */
    fun sendForgotCode(email: String) {
        if (TextUtils.isEmpty(email)) {
            sendToast(context.getString(R.string.email_empty))
            return
        }
        viewModelScope.launch {
            val result = userRepository.sendForgotPasswordCode(email)
            result.onSuccess {
                sendToast(context.getString(R.string.code_sent))
                startResendCountdown()
            }.onFailure { exception ->
                toastFailure(exception)
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
     * 携邮箱与验证码进入改密页 RESET 模式。
     *
     * 客户端只校验填写完整性；验证码正确性由服务端在 [reset] 时校验，
     * 避免本地校验与服务端状态（过期/次数）脱节。
     */
    fun toResetPage(email: String, code: String) {
        if (TextUtils.isEmpty(email)) {
            sendToast(context.getString(R.string.email_empty))
            return
        }
        if (code.trim().length != 6) {
            sendToast(context.getString(R.string.verify_code_invalid))
            return
        }
        Logger.d(TAG, "toResetPage: email:$email")
        build(KeyCode.Login.MODIFY_PWD_PATH)
            .withString(ModifyPwdActivity.EXTRA_MODE, ModifyPwdActivity.MODE_RESET)
            .withString(ModifyPwdActivity.EXTRA_EMAIL, email)
            .withString(ModifyPwdActivity.EXTRA_CODE, code.trim())
            .navigation()
    }

    /**
     * 已登录改密：旧密码由服务端校验（A0210 旧密码错误）。
     * 改密成功即视为会话失效：清会话并回登录页。
     *
     * 两处登录态必须**成对清**（与 module_me 退出登录的 SettingViewModel.logout 同一收口）：
     * [UserSessionManager.clearSession] 清 token/isLoggedIn/user_session SP 并同步镜像的 SP_IS_LOGIN，
     * [ProfileRepository.clearAuthData] 清内存昵称/头像流。只清后者的话两侧数据源会脱钩：
     * `LoginInterceptor` 读 SP_IS_LOGIN 已认为未登录，而 isLoggedIn/TokenHolder 仍为已登录，
     * 表现为「我的页仍显示已登录、进评论区/编辑资料却被弹回登录页」。
     */
    fun modify(oldPwd: String, newPwd: String, confirmPwd: String) {
        if (oldPwd.isEmpty() || newPwd.isEmpty() || confirmPwd.isEmpty()) {
            sendToast(context.getString(R.string.pwd_incomplete))
            return
        }
        if (newPwd != confirmPwd) { //两次密码不一致
            sendToast(context.getString(R.string.pwd_mismatch))
            return
        }
        // 日志不输出密码，避免敏感信息进入 logcat（release 裁剪之前的残留风险）
        Logger.d(TAG, "modify: 已登录改密")
        viewModelScope.launch {
            val result = userRepository.modifyPwd(oldPwd, newPwd)
            result.onSuccess {
                sendToast(context.getString(R.string.modify_success))
                // 先清会话（token + isLoggedIn + SP 镜像），再清资料内存流，见方法 KDoc
                userSessionManager.clearSession()
                profileRepository.clearAuthData()
                build(KeyCode.Login.LOGIN_PATH)
                    .navigation()
                sendFinish()
            }.onFailure { exception ->
                toastFailure(exception)
            }
        }
    }

    /**
     * 忘记密码重置：邮箱 + 验证码 + 新密码（验证码由服务端校验，A0132/A0241）。
     * 重置成功后引导用户主动登录（预填邮箱）。
     */
    fun reset(email: String, code: String, newPwd: String, confirmPwd: String) {
        if (newPwd.isEmpty() || confirmPwd.isEmpty()) {
            sendToast(context.getString(R.string.pwd_incomplete))
            return
        }
        if (newPwd != confirmPwd) { //两次密码不一致
            sendToast(context.getString(R.string.pwd_mismatch))
            return
        }
        viewModelScope.launch {
            val result = userRepository.resetPassword(email, code, newPwd)
            result.onSuccess {
                sendToast(context.getString(R.string.pwd_reset_success))
                build(KeyCode.Login.LOGIN_PATH)
                    .withString("email", email)
                    .navigation()
                sendFinish()
            }.onFailure { exception ->
                toastFailure(exception)
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
        private const val TAG = "ModifyPwdViewModel"

        /** 重发倒计时秒数，与服务端发码频控窗口一致 */
        private const val CODE_RESEND_COUNTDOWN = 60
    }
}
