package com.ebook.me.mvvm.viewmodel;

import android.app.Application;

import com.ebook.api.entity.Comment;
import com.ebook.common.mvvm.viewmodel.BaseRefreshViewModel;

import com.ebook.me.mvvm.model.CommentModel;

import androidx.annotation.NonNull;

public class CommentViewModel extends BaseRefreshViewModel<Comment,CommentModel> {
    public CommentViewModel(@NonNull Application application, CommentModel model) {
        super(application, model);
    }

    @Override
    public void refreshData() {

    }

    @Override
    public void loadMore() {

    }


}
