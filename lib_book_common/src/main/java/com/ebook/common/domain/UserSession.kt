package com.ebook.common.domain

/**
 * 登录会话信息，替代 [com.ebook.api.entity.LoginDTO] 作为跨模块 seam 类型
 */
data class UserSession(
    val userId: Long,
    val username: String,
    val nickname: String,
    val avatar: String,
    val token: String,
    /**
     * 仅登录瞬时的刷新凭证：登录结果映射时填充，供 saveSession 持久化。
     * 会话恢复/静默刷新后本字段不再维护（恒为空串），读取请统一走 [UserSessionManager.getRefreshToken]。
     */
    val refreshToken: String = ""
)
