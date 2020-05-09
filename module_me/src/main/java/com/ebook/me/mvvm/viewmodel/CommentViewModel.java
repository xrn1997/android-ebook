package com.ebook.me.mvvm.viewmodel;

import android.app.Application;
import android.util.Log;

import com.ebook.api.dto.RespDTO;
import com.ebook.api.entity.Comment;
import com.ebook.api.entity.LoginDTO;
import com.ebook.api.http.ExceptionHandler;
import com.ebook.common.mvvm.viewmodel.BaseRefreshViewModel;

import com.ebook.me.mvvm.model.CommentModel;

import java.util.List;

import androidx.annotation.NonNull;
import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;

public class CommentViewModel extends BaseRefreshViewModel<Comment,CommentModel> {
    private static String TAG = ModifyViewModel.class.getSimpleName();
    public CommentViewModel(@NonNull Application application, CommentModel model) {
        super(application, model);
    }

    @Override
    public void refreshData() {
        mModel.getUserComments().subscribe(new Observer<RespDTO<List<Comment>>>() {
            @Override
            public void onSubscribe(Disposable d) {

            }

            @Override
            public void onNext(RespDTO<List<Comment>> listRespDTO) {
                if(listRespDTO.code== ExceptionHandler.APP_ERROR.SUCC){
                    List<Comment> comments=listRespDTO.data;
                    if (comments != null && comments.size() > 0) {
                        mList.clear();
                        mList.addAll(comments);
                    }
                }else{
                    Log.e(TAG, "error: " + listRespDTO.error);
                }
                postStopRefreshEvent();
            }

            @Override
            public void onError(Throwable e) {

            }

            @Override
            public void onComplete() {

            }
        });
    }

    @Override
    public void loadMore() {

    }

    @Override
    public boolean enableLoadMore() {
        return false;
    }

    public void deleteComent(Long id){
        mModel.deleteComment(id).subscribe(new Observer<RespDTO<Integer>>() {
            @Override
            public void onSubscribe(Disposable d) {

            }

            @Override
            public void onNext(RespDTO<Integer> integerRespDTO) {

            }

            @Override
            public void onError(Throwable e) {

            }

            @Override
            public void onComplete() {

            }
        });
    }
}
