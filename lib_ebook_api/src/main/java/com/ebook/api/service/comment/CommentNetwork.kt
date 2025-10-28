package com.ebook.api.service.comment

import com.ebook.api.config.API
import com.xrn1997.common.dto.RespDTO
import com.ebook.api.entity.Comment
import com.xrn1997.common.manager.RetrofitManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommentNetwork @Inject constructor() : CommentDataSource {

    init {
        //通过反射动态修改BaseUrl
        RetrofitManager.mHttpUrl.setHost(API.URL_HOST_COMMENT)
        RetrofitManager.mHttpUrl.setPort(API.URL_PORT_COMMENT)
    }

    private val networkApi = RetrofitManager.create(CommentService::class.java)

    override suspend fun addComment(
        token: String?,
        comment: Comment
    ): RespDTO<Comment> = networkApi.addComment(token, comment)


    override suspend fun deleteComment(
        token: String?,
        id: Long
    ): RespDTO<Int> = networkApi.deleteComment(token, id)

    override suspend fun getUserComments(
        token: String?,
        username: String
    ): RespDTO<List<Comment>> = networkApi.getUserComments(token, username)

    override suspend fun getChapterComments(
        token: String?,
        chapterUrl: String?
    ): RespDTO<List<Comment>> = networkApi.getChapterComments(token, chapterUrl)
}