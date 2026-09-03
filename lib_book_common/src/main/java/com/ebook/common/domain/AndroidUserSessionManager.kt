package com.ebook.common.domain

import android.app.Application
import android.content.Context
import com.ebook.common.event.KeyCode
import com.ebook.common.util.SPUtil
import com.xrn1997.common.di.TokenHolder
import com.xrn1997.common.mvvm.model.BaseModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UserSessionManager 的 Android 实现
 *
 * 职责：
 * - 管理内存中的状态（StateFlow）
 * - 持久化到 SharedPreferences
 * - 登录/登出时同步 token 到 lib_common 的 TokenHolder（AuthInterceptor 从那里取）
 * - 兼容 LoginInterceptor（同时写入 spUtils 文件）
 */
@Singleton
class AndroidUserSessionManager @Inject constructor(
    application: Application,
    private val tokenHolder: TokenHolder,
) : BaseModel(), UserSessionManager {

    private val sp by lazy {
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _isLoggedIn = MutableStateFlow(sp.getBoolean(KEY_IS_LOGGED_IN, false))
    override val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _currentUser = MutableStateFlow(loadSessionFromSp())
    override val currentUser: StateFlow<UserSession?> = _currentUser.asStateFlow()

    init {
        // 启动恢复：将持久化的 token 写入 TokenHolder，供 AuthInterceptor 使用
        tokenHolder.setToken(_currentUser.value?.token)
        // 安全债清理（一次性、幂等）：旧版本会把明文密码落盘到两处 SP，
        // 密码改为彻底不落盘后，升级设备上遗留的旧键在此抹除（见 ADR-0008）
        sp.edit().remove(LEGACY_KEY_PASSWORD).apply()
        SPUtil.remove(LEGACY_SP_PASSWORD)
    }

    override fun getRefreshToken(): String? {
        if (!isLoggedIn.value) return null
        return sp.getString(KEY_REFRESH_TOKEN, null)
    }

    override fun getToken(): String? = tokenHolder.token

    override suspend fun saveSession(session: UserSession, refreshToken: String) {
        // 更新内存状态
        _currentUser.value = session
        _isLoggedIn.value = true
        tokenHolder.setToken(session.token)

        // 写入 user_session 文件
        // 注：access token 只驻内存（TokenHolder），不落盘——冷启动时为空，
        // 首个请求 A0230 会用 refresh token 静默轮换（见 ADR-0011 / Q2 权衡）
        sp.edit()
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putString(KEY_USER_ID, session.userId.toString())
            .putString(KEY_USERNAME, session.username)
            .putString(KEY_NICKNAME, session.nickname)
            .putString(KEY_AVATAR, session.avatar)
            .apply()

        // 兼容 LoginInterceptor：同时写入 spUtils 文件
        SPUtil.put(KeyCode.Login.SP_IS_LOGIN, true)
        SPUtil.put(KeyCode.Login.SP_USERNAME, session.username)
        SPUtil.put(KeyCode.Login.SP_NICKNAME, session.nickname)
        SPUtil.put(KeyCode.Login.SP_USER_ID, session.userId)
        SPUtil.put(KeyCode.Login.SP_IMAGE, session.avatar)
    }

    override suspend fun rotateCredentials(accessToken: String, refreshToken: String) {
        // 仅会话已登录时轮换；无会话则无处续期
        val current = _currentUser.value ?: return
        // 只更新内存中的 token，身份字段原样保留
        _currentUser.value = current.copy(token = accessToken)
        tokenHolder.setToken(accessToken)

        // 只落盘 refresh token（access 只驻内存）；绝不触碰身份键与兼容 SP_* 键
        sp.edit()
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .apply()
    }

    override fun clearSession() {
        _currentUser.value = null
        _isLoggedIn.value = false
        tokenHolder.clear()

        // 清除 user_session 文件
        sp.edit()
            .remove(KEY_IS_LOGGED_IN)
            .remove(KEY_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USERNAME)
            .remove(KEY_NICKNAME)
            .remove(KEY_AVATAR)
            .apply()

        // 兼容 LoginInterceptor：同时清除 spUtils 文件
        SPUtil.clearAuthData()
    }

    private fun loadSessionFromSp(): UserSession? {
        if (!sp.getBoolean(KEY_IS_LOGGED_IN, false)) return null

        return UserSession(
            userId = sp.getString(KEY_USER_ID, null)?.toLongOrNull() ?: 0L,
            username = sp.getString(KEY_USERNAME, "") ?: "",
            nickname = sp.getString(KEY_NICKNAME, "") ?: "",
            avatar = sp.getString(KEY_AVATAR, "") ?: "",
            // access token 只驻内存：不留盘，冷启动 token 为空，交由首请求 A0230 静默轮换
            token = ""
        )
    }

    companion object {
        private const val PREFS_NAME = "user_session"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_TOKEN = "token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_AVATAR = "avatar"

        // 旧版遗留键（明文密码），仅启动清理用，不得再写入；常量已删，故用字面量钉死
        private const val LEGACY_KEY_PASSWORD = "password"
        private const val LEGACY_SP_PASSWORD = "sp_password"
    }
}
