package com.ebook.book.mvvm.viewmodel;

import android.app.Application;
import android.util.Log;

import com.blankj.utilcode.util.SPUtils;
import com.blankj.utilcode.util.StringUtils;
import com.ebook.api.dto.RespDTO;
import com.ebook.api.entity.Comment;
import com.ebook.api.entity.User;
import com.ebook.api.http.ExceptionHandler;
import com.ebook.book.mvvm.model.BookCommentsModel;

import com.ebook.common.event.KeyCode;
import com.ebook.common.event.SingleLiveEvent;
import com.ebook.common.mvvm.viewmodel.BaseRefreshViewModel;
import com.ebook.common.util.DateUtil;
import com.ebook.common.util.SoftInputUtil;
import com.ebook.common.util.ToastUtil;

import java.util.Date;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.databinding.ObservableField;
import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;


public class BookCommentsViewModel extends BaseRefreshViewModel<Comment, BookCommentsModel> {
    private static String TAG = BookCommentsViewModel.class.getSimpleName();
    public ObservableField<String> comments = new ObservableField<>();
    public Comment comment = new Comment();
    private SingleLiveEvent<Void> mVoidSingleLiveEvent;

    public BookCommentsViewModel(@NonNull Application application, BookCommentsModel model) {
        super(application, model);
    }

    @Override
    public void refreshData() {
        mModel.getChapterComments(comment.getChapterUrl()).subscribe(new Observer<RespDTO<List<Comment>>>() {
            @Override
            public void onSubscribe(Disposable d) {

            }

            @Override
            public void onNext(RespDTO<List<Comment>> listRespDTO) {
                if (listRespDTO.code == ExceptionHandler.APP_ERROR.SUCC) {
                    List<Comment> comments = listRespDTO.data;
                    if (comments != null && comments.size() > 0) {
                        mList.clear();
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
    public boolean enableLoadMore() {
        return false;
    }

    @Override
    public void loadMore() {

    }

    /**
     * 添加评论
     */
    public void addComment() {
        if (!StringUtils.isEmpty(comments.get())) {
            User user = new User();
            user.setId(SPUtils.getInstance().getLong(KeyCode.Login.SP_USER_ID));
            comment.setUser(user);
            comment.setComment(comments.get());
            mModel.addComment(comment).subscribe(new Observer<RespDTO<Comment>>() {
                @Override
                public void onSubscribe(Disposable d) {

                }

                @Override
                public void onNext(RespDTO<Comment> commentRespDTO) {
                    if (commentRespDTO.code == ExceptionHandler.APP_ERROR.SUCC) {
                        getmVoidSingleLiveEvent().call();
                        refreshData();
                    } else {
                        Log.e(TAG, "error: " + commentRespDTO.error);
                    }
                }

                @Override
                public void onError(Throwable e) {

                }

                @Override
                public void onComplete() {

                }
            });
        } else {
            ToastUtil.showToast("不能为空哦！");
        }

    }

    public void deleteComent(Long id) {
        mModel.deleteComment(id).subscribe(new Observer<RespDTO<Integer>>() {
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

    public SingleLiveEvent<Void> getmVoidSingleLiveEvent() {
        return mVoidSingleLiveEvent = createLiveData(mVoidSingleLiveEvent);
    }
}
