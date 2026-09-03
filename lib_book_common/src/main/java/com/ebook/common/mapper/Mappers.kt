package com.ebook.common.mapper

import com.ebook.api.entity.Comment
import com.ebook.api.entity.LoginDTO
import com.ebook.api.entity.User
import com.ebook.common.domain.BookComment
import com.ebook.common.domain.UserSession

/**
 * Data Transfer Object 转领域模型的映射器扩展函数
 */

// ── LoginDTO → UserSession ──

fun LoginDTO.toUserSession(): UserSession = UserSession(
    userId = user?.id ?: 0,
    username = user?.username ?: "",
    nickname = user?.nickname ?: "",
    avatar = user?.image ?: "",
    token = requireNotNull(token) { "Login failed: token is null" },
    refreshToken = refreshToken ?: ""
)

// ── Comment → BookComment ──

fun Comment.toBookComment(): BookComment = BookComment(
    id = id,
    userId = user.id,
    username = user.nickname.ifEmpty { user.username },
    avatar = user.image,
    chapterUrl = chapterUrl,
    chapterName = chapterName,
    bookName = bookName,
    content = content,
    addTime = addTime
)

fun List<Comment>.toBookCommentList(): List<BookComment> = map { it.toBookComment() }

// ── BookComment → Comment（新建/发送用） ──

fun BookComment.toApiComment(): Comment = Comment().apply {
    id = this@toApiComment.id
    user = User().apply {
        id = this@toApiComment.userId
        username = this@toApiComment.username
        nickname = this@toApiComment.username
        image = this@toApiComment.avatar
    }
    chapterUrl = this@toApiComment.chapterUrl
    chapterName = this@toApiComment.chapterName
    bookName = this@toApiComment.bookName
    content = this@toApiComment.content
    addTime = this@toApiComment.addTime
}

fun List<BookComment>.toApiCommentList(): List<Comment> = map { it.toApiComment() }
