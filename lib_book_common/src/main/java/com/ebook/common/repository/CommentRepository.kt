package com.ebook.common.repository

import com.ebook.api.entity.CommentPage
import com.ebook.api.service.comment.CommentDataSource
import com.xrn1997.common.dto.RespDTO
import com.ebook.api.utils.CoroutineAdapter
import com.ebook.common.domain.BookComment
import com.ebook.common.domain.BookCommentPage
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
     * 删除评论（个人中心/书籍详情页，仅本人或管理员）。
     *
     * 后端删除成功时 data 为 null（SuccessMsg），以业务码 00000 为成功判据，
     * 不再依赖 data 非空（旧契约 data=1 已废弃）。
     */
    suspend fun deleteComment(id: Long): Result<Unit> =
        coroutineAdapter.safeApiCall { dataSource.deleteComment(id) }
            .mapCatching { }

    /**
     * 获取我的评论列表（身份取自 token，客户端按时间倒序展示；当前以大页一次性拉取，
     * 待该页接入分页后并入 [PAGE_SIZE]）。
     */
    suspend fun getUserComments(): Result<List<BookComment>> =
        queryCommentPage(MY_COMMENTS_PAGE_SIZE) { dataSource.getMyComments(1, MY_COMMENTS_PAGE_SIZE) }
            .mapCatching { it.items }

    // 添加评论（书籍详情页）
    suspend fun addComment(comment: BookComment): Result<BookComment> =
        mutateComment("添加评论失败") { dataSource.addComment(comment.toApiComment()) }
            .mapCatching { it.toBookComment() }

    /**
     * 分页获取评论（M2：按聚合键列表做并集查询）。
     *
     * 调用方传入一个或多个 `commentKey`（章键或书键）与页码，后端返回该页匹配的评论。
     * 翻页由调用方推进：首页 `page = 1`，后续页在上页结果合并完成后取 `page + 1`；
     * [pageSize] 缺省取 [PAGE_SIZE]。
     *
     * **空键列表直接返回空页、不发请求**：契约（M2 spec §3.2.1）规定 `comment_keys` 缺失时后端返回
     * **全局最新列表**，而 `CommentNetwork` 把空列表翻译成 `comment_keys=null`，正好命中该分支。
     * 于是调用方（章评论区）拿到的会是全站最新评论而不是空页——旧数据的 `commentKey` 可为 null，
     * 这条路径真的可达。收口在这里而不是各调用方：隐患出在网络层的空值翻译上，任何新调用方
     * 传空列表都会踩同一个坑。
     */
    suspend fun getComments(
        commentKeys: List<String>,
        page: Int,
        pageSize: Int = PAGE_SIZE,
    ): Result<BookCommentPage> =
        if (commentKeys.isEmpty()) {
            Result.success(BookCommentPage())
        } else {
            queryCommentPage(pageSize) { dataSource.getComments(commentKeys, page, pageSize) }
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

    /**
     * 评论分页查询：映射为领域页型（data 为空时兜底为空页），
     * [requestedPageSize] 是本次请求的页大小，返回条数达到它即视为可能还有下一页。
     */
    private suspend fun queryCommentPage(
        requestedPageSize: Int,
        block: suspend () -> RespDTO<CommentPage>
    ): Result<BookCommentPage> =
        coroutineAdapter.safeApiCall(block)
            .mapCatching { resp ->
                val items = resp.data?.items?.toBookCommentList() ?: emptyList()
                BookCommentPage(items, hasMore = items.size >= requestedPageSize)
            }

    /** 评论变更操作：data 为空时抛出指定错误 */
    private suspend fun <T> mutateComment(
        errorMessage: String,
        block: suspend () -> RespDTO<T>
    ): Result<T> =
        coroutineAdapter.safeApiCall(block)
            .mapCatching { resp -> resp.data ?: throw Exception(errorMessage) }

    companion object {
        /**
         * 书评页分页页大小。列表触底自动加载更多，页越大首屏等待与单请求载荷越重、
         * 自动翻页下没有收益；评论条目轻，20 条约几 KB，首屏快且翻页几乎无感。
         */
        private const val PAGE_SIZE = 20

        /**
         * 「我的评论」单次拉取页大小：该页当前一次性展示（未接入分页），页大小过小会
         * 重新引入「超出即静默截断」的悬崖，故维持大页；该页将来接入分页时并入 [PAGE_SIZE]。
         */
        private const val MY_COMMENTS_PAGE_SIZE = 100
    }
}
