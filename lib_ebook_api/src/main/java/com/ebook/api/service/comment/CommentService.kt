package com.ebook.api.service.comment

import com.xrn1997.common.dto.RespDTO
import com.ebook.api.entity.Comment
import com.ebook.api.entity.CommentMigrateRequest
import com.ebook.api.entity.CommentMigrateResponse
import com.ebook.api.entity.CommentPage
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 评论服务接口（对齐服务端评论 RESTful 契约：POST/DELETE/GET 收口到 /api/comments 系端点）。
 *
 * M2 改动：
 * - 查询评论改用 `comment_keys`（逗号分隔的聚合键列表），替代旧 `chapter_url` 过滤
 * - 新增 `migrateMyComments` 端点：用户迁移自己的旧键评论到新键
 * - 创建评论请求体新增 `comment_key` 字段（必填）
 */
interface CommentService {
    //创建评论（需登录；content 必填，comment_key 必填，章节字段可选）
    @POST("/api/comments")
    @Headers("Content-Type:application/json;charset=UTF-8")
    suspend fun addComment(
        @Body comment: Comment
    ): RespDTO<Comment>

    //删除评论（需登录，仅本人或管理员，A0303 无权删除）
    @DELETE("/api/comments/{id}")
    @Headers("Content-Type:application/json;charset=UTF-8")
    suspend fun deleteComment(
        @Path("id") id: Long
    ): RespDTO<Unit>

    //我的评论列表（需登录，身份取自 token，分页；响应含 comment_key）
    @GET("/api/comments/my")
    @Headers("Content-Type:application/json;charset=UTF-8")
    suspend fun getMyComments(
        @Query("page") page: Int,
        @Query("page_size") pageSize: Int
    ): RespDTO<CommentPage>

    //查询评论（M2：comment_keys 逗号分隔的聚合键列表，返回并集）
    @GET("/api/comments")
    @Headers("Content-Type:application/json;charset=UTF-8")
    suspend fun getComments(
        @Query("comment_keys") commentKeys: String?,
        @Query("page") page: Int,
        @Query("page_size") pageSize: Int
    ): RespDTO<CommentPage>

    //迁移我的评论（M2：将当前用户的旧 key 评论批量改到新 key）
    @POST("/api/comments/migrate")
    @Headers("Content-Type:application/json;charset=UTF-8")
    suspend fun migrateMyComments(
        @Body request: CommentMigrateRequest
    ): RespDTO<CommentMigrateResponse>
}
