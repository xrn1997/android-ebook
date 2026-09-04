package com.ebook.api.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 评论列表分页响应（对齐服务端分页包裹结构：items/total/page/page_size）。
 *
 * 服务端键为 page_size，Kotlin 属性保持驼峰 [pageSize]，边界翻译由 [SerialName] 完成。
 */
@Serializable
data class CommentPage(
    val items: List<Comment> = emptyList(),
    val total: Long = 0L,
    val page: Int = 1,
    @SerialName("page_size")
    val pageSize: Int = 0
)
