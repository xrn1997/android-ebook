package com.ebook.book.mvvm.viewmodel

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.ebook.api.entity.Comment
import com.ebook.api.entity.User
import com.ebook.api.utils.CoroutineAdapter
import com.ebook.book.mvvm.model.BookCommentsModel
import com.ebook.common.event.KeyCode
import com.ebook.common.util.DateUtil
import com.ebook.common.util.SPUtil
import com.xrn1997.common.event.SingleLiveEvent
import com.xrn1997.common.mvvm.viewmodel.BaseRefreshViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BookCommentsViewModel @Inject constructor(
    application: Application,
    model: BookCommentsModel
) : BaseRefreshViewModel<Comment, BookCommentsModel>(application, model) {

    @JvmField
    var comment: Comment = Comment()
    val mVoidSingleLiveEvent by lazy { SingleLiveEvent<Unit>() }

    override fun refreshData() {
        viewModelScope.launch {
            val result = mModel.getChapterComments(comment.chapterUrl)
            result.onSuccess { resp ->
                resp.data?.let { data ->
                    val sortedComments = data.sortedByDescending {
                        DateUtil.parseTime(it.addTime, DateUtil.FormatType.yyyyMMddHHmm)
                    }
                    mList.value = sortedComments
                }
                postStopRefreshEvent(true)
            }.onFailure { exception ->
                if (exception is CoroutineAdapter.ApiException) {
                    postToastEvent(exception.message())
                } else {
                    postToastEvent("${exception.message}")
                }
                postStopRefreshEvent(false)
            }
        }
    }



    override fun loadMore() {
    }

    /**
     * 添加评论
     */
    fun addComment(comments: String) {
        if (comments.isNotEmpty()) {
            val user = User()
            user.id = SPUtil.get(KeyCode.Login.SP_USER_ID, -1L)
            comment.user = user
            comment.comment = comments
            viewModelScope.launch {
                val result = mModel.addComment(comment)
                result.onSuccess {
                    mVoidSingleLiveEvent.call()
                    refreshData()
                }.onFailure { exception ->
                    if (exception is CoroutineAdapter.ApiException) {
                        postToastEvent(exception.message())
                    } else {
                        postToastEvent("${exception.message}")
                    }
                }
            }
        } else {
            postToastEvent("不能为空哦！")
        }
    }

    fun deleteComment(id: Long) {
        viewModelScope.launch {
            val result = mModel.deleteComment(id)
            result.onSuccess {
                postToastEvent("删除成功！")
                refreshData()
            }.onFailure { exception ->
                if (exception is CoroutineAdapter.ApiException) {
                    postToastEvent(exception.message())
                } else {
                    postToastEvent("${exception.message}")
                }
            }
        }
    }
}
