package com.ebook.api.service.comment

import com.xrn1997.common.dto.RespDTO
import com.ebook.api.entity.Comment
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
 * 与旧契约（/comments/save 等）的差异：
 * - 创建/删除/查询统一收口到 /api/comments 系端点，方法语义化（POST/DELETE/GET）
 * - 我的评论从「按 username 查询」改为 GET /api/comments/my（身份取自 token）
 * - 章节评论经 GET /api/comments 的 chapter_url 过滤（端点签名仍保留 book_name 参数，
 *   但客户端恒传 null——后端按 URL 聚合即可，见 ADR-0013 决策）
 * - 列表统一返回 [CommentPage] 分页包裹（items/total/page/page_size）
 */
interface CommentService {
    //创建评论（需登录；content 必填，章节字段可选，字段语义对齐服务端契约）
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

    //我的评论列表（需登录，身份取自 token，分页）
    @GET("/api/comments/my")
    @Headers("Content-Type:application/json;charset=UTF-8")
    suspend fun getMyComments(
        @Query("page") page: Int,
        @Query("page_size") pageSize: Int
    ): RespDTO<CommentPage>

    //章节评论列表（公开；chapter_url 可选，提供时返回该章节评论，否则全局列表）
    @GET("/api/comments")
    @Headers("Content-Type:application/json;charset=UTF-8")
    suspend fun getChapterComments(
        @Query("chapter_url") chapterUrl: String?,
        @Query("book_name") bookName: String?,
        @Query("page") page: Int,
        @Query("page_size") pageSize: Int
    ): RespDTO<CommentPage>
}
