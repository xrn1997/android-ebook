package com.ebook.me.mvvm.model

import android.app.Application
import com.ebook.api.entity.Comment
import com.ebook.api.service.comment.CommentDataSource
import com.ebook.api.utils.CoroutineAdapter
import com.ebook.common.event.KeyCode
import com.ebook.common.util.SPUtil
import com.xrn1997.common.dto.RespDTO
import com.xrn1997.common.manager.RetrofitManager
import com.xrn1997.common.mvvm.model.BaseModel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommentModel @Inject constructor(
    application: Application,
    private val dataSource: CommentDataSource
) : BaseModel(application) {

    /**
     * 删除评论
     */
    suspend fun deleteComment(id: Long): Result<RespDTO<Int>> =
        CoroutineAdapter.safeApiCall { dataSource.deleteComment(RetrofitManager.TOKEN, id) }


    /**
     * 获得用户评论
     */
    suspend fun getUserComments(): Result<RespDTO<List<Comment>>> {
        val username = SPUtil.get(KeyCode.Login.SP_USERNAME, "")
        return CoroutineAdapter.safeApiCall {
            dataSource.getUserComments(
                RetrofitManager.TOKEN,
                username
            )
        }
    }
}
