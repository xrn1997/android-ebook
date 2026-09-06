package com.ebook.login.mvvm.viewmodel

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import androidx.lifecycle.viewModelScope
import com.ebook.common.domain.UserSessionManager
import com.ebook.common.event.KeyCode
import com.ebook.common.interceptor.LoginInterceptor
import com.ebook.common.repository.ProfileRepository
import com.ebook.common.util.reportFailure
import com.ebook.login.R
import com.ebook.login.repository.UserRepository
import com.therouter.TheRouter.build
import com.xrn1997.common.BaseApplication.Companion.context
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import com.xrn1997.common.mvvm.viewmodel.Overlay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 登录 ViewModel：邮箱 + 密码登录（邮箱为登录主标识，见 ADR-0009）。
 *
 * 登录成功后经 [UserSessionManager.saveSession] 建立会话（身份 + 双 token 持久化，
 * access token 同步写入 TokenHolder），并按 [bundle] 携带的来源路径回跳发起方页面。
 * A0230 过期由网络层单飞静默刷新收口，本页失败分支只处理「救不回来」以外的异常。
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val profileRepository: ProfileRepository,
    private val userSessionManager: UserSessionManager
) : BaseViewModel<UserRepository>(userRepository) {
    /** 登录页路由参数（发起方页面路径，登录成功后回跳；见 [loginOnNext]） */
    var bundle: Bundle? = null

    /** 防重复点击登录：请求在途时忽略后续触发 */
    private var isLoggingIn = false

    /**
     * 发起登录：客户端只做非空校验，账号/密码正确性交给服务端业务码。
     * 成功后保存会话、回跳来源页并刷新本地资料缓存（头像/昵称）。
     */
    fun login(email: String, password: String) {
        if (isLoggingIn) return  // 防止重复登录
        if (TextUtils.isEmpty(email)) {
            sendToast(context.getString(R.string.email_empty))
            return
        }
        if (TextUtils.isEmpty(password)) {
            sendToast(context.getString(R.string.pwd_empty))
            return
        }
        viewModelScope.launch {
            isLoggingIn = true
            updateOverlay(Overlay.Loading)
            try {
                val result = userRepository.login(email, password)
                result.onSuccess { session ->
                    // 保存会话信息到 UserSessionManager
                    // saveSession 会将 token 写入 TokenHolder，AuthInterceptor 自动附加到请求头
                    userSessionManager.saveSession(session, session.refreshToken)
                    loginOnNext()
                    profileRepository.updatePicture(session.avatar)
                    profileRepository.updateNickname(session.nickname)
                }.onFailure { reportFailure(it) }
            } finally {
                isLoggingIn = false
                updateOverlay(Overlay.None)
            }
        }
    }

    /**
     * 登录成功后的导航处置：
     *
     * - **拦截回跳**：页面被 [LoginInterceptor] 拦截跳登录时，TheRouter 自动写入的
     *   `therouter_path` 是**原始目标页** URL（如书籍详情页）→ 登录成功后回跳原页；
     * - **主动跳登录**（改密/注册/会话过期后）：`therouter_path` 是 LOGIN_PATH 自身
     *   （或其带参形式 `login?email=xxx`）→ 此时登录页只是流程中转站，应清掉中间
     *   链路页面（编辑资料/注册等）回主界面，而非回退到改密/注册前的页面。
     *
     * 主界面路由在两种构建模式下都存在：集成模式由 module\_main 的 MainActivity 持有；
     * 独立模式（isModule=true）不编译 module\_main，由调试宿主 `src/main/test/debug/MainActivity`
     * 以同一路径占位（否则 TheRouter 找不到路由只会静默丢弃跳转，栈里未 finish 的中间页会被露出）。
     */
    private fun loginOnNext() {
        val path = bundle?.getString(KeyCode.Login.PATH)
        // 被拦截回跳的目标：非空且非 LOGIN_PATH（或其带参形式）→ 原始目标页
        val interceptTarget = path?.takeUnless {
            it == KeyCode.Login.LOGIN_PATH || it.startsWith("${KeyCode.Login.LOGIN_PATH}?")
        }
        if (interceptTarget != null) {
            // 拦截回跳：登录成功回到被拦截的原始页面（如书籍详情页）
            build(interceptTarget).navigation()
        } else {
            // 主动跳登录场景：CLEAR_TOP 复用栈底主界面并清掉其上的中间页（编辑资料/注册等），
            // 登录成功后直接落回主界面；SINGLE_TOP 防主界面恰在栈顶时被重建。
            build(KeyCode.Main.MAIN_PATH)
                .withFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .navigation()
        }
        sendFinish()
        sendToast(context.getString(R.string.login_success))
    }
}
