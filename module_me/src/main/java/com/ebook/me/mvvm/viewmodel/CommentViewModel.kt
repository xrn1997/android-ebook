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
     * 幂等加载入口：仅列表为空时才真正发请求（首开、或失败后列表仍空）。
     *
     * 基类对每次配置变更都调页面的 `initData()`，若它直接调 [refreshData]，转屏即重发全量
     * 并把覆盖层打回 Loading；[refreshData] 本身保留无条件语义，供下拉刷新与删除成功后的重拉使用。
     *
     * 注意：`initData()` 目前身兼首次加载与基类错误页重试钩子（`onNetworkErrorRetry`）双职责，
     * 是后续 C7 约定要统一的对象；本次只加此守卫，不动职责划分。
     */
    fun loadData() {
        if (list.value.isEmpty()) {
            refreshData()
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
