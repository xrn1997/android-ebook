package com.ebook.book.mvvm.model

import android.app.Application
import com.ebook.api.entity.Comment
import com.ebook.api.service.comment.CommentDataSource
import com.ebook.api.utils.CoroutineAdapter
import com.xrn1997.common.dto.RespDTO
import com.xrn1997.common.manager.RetrofitManager
import com.xrn1997.common.mvvm.model.BaseModel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookCommentsModel @Inject constructor(
    application: Application,
    private val dataSource: CommentDataSource
) : BaseModel(application) {
    /**
     * 添加评论
     */
    suspend fun addComment(comment: Comment): Result<RespDTO<Comment>> =
        CoroutineAdapter.safeApiCall {
            dataSource.addComment(RetrofitManager.TOKEN, comment)
        }


    /**
     * 获得章节评论
     */
    suspend fun getChapterComments(chapterUrl: String?): Result<RespDTO<List<Comment>>> =
        CoroutineAdapter.safeApiCall {
            dataSource.getChapterComments(RetrofitManager.TOKEN, chapterUrl)
        }

    /**
     * 删除评论
     */
    suspend fun deleteComment(id: Long): Result<RespDTO<Int>> =
        CoroutineAdapter.safeApiCall {
            dataSource.deleteComment(RetrofitManager.TOKEN, id)
    }

}
