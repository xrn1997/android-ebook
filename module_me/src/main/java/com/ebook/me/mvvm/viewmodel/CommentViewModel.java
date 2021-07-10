package com.ebook.me.mvvm.viewmodel;

import android.app.Application;
import android.os.Build;
import android.util.Log;

import com.ebook.api.dto.RespDTO;
import com.ebook.api.entity.Comment;
import com.ebook.api.entity.LoginDTO;
import com.ebook.api.http.ExceptionHandler;
import com.ebook.common.event.SingleLiveEvent;
import com.ebook.common.mvvm.viewmodel.BaseRefreshViewModel;

import com.ebook.common.util.DateUtil;
import com.ebook.common.util.ToastUtil;
import com.ebook.me.mvvm.model.CommentModel;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;

public class CommentViewModel extends BaseRefreshViewModel<Comment, CommentModel> {
    private static String TAG = ModifyViewModel.class.getSimpleName();

    public CommentViewModel(@NonNull Application application, CommentModel model) {
        super(application, model);
    }

    @Override
    public void refreshData() {
        mModel.getUserComments().subscribe(new Observer<>() {
            @Override
            public void onSubscribe(Disposable d) {

            }

            @RequiresApi(api = Build.VERSION_CODES.N)
            @Override
            public void onNext(RespDTO<List<Comment>> listRespDTO) {
                if (listRespDTO.code == ExceptionHandler.APP_ERROR.SUCC) {
                    List<Comment> comments = listRespDTO.data;
                    comments.sort((x, y) -> DateUtil.parseTime(y.getAddtime(), DateUtil.FormatType.yyyyMMddHHmm).compareTo(DateUtil.parseTime(x.getAddtime(), DateUtil.FormatType.yyyyMMddHHmm)));
                    mList.clear();
                    if (comments != null && comments.size() > 0) {
                        mList.addAll(comments);
                    }
                } else {
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

    public void deleteComent(Long id) {
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
