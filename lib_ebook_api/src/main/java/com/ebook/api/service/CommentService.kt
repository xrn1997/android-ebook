package com.ebook.api.service

import com.ebook.api.config.API
import com.ebook.api.dto.RespDTO
import com.ebook.api.entity.Comment
import io.reactivex.rxjava3.core.Observable
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
    fun addComment(
        @Header("Authorization") token: String?,
        @Body comment: Comment
    ): Observable<RespDTO<Comment>>

    //删除评论
    @POST("/comments/delete/{id}")
    @Headers("Content-Type:application/json;charset=UTF-8")
    fun deleteComment(
        @Header("Authorization") token: String?,
        @Path("id") id: Long
    ): Observable<RespDTO<Int>>

    //获得用户评论
    @GET("/comments/query/name/{username}")
    @Headers("Content-Type:application/json;charset=UTF-8")
    fun getUserComments(
        @Header("Authorization") token: String?,
        @Path("username") username: String
    ): Observable<RespDTO<List<Comment>>>

    //获得章节评论
    @GET("/comments/query/chapter")
    @Headers("Content-Type:application/json;charset=UTF-8")
    fun getChapterComments(
        @Header("Authorization") token: String?,
        @Query("chapterUrl") chapterUrl: String?
    ): Observable<RespDTO<List<Comment>>>
}
