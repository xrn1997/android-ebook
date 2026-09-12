package com.ebook.me.mvvm.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.ebook.common.domain.BookComment
import com.ebook.common.domain.CommentTime
import com.ebook.common.repository.CommentRepository
import com.ebook.common.util.reportFailure
import com.ebook.me.R
import com.xrn1997.common.mvvm.viewmodel.BaseRefreshViewModel
import com.xrn1997.common.mvvm.viewmodel.Overlay
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
                // 时间口径（解析格式、到秒精度）收口在 CommentTime，两个页面共用同一把排序键
                val sortedComments = data.sortedByDescending { CommentTime.sortMillis(it.addTime) }
                updateList(sortedComments)
                updateOverlay(if (sortedComments.isEmpty()) Overlay.NoData else Overlay.None)
                updateStopRefresh()
            }.onFailure { exception ->
                // 会话过期只记日志（全局已提示过一次），其余弹文案；
                // 覆盖层形态两类不同：过期时不摆「暂无数据」，交给全局跳转处置
                val silenced = reportFailure(exception)
                // 首屏失败（列表仍空）显示空态 + Toast；已有数据刷新失败保持列表
                updateOverlay(if (!silenced && list.value.isEmpty()) Overlay.NoData else Overlay.None)
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
            }.onFailure { reportFailure(it) }
        }
    }
}
