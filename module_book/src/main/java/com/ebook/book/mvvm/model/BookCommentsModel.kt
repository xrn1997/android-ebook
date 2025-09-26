package com.ebook.book.mvvm.model

import android.app.Application
import com.ebook.api.config.API
import com.ebook.api.dto.RespDTO
import com.ebook.api.entity.Comment
import com.ebook.api.service.CommentService
import com.xrn1997.common.http.RxJavaAdapter.exceptionTransformer
import com.xrn1997.common.http.RxJavaAdapter.schedulersTransformer
import com.xrn1997.common.manager.RetrofitManager
import com.xrn1997.common.mvvm.model.BaseModel
import io.reactivex.rxjava3.core.Observable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookCommentsModel @Inject constructor(
    application: Application
) : BaseModel(application) {
    private val commentService = RetrofitManager.create(CommentService::class.java)

    init {
        //通过反射动态修改BaseUrl
        RetrofitManager.mHttpUrl.setHost(API.URL_HOST_COMMENT)
        RetrofitManager.mHttpUrl.setPort(API.URL_PORT_COMMENT)
    }
    /**
     * 添加评论
     */
    fun addComment(comment: Comment): Observable<RespDTO<Comment>> {
        return commentService.addComment(RetrofitManager.TOKEN, comment)
            .compose(schedulersTransformer())
            .compose(exceptionTransformer())
    }

    /**
     * 获得章节评论
     */
    fun getChapterComments(chapterUrl: String?): Observable<RespDTO<List<Comment>>> {
        return commentService.getChapterComments(RetrofitManager.TOKEN, chapterUrl)
            .compose(schedulersTransformer())
            .compose(exceptionTransformer())
    }

    /**
     * 删除评论
     */
    fun deleteComment(id: Long): Observable<RespDTO<Int>> {
        return commentService.deleteComment(RetrofitManager.TOKEN, id)
            .compose(schedulersTransformer())
            .compose(exceptionTransformer())
    }
}
