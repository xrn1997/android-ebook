package com.ebook.book.comments

import androidx.lifecycle.viewModelScope
import com.ebook.book.R
import com.ebook.common.domain.BookComment
import com.ebook.common.domain.CommentTime
import com.ebook.common.domain.UserSessionManager
import com.ebook.common.pagination.PageSession
import com.ebook.common.pagination.PageSignal
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
     * 翻页会话（ADR-0057）：持有游标、在途闸门与「到底」置位，信号时序也由它给出。
     *
     * 起始页 2——首页 1 由 [refreshData] 直接请求（会话不接管刷新的取数，只在结果落地后
     * 用 [PageSession.onRefreshResult] 复位游标并同步「还有没有更多」）。原先散在本类的
     * `nextPage`/`hasMoreData`/`loadMoreInProgress` 三个裸字段与「先停刷新再同步 hasMore」的
     * 顺序纪律都收进会话，本类只把服务端的 [BookCommentPage.hasMore] 算成 boolean 交进去、
     * 再按序 apply 返回的 [PageSignal]。
     */
    private val pageSession = PageSession(startPage = 2)

    override fun refreshData() {
        pageSession.startRefresh()
        viewModelScope.launch {
            commentRepository.getComments(commentKeys, page = 1)
                .onSuccess { page ->
                    updateList(mergeCommentPage(emptyList(), page.items))
                    applySignals(pageSession.onRefreshResult(1, page.hasMore))
                }
                .onFailure { exception ->
                    reportFailure(exception)
                    updateStopRefresh()
                }
        }
    }

    override fun loadMore() {
        val page = pageSession.requestLoadMore() ?: return
        viewModelScope.launch {
            commentRepository.getComments(commentKeys, page = page)
                .onSuccess { newPage ->
                    updateList(mergeCommentPage(list.value, newPage.items))
                    applySignals(pageSession.onLoadMoreSuccess(page, newPage.hasMore))
                }
                .onFailure { exception ->
                    reportFailure(exception)
                    applySignals(pageSession.onLoadMoreFailure())
                }
        }
    }

    /** 按序把会话给出的信号落到基类刷新状态机上；顺序由会话保证，这里只转发（ADR-0057）。 */
    private fun applySignals(signals: List<PageSignal>) {
        for (signal in signals) {
            when (signal) {
                PageSignal.StopRefresh -> updateStopRefresh()
                is PageSignal.HasMore -> updateHasMoreData(signal.value)
                is PageSignal.StopLoadMore -> updateStopLoadMore(signal.success)
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
