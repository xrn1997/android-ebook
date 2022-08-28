package com.ebook.me.mvvm.factory;

import android.annotation.SuppressLint;
import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.ebook.me.mvvm.model.CommentModel;
import com.ebook.me.mvvm.model.ModifyModel;
import com.ebook.me.mvvm.viewmodel.CommentViewModel;
import com.ebook.me.mvvm.viewmodel.ModifyViewModel;


public class MeViewModelFactory extends ViewModelProvider.NewInstanceFactory {
    @SuppressLint("StaticFieldLeak")
    private static volatile MeViewModelFactory INSTANCE;
    private final Application mApplication;

    private MeViewModelFactory(Application application) {
        this.mApplication = application;
    }

    public static MeViewModelFactory getInstance(Application application) {
        if (INSTANCE == null) {
            synchronized (MeViewModelFactory.class) {
                if (INSTANCE == null) {
                    INSTANCE = new MeViewModelFactory(application);
                }
            }
        }
        return INSTANCE;
    }

    @VisibleForTesting
    public static void destroyInstance() {
        INSTANCE = null;
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(CommentViewModel.class)) {
            return (T) new CommentViewModel(mApplication, new CommentModel(mApplication));
        } else if (modelClass.isAssignableFrom(ModifyViewModel.class)) {
            return (T) new ModifyViewModel(mApplication, new ModifyModel(mApplication));
        }
        throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
    }
}
