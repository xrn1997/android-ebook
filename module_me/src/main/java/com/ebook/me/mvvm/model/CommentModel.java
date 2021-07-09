package com.ebook.me.mvvm.model;

import android.app.Application;

import com.blankj.utilcode.util.SPUtils;
import com.ebook.api.service.CommentService;
import com.ebook.api.RetrofitManager;
import com.ebook.api.entity.Comment;
import com.ebook.api.dto.RespDTO;
import com.ebook.api.http.RxAdapter;
import com.ebook.common.event.KeyCode;
import com.ebook.common.mvvm.model.BaseModel;

import java.util.List;

import io.reactivex.Observable;

public class CommentModel extends BaseModel {
    private CommentService commentService;

    public CommentModel(Application application) {
        super(application);
        commentService = RetrofitManager.getInstance().getCommentService();
    }


    /**
     * 删除评论
     */
    @SuppressWarnings("unchecked")
    public Observable<RespDTO<Integer>> deleteComment(Long id) {
        return commentService.deleteComment(RetrofitManager.getInstance().TOKEN, id)
                .compose(RxAdapter.schedulersTransformer())
                .compose(RxAdapter.exceptionTransformer());
    }

    /**
     * 获得用户评论
     */
    @SuppressWarnings("unchecked")
    public Observable<RespDTO<List<Comment>>> getUserComments() {
        String username = SPUtils.getInstance().getString(KeyCode.Login.SP_USERNAME);
        return commentService.getUserComments(RetrofitManager.getInstance().TOKEN, username)
                .compose(RxAdapter.schedulersTransformer())
                .compose(RxAdapter.exceptionTransformer());
    }


}
