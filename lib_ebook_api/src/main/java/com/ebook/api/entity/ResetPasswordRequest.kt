package com.ebook.api.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 验证码重置密码请求（忘记密码；纯邮箱验证，不依赖用户名）
 *
 * @property email 账号邮箱
 * @property code 6 位邮箱验证码（服务端在 reset 时校验，客户端不做本地校验）
 * @property newPassword 新密码（线上键为 new_password）
 */
@Serializable
data class ResetPasswordRequest(
    val email: String,
    val code: String,
    @SerialName("new_password")
    val newPassword: String
)
