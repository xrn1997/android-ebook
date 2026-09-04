package com.ebook.api.entity

import kotlinx.serialization.Serializable

/**
 * 注册请求（邮箱 + 验证码 + 密码；注册即激活建号、不发 token，对齐服务端注册契约）。
 *
 * @property email 邮箱（账号主标识）
 * @property code 6 位邮箱验证码
 * @property password 密码
 */
@Serializable
data class RegisterRequest(
    val email: String,
    val code: String,
    val password: String
)
