package com.ebook.me.mvvm.viewmodel;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;

import com.ebook.api.dto.RespDTO;
import com.ebook.api.entity.Comment;
import com.ebook.api.http.ExceptionHandler;
import com.ebook.common.util.DateUtil;
import com.ebook.common.util.ToastUtil;
import com.ebook.me.mvvm.model.CommentModel;
import com.xrn1997.common.mvvm.viewmodel.BaseRefreshViewModel;

import java.util.List;

import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;

public class CommentViewModel extends BaseRefreshViewModel<Comment, CommentModel> {
    private static final String TAG = ModifyViewModel.class.getSimpleName();

    public CommentViewModel(@NonNull Application application, CommentModel model) {
        super(application, model);
    }

    @Override
    public void refreshData() {
        mModel.getUserComments().subscribe(new Observer<>() {
            @Override
            public void onSubscribe(Disposable d) {

            }

            @Override
            public void onNext(RespDTO<List<Comment>> listRespDTO) {
                if (listRespDTO.code == ExceptionHandler.APP_ERROR.SUCC) {
                    List<Comment> comments = listRespDTO.data;
                    comments.sort((x, y) -> DateUtil.parseTime(y.getAddtime(), DateUtil.FormatType.yyyyMMddHHmm).compareTo(DateUtil.parseTime(x.getAddtime(), DateUtil.FormatType.yyyyMMddHHmm)));
                    mList.clear();
                    if (!comments.isEmpty()) {
                        mList.addAll(comments);
                    }
                } else {
                    Log.e(TAG, "error: " + listRespDTO.error);
                }
                postStopRefreshEvent(true);
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

    public void deleteComment(Long id) {
        mModel.deleteComment(id).subscribe(new Observer<>() {
            @Override
            public void onSubscribe(Disposable d) {

            }

            @Override
            public void onNext(RespDTO<Integer> integerRespDTO) {
                if (integerRespDTO.code == ExceptionHandler.APP_ERROR.SUCC) {
                    ToastUtil.showToast("删除成功！");
                    refreshData();
                } else {
                    Log.e(TAG, "error: " + integerRespDTO.error);
                }
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
