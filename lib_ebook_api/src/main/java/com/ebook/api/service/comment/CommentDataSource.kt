package com.ebook.api.service.comment

import com.xrn1997.common.dto.RespDTO
import com.ebook.api.entity.Comment

interface CommentDataSource {
    //添加评论
    suspend fun addComment(token: String?, comment: Comment): RespDTO<Comment>

    //删除评论
    suspend fun deleteComment(token: String?, id: Long): RespDTO<Int>

    //获得用户评论
    suspend fun getUserComments(token: String?, username: String): RespDTO<List<Comment>>

    //获得章节评论
    suspend fun getChapterComments(token: String?, chapterUrl: String?): RespDTO<List<Comment>>
}