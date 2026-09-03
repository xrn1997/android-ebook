package com.ebook.api.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 刷新token请求
 *
 * @property refreshToken 刷新token（线上键为 refresh_token）
 */
@Serializable
data class RefreshTokenRequest(
    @SerialName("refresh_token")
    val refreshToken: String
)
