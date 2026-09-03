package com.ebook.api.entity

import kotlinx.serialization.Serializable

/**
 * 发送邮箱验证码请求（注册 / 忘记密码共用，对齐 ebook-server ADR-0002：纯邮箱，不依赖用户名）
 *
 * @property email 目标邮箱（发码唯一入参）
 */
@Serializable
data class SendCodeRequest(
    val email: String
)
