package com.ebook.me.mvvm.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.ebook.api.utils.CoroutineAdapter
import com.ebook.common.domain.BookComment
import com.ebook.common.repository.CommentRepository
import com.ebook.common.util.DateUtil
import com.ebook.me.R
import com.xrn1997.common.mvvm.viewmodel.BaseRefreshViewModel
import com.xrn1997.common.mvvm.viewmodel.Overlay
import com.xrn1997.common.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 我的评论页 ViewModel：当前用户全部章节评论的拉取与删除。
 *
 * 评论无分页（一次性全量拉取，[loadMore] 空实现），客户端按 addTime 倒序展示；
 * 删除成功后重新拉取全量（服务端为唯一数据源，不做本地移除拼接）。
 */
@HiltViewModel
class CommentViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val commentRepository: CommentRepository
) : BaseRefreshViewModel<BookComment, CommentRepository>(commentRepository) {

    /**
     * 首屏加载/空态经基类 Overlay 互斥表达（updateOverlay）：
     * Loading（加载中遮罩）→ None（有数据）或 NoData（空数据，页面自绘带文案空态）。
     * 失败：列表为空时进入 NoData + Toast 提示（语义见 MyCommentActivity KDoc）。
     */
    override fun refreshData() {
        viewModelScope.launch {
            updateOverlay(Overlay.Loading)
            val result = commentRepository.getUserComments()
            result.onSuccess { data ->
                // 按时间倒序：addTime 为 yyyy-MM-dd HH:mm:ss（与服务端/测试数据一致），
                // 显式传 FormatType，避免隐式依赖 parseTime 的默认格式（改动默认格式会静默破坏排序）
                val sortedComments = data.sortedByDescending {
                    DateUtil.parseTime(it.addTime, DateUtil.FormatType.yyyyMMddHHmmss)?.time ?: 0L
                }
                updateList(sortedComments)
                updateOverlay(if (sortedComments.isEmpty()) Overlay.NoData else Overlay.None)
                updateStopRefresh()
            }.onFailure { exception ->
                if (CoroutineAdapter.isSessionExpiredHandled(exception)) {
                    Logger.w(TAG, "会话过期已由全局处置，本调用点静默（仅日志）：${exception.message}")
                    updateOverlay(Overlay.None)
                    updateStopRefresh()
                    return@onFailure
                }
                sendToast(errorText(exception))
                // 首屏失败（列表仍空）显示空态 + Toast；已有数据刷新失败保持列表
                updateOverlay(if (list.value.isEmpty()) Overlay.NoData else Overlay.None)
                updateStopRefresh()
            }
        }
    }

    /**
     * 我的评论无分页（一次性全量拉取），不需要加载更多。
     */
    override fun loadMore() {
    }

    /** 删除单条评论：成功后刷新列表，失败经 [sendToast] 提示（会话过期已全局处置）。 */
    fun deleteComment(id: Long) {
        viewModelScope.launch {
            val result = commentRepository.deleteComment(id)
            result.onSuccess {
                sendToast(context.getString(R.string.my_comment_delete_success))
                refreshData()
            }.onFailure { exception ->
                if (CoroutineAdapter.isSessionExpiredHandled(exception)) {
                    Logger.w(TAG, "会话过期已由全局处置，本调用点静默（仅日志）：${exception.message}")
                    return@onFailure
                }
                sendToast(errorText(exception))
            }
        }
    }

    companion object {
        private const val TAG = "CommentViewModel"
    }
}
