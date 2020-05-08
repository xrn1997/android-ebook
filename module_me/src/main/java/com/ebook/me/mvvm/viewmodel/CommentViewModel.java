package com.ebook.me.mvvm.viewmodel;

import android.app.Application;

import com.ebook.common.mvvm.viewmodel.BaseViewModel;
import com.ebook.me.mvvm.model.CommentModel;

import androidx.annotation.NonNull;

public class CommentViewModel extends BaseViewModel<CommentModel> {
    public CommentViewModel(@NonNull Application application, CommentModel model) {
        super(application, model);
    }
}
