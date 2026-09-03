package com.ebook.api.service.comment

import com.xrn1997.common.dto.RespDTO
import com.ebook.api.entity.Comment
import com.ebook.api.entity.CommentPage

interface CommentDataSource {
    //添加评论（登录；章节字段可选）
    suspend fun addComment(comment: Comment): RespDTO<Comment>

    //删除评论（登录，仅本人或管理员）
    suspend fun deleteComment(id: Long): RespDTO<Unit>

    //我的评论列表（登录，身份取自 token）
    suspend fun getMyComments(page: Int, pageSize: Int): RespDTO<CommentPage>

    //章节评论列表（公开，chapter_url 过滤 + 分页）
    suspend fun getChapterComments(
        chapterUrl: String?,
        bookName: String?,
        page: Int,
        pageSize: Int
    ): RespDTO<CommentPage>
}
