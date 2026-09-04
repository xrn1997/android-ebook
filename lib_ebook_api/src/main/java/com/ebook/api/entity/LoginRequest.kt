package com.ebook.api.entity

import kotlinx.serialization.Serializable

/**
 * 登录请求（邮箱为登录主标识，对齐服务端登录契约）。
 *
 * @property email 邮箱（登录主标识）
 * @property password 密码
 */
@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)
