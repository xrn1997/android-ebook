package com.ebook.me.mvvm.viewmodel

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.ebook.api.entity.Comment
import com.ebook.api.utils.CoroutineAdapter
import com.ebook.common.util.DateUtil
import com.ebook.me.mvvm.model.CommentModel
import com.xrn1997.common.mvvm.viewmodel.BaseRefreshViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CommentViewModel @Inject constructor(
    application: Application,
    model: CommentModel
) : BaseRefreshViewModel<Comment, CommentModel>(application, model) {
    override fun refreshData() {
        viewModelScope.launch {
            val result = mModel.getUserComments()
            result.onSuccess {resp->
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
