package com.ebook.common.repository

import com.ebook.api.entity.CommentPage
import com.ebook.api.service.comment.CommentDataSource
import com.xrn1997.common.dto.RespDTO
import com.ebook.api.utils.CoroutineAdapter
import com.ebook.common.domain.BookComment
import com.ebook.common.mapper.toApiComment
import com.ebook.common.mapper.toBookComment
import com.ebook.common.mapper.toBookCommentList
import com.xrn1997.common.mvvm.model.BaseModel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommentRepository @Inject constructor(
    private val dataSource: CommentDataSource,
    private val coroutineAdapter: CoroutineAdapter
) : BaseModel() {

    /**
     * 删除评论（个人中心/章节评论区，仅本人或管理员）。
     *
     * 后端删除成功时 data 为 null（SuccessMsg），以业务码 00000 为成功判据，
     * 不再依赖 data 非空（旧契约 data=1 已废弃）。
     */
    suspend fun deleteComment(id: Long): Result<Unit> =
        coroutineAdapter.safeApiCall { dataSource.deleteComment(id) }
            .mapCatching { }

    /**
     * 获取我的评论列表（身份取自 token，客户端按时间倒序展示，先取第一页全量）。
     */
    suspend fun getUserComments(): Result<List<BookComment>> =
        queryCommentPage { dataSource.getMyComments(1, DEFAULT_PAGE_SIZE) }

    // 添加评论（书籍详情页/章节评论区）
    suspend fun addComment(comment: BookComment): Result<BookComment> =
        mutateComment("添加评论失败") { dataSource.addComment(comment.toApiComment()) }
            .mapCatching { it.toBookComment() }

    /**
     * 获取评论列表（M2：按聚合键列表做并集查询）。
     *
     * 调用方传入一个或多个 `commentKey`（章键或书键），后端返回所有匹配的评论。
     *
     * **空列表直接返回空、不发请求**：契约（M2 spec §3.2.1）规定 `comment_keys` 缺失时后端返回
     * **全局最新列表**，而 `CommentNetwork` 把空列表翻译成 `comment_keys=null`，正好命中该分支。
     * 于是调用方（章评论区）拿到的会是全站最新评论而不是空页——旧数据的 `commentKey` 可为 null，
     * 这条路径真的可达。收口在这里而不是各调用方：隐患出在网络层的空值翻译上，任何新调用方
     * 传空列表都会踩同一个坑。
     */
    suspend fun getComments(commentKeys: List<String>): Result<List<BookComment>> =
        if (commentKeys.isEmpty()) {
            Result.success(emptyList())
        } else {
            queryCommentPage { dataSource.getComments(commentKeys, 1, DEFAULT_PAGE_SIZE) }
        }

    /**
     * 迁移当前用户的旧键评论到新键（M2：换源后保留评论历史）。
     *
     * 返回迁移条数（契约见 M2 spec §4.4），供调用方展示确认文案；
     * 响应 DTO 是传输层细节，不出本仓库层。
     */
    suspend fun migrateMyComments(oldKey: String, newKey: String): Result<Int> =
        coroutineAdapter.safeApiCall { dataSource.migrateMyComments(oldKey, newKey) }
            .mapCatching { resp -> resp.data?.migratedCount ?: throw Exception("迁移评论失败") }

    /** 评论分页查询：从 [CommentPage.items] 取列表，data 为空时兜底为空列表 */
    private suspend fun queryCommentPage(
        block: suspend () -> RespDTO<CommentPage>
    ): Result<List<BookComment>> =
        coroutineAdapter.safeApiCall(block)
            .mapCatching { resp -> resp.data?.items?.toBookCommentList() ?: emptyList() }

    /** 评论变更操作：data 为空时抛出指定错误 */
    private suspend fun <T> mutateComment(
        errorMessage: String,
        block: suspend () -> RespDTO<T>
    ): Result<T> =
        coroutineAdapter.safeApiCall(block)
            .mapCatching { resp -> resp.data ?: throw Exception(errorMessage) }

    companion object {
        /** 客户端当前不分页拉取（一次性全量），取第一页大页；后端默认 page_size=10 由显式传参覆盖 */
        private const val DEFAULT_PAGE_SIZE = 100
    }
}
