package com.ebook.me.mvvm.model

import android.app.Application
import com.blankj.utilcode.util.SPUtils
import com.ebook.api.config.API
import com.ebook.api.dto.RespDTO
import com.ebook.api.entity.Comment
import com.ebook.api.service.CommentService
import com.ebook.common.event.KeyCode
import com.xrn1997.common.http.RxJavaAdapter.exceptionTransformer
import com.xrn1997.common.http.RxJavaAdapter.schedulersTransformer
import com.xrn1997.common.manager.RetrofitManager
import com.xrn1997.common.mvvm.model.BaseModel
import io.reactivex.rxjava3.core.Observable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommentModel @Inject constructor(
    application: Application
) : BaseModel(application) {
    private val commentService = RetrofitManager.create(CommentService::class.java)

    init {
        //通过反射动态修改BaseUrl
        RetrofitManager.mHttpUrl.setHost(API.URL_HOST_COMMENT)
        RetrofitManager.mHttpUrl.setPort(API.URL_PORT_COMMENT)
    }


    /**
     * 删除评论
     */
    fun deleteComment(id: Long): Observable<RespDTO<Int>> {
        return commentService.deleteComment(RetrofitManager.TOKEN, id)
            .compose(schedulersTransformer())
            .compose(exceptionTransformer())
    }

    /**
     * 获得用户评论
     */
    fun getUserComments(): Observable<RespDTO<List<Comment>>> {
        val username = SPUtils.getInstance().getString(KeyCode.Login.SP_USERNAME)
        return commentService.getUserComments(RetrofitManager.TOKEN, username)
            .compose(schedulersTransformer())
            .compose(exceptionTransformer())
    }
}
