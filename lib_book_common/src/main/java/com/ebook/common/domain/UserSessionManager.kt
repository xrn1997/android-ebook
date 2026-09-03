package com.ebook.common.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * 用户会话管理接口 - 认证状态的唯一 seam
 *
 * 设计原则：
 * - 纯 Kotlin 接口，无 Android 依赖
 * - 所有状态读写通过此接口
 * - 实现类处理持久化细节
 */
interface UserSessionManager {
    /**
     * 是否已登录
     */
    val isLoggedIn: StateFlow<Boolean>

    /**
     * 当前用户会话信息
     */
    val currentUser: StateFlow<UserSession?>

    /**
     * 保存会话信息（登录/注册建立会话：身份 + 双 token）
     *
     * @param session 用户会话信息
     * @param refreshToken 刷新token
     */
    suspend fun saveSession(session: UserSession, refreshToken: String)

    /**
     * 轮换双凭证（静默刷新专用）
     *
     * 只更新 access token（内存，经 TokenHolder）与 refresh token（落盘），
     * **绝不触碰用户身份字段**——refresh 语义只是续期凭证，不回填/改写身份
     * （见 ADR-0011：刷新端点不再返回 user，与身份解耦）。
     *
     * @param accessToken 新 access token
     * @param refreshToken 新 refresh token
     */
    suspend fun rotateCredentials(accessToken: String, refreshToken: String)

    /**
     * 清除会话信息（退出登录）
     */
    fun clearSession()

    /**
     * 获取当前会话 token（运行时令牌）
     *
     * @return token 字符串，未登录或未保存 token 时返回 null
     */
    fun getToken(): String? = null

    /**
     * 获取保存的刷新token
     *
     * @return 刷新token字符串，未登录或未保存时返回 null
     */
    fun getRefreshToken(): String?
}
