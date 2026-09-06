package com.ebook.book.mvvm.viewmodel

import androidx.lifecycle.viewModelScope
import com.ebook.api.utils.CoroutineAdapter
import com.ebook.book.R
import com.ebook.common.domain.BookComment
import com.ebook.common.domain.UserSessionManager
import com.ebook.common.util.DateUtil
import com.ebook.common.repository.CommentRepository
import com.xrn1997.common.util.Logger
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

    override fun refreshData() {
        viewModelScope.launch {
            // M2：按聚合键列表做并集查询；空列表时后端返回空结果
            val result = commentRepository.getComments(commentKeys)
            result.onSuccess { data ->
                val sortedComments = data.sortedByDescending {
                    DateUtil.parseTime(it.addTime, DateUtil.FormatType.yyyyMMddHHmm)
                }
                updateList(sortedComments)
                updateStopRefresh()
            }.onFailure { exception ->
                toastFailure(exception)
                updateStopRefresh()
            }
        }
    }

    override fun loadMore() {
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
                    toastFailure(exception)
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
                toastFailure(exception)
            }
        }
    }

    /**
     * 统一失败提示：已全局处置的会话过期只记日志、不重复弹 Toast（Q4：事件唯一出口）；
     * 其余业务异常走业务文案，本地异常走原始 message。
     */
    private fun toastFailure(exception: Throwable) {
        if (CoroutineAdapter.isSessionExpiredHandled(exception)) {
            Logger.w(TAG, "会话过期已由全局处置，本调用点静默（仅日志）：${exception.message}")
            return
        }
        if (exception is CoroutineAdapter.ApiException) {
            sendToast(exception.message())
        } else {
            sendToast("${exception.message}")
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
