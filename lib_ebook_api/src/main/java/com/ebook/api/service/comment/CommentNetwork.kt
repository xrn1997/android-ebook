package com.ebook.api.service.comment

import com.ebook.api.RetrofitBuilder
import com.ebook.api.config.API
import com.xrn1997.common.dto.RespDTO
import com.ebook.api.entity.Comment
import com.ebook.api.entity.CommentPage
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommentNetwork @Inject constructor(
    retrofitBuilder: RetrofitBuilder
) : CommentDataSource {
    private val networkApi = retrofitBuilder.getRetrofitObject(
        "http://${API.URL_HOST_COMMENT}:${API.URL_PORT_COMMENT}/"
    ).create(CommentService::class.java)

    override suspend fun addComment(comment: Comment): RespDTO<Comment> =
        networkApi.addComment(comment)

    override suspend fun deleteComment(id: Long): RespDTO<Unit> =
        networkApi.deleteComment(id)

    override suspend fun getMyComments(page: Int, pageSize: Int): RespDTO<CommentPage> =
        networkApi.getMyComments(page, pageSize)

    override suspend fun getChapterComments(
        chapterUrl: String?,
        bookName: String?,
        page: Int,
        pageSize: Int
    ): RespDTO<CommentPage> = networkApi.getChapterComments(chapterUrl, bookName, page, pageSize)
}
