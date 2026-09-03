package com.ebook.api.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 已登录改密请求（旧密码由服务端校验，A0210 旧密码错误）
 *
 * @property oldPassword 旧密码（线上键为 old_password）
 * @property newPassword 新密码（线上键为 new_password）
 */
@Serializable
data class ModifyPwdRequest(
    @SerialName("old_password")
    val oldPassword: String,
    @SerialName("new_password")
    val newPassword: String
)
