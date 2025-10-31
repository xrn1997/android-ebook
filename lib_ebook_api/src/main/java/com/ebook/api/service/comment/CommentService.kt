package com.ebook.api.service.comment

import com.xrn1997.common.dto.RespDTO
import com.ebook.api.entity.Comment
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface CommentService {
    //添加评论
    @POST("/comments/save")
    @Headers("Content-Type:application/json;charset=UTF-8")
    suspend fun addComment(
        @Header("Authorization") token: String?,
        @Body comment: Comment
    ): RespDTO<Comment>

    //删除评论
    @POST("/comments/delete/{id}")
    @Headers("Content-Type:application/json;charset=UTF-8")
    fun deleteComment(
        @Header("Authorization") token: String?,
        @Path("id") id: Long
    ): RespDTO<Int>

    //获得用户评论
    @GET("/comments/query/name/{username}")
    @Headers("Content-Type:application/json;charset=UTF-8")
    fun getUserComments(
        @Header("Authorization") token: String?,
        @Path("username") username: String
    ): RespDTO<List<Comment>>

    //获得章节评论
    @GET("/comments/query/chapter")
    @Headers("Content-Type:application/json;charset=UTF-8")
    fun getChapterComments(
        @Header("Authorization") token: String?,
        @Query("chapterUrl") chapterUrl: String?
    ): RespDTO<List<Comment>>
}