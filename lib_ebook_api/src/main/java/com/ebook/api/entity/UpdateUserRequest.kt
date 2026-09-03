package com.ebook.api.entity

import kotlinx.serialization.Serializable

/**
 * 更新当前用户信息请求（对齐 ebook-server：PUT /api/users/me 部分更新语义）。
 *
 * 全部字段可选：服务端按「非空即更新」处理；昵称/头像修改统一走此端点，
 * 客户端历史独立端点（/users/me/nickname、/users/me/avatar multipart）已废弃。
 *
 * @property avatar 头像 URL（先经 POST /api/uploads/avatar 上传拿 URL，再在此提交）
 * @property email 邮箱（账号主标识，一般不改）
 * @property nickname 昵称
 * @property username 用户名（展示用）
 */
@Serializable
data class UpdateUserRequest(
    val avatar: String? = null,
    val email: String? = null,
    val nickname: String? = null,
    val username: String? = null
)
