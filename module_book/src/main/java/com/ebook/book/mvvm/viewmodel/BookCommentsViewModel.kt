package com.ebook.book.mvvm.viewmodel

import android.app.Application
import android.util.Log
import com.blankj.utilcode.util.SPUtils
import com.blankj.utilcode.util.StringUtils
import com.ebook.api.dto.RespDTO
import com.ebook.api.entity.Comment
import com.ebook.api.entity.User
import com.ebook.book.mvvm.model.BookCommentsModel
import com.ebook.common.event.KeyCode
import com.ebook.common.util.DateUtil
import com.xrn1997.common.event.SimpleObserver
import com.xrn1997.common.event.SingleLiveEvent
import com.xrn1997.common.http.ExceptionHandler
import com.xrn1997.common.mvvm.viewmodel.BaseRefreshViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.reactivex.rxjava3.core.Observer
import io.reactivex.rxjava3.disposables.Disposable
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
        mModel.getChapterComments(comment.chapterUrl)
            .doOnSubscribe(this)
            .subscribe(object : Observer<RespDTO<List<Comment>>> {
                override fun onSubscribe(d: Disposable) {
                }

                override fun onNext(listRespDTO: RespDTO<List<Comment>>) {
                    if (listRespDTO.code == ExceptionHandler.AppError.SUCCESS) {
                        listRespDTO.data?.let { data ->
                            val sortedComments = data.sortedByDescending {
                                DateUtil.parseTime(it.addTime, DateUtil.FormatType.yyyyMMddHHmm)
                            }
                            mList.value = sortedComments
                        }
                        postStopRefreshEvent(true)
                    } else {
                        Log.e(TAG, "error: " + listRespDTO.error)
                        postStopRefreshEvent(false)
                    }
                }

                override fun onError(e: Throwable) {
                    postStopRefreshEvent(false)
                }

                override fun onComplete() {
                }
            })
    }



    override fun loadMore() {
    }

    /**
     * 添加评论
     */
    fun addComment(comments: String) {
        if (!StringUtils.isEmpty(comments)) {
            val user = User()
            user.id = SPUtils.getInstance().getLong(KeyCode.Login.SP_USER_ID)
            comment.user = user
            comment.comment = comments
            mModel.addComment(comment)
                .doOnSubscribe(this)
                .subscribe(object : Observer<RespDTO<Comment>> {
                override fun onSubscribe(d: Disposable) {
                }

                override fun onNext(commentRespDTO: RespDTO<Comment>) {
                    if (commentRespDTO.code == ExceptionHandler.AppError.SUCCESS) {
                        mVoidSingleLiveEvent.call()
                        refreshData()
                    } else {
                        Log.e(TAG, "error: " + commentRespDTO.error)
                    }
                }

                override fun onError(e: Throwable) {
                }

                override fun onComplete() {
                }
            })
        } else {
            mToastLiveEvent.setValue("不能为空哦！")
        }
    }

    fun deleteComment(id: Long) {
        mModel.deleteComment(id)
            .doOnSubscribe(this)
            .subscribe(object : SimpleObserver<RespDTO<Int>>() {
            override fun onNext(integerRespDTO: RespDTO<Int>) {
                if (integerRespDTO.code == ExceptionHandler.AppError.SUCCESS) {
                    mToastLiveEvent.setValue("删除成功！")
                    refreshData()
                } else {
                    Log.e(TAG, "error: " + integerRespDTO.error)
                }
            }

            override fun onError(e: Throwable) {
            }
        })
    }

    companion object {
        private val TAG: String = BookCommentsViewModel::class.java.simpleName
    }
}
