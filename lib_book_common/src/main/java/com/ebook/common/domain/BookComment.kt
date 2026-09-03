package com.ebook.common.domain

/**
 * 书籍评论领域模型，替代 [com.ebook.api.entity.Comment]
 * 扁平化结构（无嵌套 User 对象），与 API 实体解耦
 */
data class BookComment(
    val id: Long,
    val userId: Long,
    val username: String,
    val avatar: String,
    val chapterUrl: String?,
    val chapterName: String?,
    val bookName: String?,
    val content: String?,
    val addTime: String
)
