package com.ebook.book.mvvm.viewmodel

import androidx.lifecycle.viewModelScope
import com.ebook.book.R
import com.ebook.common.domain.BookComment
import com.ebook.common.domain.CommentTime
import com.ebook.common.domain.UserSessionManager
import com.ebook.common.repository.CommentRepository
import com.ebook.common.util.reportFailure
import com.xrn1997.common.BaseApplication.Companion.context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import com.xrn1997.common.mvvm.viewmodel.BaseRefreshViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BookCommentsViewModel @Inject constructor(
    private val commentRepository: CommentRepository,
    private val userSessionManager: UserSessionManager
) : BaseRefreshViewModel<BookComment, CommentRepository>(commentRepository) {

    /**
     * 当前会话用户 id（null = 未登录）。
     *
     * 取自 [UserSessionManager]（认证状态的唯一 seam），供评论本人判定使用：
     * 判身份一律用 userId，不用展示名——昵称可重复且仅展示用（见 ADR-0009），
     * 用展示名比对会让设过昵称的用户永久删不掉自己的评论。
     */
    val currentUserId: Flow<Long?> = userSessionManager.currentUser.map { it?.userId }

    @JvmField
    var comment: BookComment = BookComment(
        id = 0, userId = 0, username = "", avatar = "",
        commentKey = null,
        chapterUrl = null, chapterName = null, bookName = null,
        content = null, addTime = ""
    )

    /**
     * M2 查询用聚合键列表：阅读器传入多个章键（跨源合并）时全量查询；
     * 与 [comment] 的 `commentKey` 分离——后者用于新发评论的归属键，前者用于查询范围。
     */
    var commentKeys: List<String> = emptyList()

    val mVoidSingleLiveEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * 下一页页码（页大小由 [CommentRepository] 的缺省值统一）。
     *
     * 初始即指向第 2 页：首屏前列表为空，[com.xrn1997.common.ui.RefreshableList] 的
     * 短列表守卫不会触发 loadMore，该初值不会被消费；刷新成功后无条件重置回 2。
     */
    private var nextPage = 2

    /**
     * 是否还有下一页（VM 侧闸门 + 经 [updateHasMoreData] 同步给刷新状态机）。
     * 刷新成功按返回页是否为整页重算；加载到短页/空页后置 false。
     */
    private var hasMoreData = true

    /**
     * 加载更多在途闸门：触底信号可能在滚动中连续到来，只放行一笔在途请求。
     * 刷新不做此闸门——下拉刷新总是允许的，刷新会用新首页整体替换列表。
     */
    private var loadMoreInProgress = false

    override fun refreshData() {
        viewModelScope.launch {
            commentRepository.getComments(commentKeys, page = 1)
                .onSuccess { page ->
                    updateList(mergeCommentPage(emptyList(), page.items))
                    nextPage = 2
                    hasMoreData = page.hasMore
                    // 先结束刷新再同步 hasMore：状态机在收到刷新结束信号时会自动复位 hasMore，
                    // 显式信号随后到达才能以本页的真实结论覆盖复位值
                    updateStopRefresh()
                    updateHasMoreData(page.hasMore)
                }
                .onFailure { exception ->
                    reportFailure(exception)
                    updateStopRefresh()
                }
        }
    }

    override fun loadMore() {
        if (loadMoreInProgress || !hasMoreData) return
        loadMoreInProgress = true
        val page = nextPage
        viewModelScope.launch {
            var success = false
            try {
                commentRepository.getComments(commentKeys, page = page)
                    .onSuccess { newPage ->
                        updateList(mergeCommentPage(list.value, newPage.items))
                        nextPage = page + 1
                        hasMoreData = newPage.hasMore
                        updateHasMoreData(newPage.hasMore)
                        success = true
                    }
                    .onFailure { exception ->
                        reportFailure(exception)
                    }
            } finally {
                loadMoreInProgress = false
                updateStopLoadMore(success)
            }
        }
    }

    fun addComment(comments: String) {
        if (comments.isNotEmpty()) {
            // 与本人判定同源：会话里的 userId；未登录取 0，交由服务端按 token 拒绝
            val userId = userSessionManager.currentUser.value?.userId ?: 0L
            val updatedComment = comment.copy(
                userId = userId,
                content = comments
            )
            viewModelScope.launch {
                val result = commentRepository.addComment(updatedComment)
                result.onSuccess {
                    comment = updatedComment
                    mVoidSingleLiveEvent.tryEmit(Unit)
                    refreshData()
                }.onFailure { exception ->
                    reportFailure(exception)
                }
            }
        } else {
            sendToast(context.getString(R.string.comment_empty))
        }
    }

    fun deleteComment(id: Long) {
        viewModelScope.launch {
            val result = commentRepository.deleteComment(id)
            result.onSuccess {
                sendToast(context.getString(R.string.comment_delete_success))
                refreshData()
            }.onFailure { exception ->
                reportFailure(exception)
            }
        }
    }

}

/**
 * 评论本人判定（纯函数，便于 JVM 单测）。
 *
 * 用 `userId` 而非展示名比对：展示名（昵称）可重复且仅用于展示（见 ADR-0009），
 * 且 [com.ebook.common.mapper.toBookComment] 填的是「昵称优先」的值，
 * 与登录名比对必然对设过昵称的用户失配。
 *
 * 要求 `currentUserId > 0`：路由参数组装的占位评论 `userId = 0`，未登录时若不加这道
 * 闸门会与自己比出「本人」的假阳性。
 */
fun isOwnComment(commentUserId: Long, currentUserId: Long?): Boolean =
    currentUserId != null && currentUserId > 0L && commentUserId == currentUserId

/**
 * 把一页新取的评论合并进既有列表（纯函数，便于 JVM 单测）。
 *
 * - 按 id 去重：刷新与加载更多存在并发窗口，同一页可能在重置后的游标下被再次取回；
 *   跨源聚合查询本身也可能在键变化后返回已见条目。
 * - 全量按时间倒序重排：分页按服务端序发放，只有全局重排能让任何窗口内到达的页
 *   收敛到一致的展示序。排序口径收口在 [CommentTime]：必须到秒，
 *   按分钟解析会让同分钟内的评论排成随机序。
 */
internal fun mergeCommentPage(
    existing: List<BookComment>,
    fetched: List<BookComment>,
): List<BookComment> =
    (existing + fetched)
        .distinctBy { it.id }
        .sortedByDescending { CommentTime.sortMillis(it.addTime) }
