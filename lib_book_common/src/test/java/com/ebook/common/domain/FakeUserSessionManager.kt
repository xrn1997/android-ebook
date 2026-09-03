package com.ebook.common.domain

import com.xrn1997.common.di.TokenHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * UserSessionManager 的内存实现，用于纯 JVM 测试
 *
 * 同步 token 到 [TokenHolder]，与 [AndroidUserSessionManager] 的生产语义一致
 */
class FakeUserSessionManager : UserSessionManager {
    private val _isLoggedIn = MutableStateFlow(false)
    override val isLoggedIn: StateFlow<Boolean> = _isLoggedIn

    private val _currentUser = MutableStateFlow<UserSession?>(null)
    override val currentUser: StateFlow<UserSession?> = _currentUser

    private val tokenHolder = TokenHolder()

    var savedSessions = mutableListOf<Pair<UserSession, String>>()
        private set

    /** 轮换记录：Pair(accessToken, refreshToken)，仅轮换凭证，不重建身份 */
    var rotated = mutableListOf<Pair<String, String>>()
        private set

    var clearCount = 0
        private set

    override suspend fun saveSession(session: UserSession, refreshToken: String) {
        _currentUser.value = session
        _isLoggedIn.value = true
        tokenHolder.setToken(session.token)
        savedSessions.add(session to refreshToken)
    }

    override suspend fun rotateCredentials(accessToken: String, refreshToken: String) {
        val current = _currentUser.value ?: return
        // 与生产语义一致：只更 token，身份字段原样保留
        _currentUser.value = current.copy(token = accessToken)
        tokenHolder.setToken(accessToken)
        rotated.add(accessToken to refreshToken)
    }

    override fun clearSession() {
        _currentUser.value = null
        _isLoggedIn.value = false
        tokenHolder.clear()
        clearCount++
    }

    override fun getToken(): String? = tokenHolder.token

    override fun getRefreshToken(): String? {
        if (!isLoggedIn.value) return null
        // 取最近一次写入的 refresh token（轮换优先，其次登录保存）
        return rotated.lastOrNull()?.second ?: savedSessions.lastOrNull()?.second
    }

    /**
     * 重置状态（用于测试）
     */
    fun reset() {
        _currentUser.value = null
        _isLoggedIn.value = false
        tokenHolder.clear()
        savedSessions.clear()
        rotated.clear()
        clearCount = 0
    }
}
