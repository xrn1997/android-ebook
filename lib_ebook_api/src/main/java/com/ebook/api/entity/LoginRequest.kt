package com.ebook.api.entity

import kotlinx.serialization.Serializable

/**
 * 登录请求（对齐 ebook-server ADR-0002：邮箱为登录主标识）。
 *
 * @property email 邮箱（登录主标识）
 * @property password 密码
 */
@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)
