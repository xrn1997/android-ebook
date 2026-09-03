package com.ebook.book.mvvm.viewmodel

import androidx.lifecycle.viewModelScope
import com.ebook.api.utils.CoroutineAdapter
import com.ebook.book.R
import com.ebook.common.domain.BookComment
import com.ebook.common.event.KeyCode
import com.ebook.common.util.DateUtil
import com.ebook.common.util.SPUtil
import com.ebook.common.repository.CommentRepository
import com.xrn1997.common.util.Logger
import com.xrn1997.common.BaseApplication.Companion.context
import kotlinx.coroutines.flow.MutableSharedFlow
import com.xrn1997.common.mvvm.viewmodel.BaseRefreshViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BookCommentsViewModel @Inject constructor(
    private val commentRepository: CommentRepository
) : BaseRefreshViewModel<BookComment, CommentRepository>(commentRepository) {

    @JvmField
    var comment: BookComment = BookComment(
        id = 0, userId = 0, username = "", avatar = "",
        chapterUrl = null, chapterName = null, bookName = null,
        content = null, addTime = ""
    )
    val mVoidSingleLiveEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override fun refreshData() {
        viewModelScope.launch {
            val result = commentRepository.getChapterComments(comment.chapterUrl)
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
            val userId = SPUtil.get(KeyCode.Login.SP_USER_ID, -1L)
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

    companion object {
        private const val TAG = "BookCommentsViewModel"
    }
}
