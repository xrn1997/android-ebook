package com.ebook.api.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 登录/刷新响应载荷（服务端 TokenPair：双 token + 用户信息）。
 */
@Serializable
data class LoginDTO(
    var user: User? = null,
    var token: String? = null,
    // 线上键为 refresh_token：Kotlin 属性保持驼峰，边界翻译由 @SerialName 完成
    @SerialName("refresh_token")
    var refreshToken: String? = null
)
