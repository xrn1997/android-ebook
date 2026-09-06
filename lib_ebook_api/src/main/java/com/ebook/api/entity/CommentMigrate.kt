package com.ebook.api.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 迁移我的评论请求体（M2）：将当前用户的旧 comment_key 评论批量改到新 key。
 *
 * 服务端按 `user_id` 过滤，仅修改当前登录用户自己的评论。
 */
@Serializable
data class CommentMigrateRequest(
    @SerialName("old_key")
    val oldKey: String,
    @SerialName("new_key")
    val newKey: String
)

/**
 * 迁移我的评论响应体（M2）：返回迁移条数。
 */
@Serializable
data class CommentMigrateResponse(
    @SerialName("migrated_count")
    val migratedCount: Int
)
