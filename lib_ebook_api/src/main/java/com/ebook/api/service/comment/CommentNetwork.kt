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
        toCommentKeysParam(commentKeys),
        page,
        pageSize
    )

    override suspend fun migrateMyComments(oldKey: String, newKey: String): RespDTO<CommentMigrateResponse> =
        networkApi.migrateMyComments(CommentMigrateRequest(oldKey, newKey))
}

/** 服务端 `comment_keys` 的条数上限，与后端 model.MaxCommentFilterKeys 同值：超出即 A0400 */
private const val MAX_FILTER_KEYS = 50

/**
 * 把聚合键列表整理成 `comment_keys` 查询参数：去重 → 截到服务端上限 → 逗号连接；空列表为 null。
 *
 * 去重是免费的（重复键不会多带出一行，白占一个绑定位）；截断则必须做：服务端对超出
 * [MAX_FILTER_KEYS] 的入参直接回 A0400，而一本书的别名键数量由书源脚本决定、客户端这边
 * 没有上界。与其让整页评论区因为第 51 个键而拿不到数据，不如返回前 50 个键的并集。
 *
 * 单独成函数是为了让这条形状可被纯 JVM 用例断言——[CommentNetwork] 自己持有 Retrofit 实例，
 * 没有可注入的测试缝。
 */
internal fun toCommentKeysParam(commentKeys: List<String>): String? =
    commentKeys.distinct().take(MAX_FILTER_KEYS).joinToString(",").ifEmpty { null }
