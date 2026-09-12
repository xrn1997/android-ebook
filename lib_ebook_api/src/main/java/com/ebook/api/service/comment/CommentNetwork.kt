package com.ebook.api.service.comment

import com.ebook.api.RetrofitBuilder
import com.ebook.api.config.API
import com.ebook.api.entity.CommentMigrateRequest
import com.ebook.api.entity.CommentMigrateResponse
import com.xrn1997.common.dto.RespDTO
import com.ebook.api.entity.Comment
import com.ebook.api.entity.CommentPage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 真实后端的评论数据源：透传到 [CommentService] Retrofit 接口。
 *
 * M2 改动：查询改走 `comment_keys`（逗号分隔），新增迁移端点透传。
 */
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

    override suspend fun getComments(
        commentKeys: List<String>,
        page: Int,
        pageSize: Int
    ): RespDTO<CommentPage> = networkApi.getComments(
        commentKeys.joinToString(",").ifEmpty { null },
        page,
        pageSize
    )

    override suspend fun migrateMyComments(oldKey: String, newKey: String): RespDTO<CommentMigrateResponse> =
        networkApi.migrateMyComments(CommentMigrateRequest(oldKey, newKey))
}
