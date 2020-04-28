package com.ebook.me.mvvm.factory;

import android.annotation.SuppressLint;
import android.app.Application;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.ebook.me.mvvm.model.NewsDetailAddModel;
import com.ebook.me.mvvm.viewmodel.NewsDetailAddViewModel;
import com.ebook.me.mvvm.model.NewsTypeAddModel;
import com.ebook.me.mvvm.model.NewsTypeListModel;
import com.ebook.me.mvvm.viewmodel.NewsTypeAddViewModel;
import com.ebook.me.mvvm.viewmodel.NewsTypeListViewModel;


public class MeViewModelFactory extends ViewModelProvider.NewInstanceFactory {
    @SuppressLint("StaticFieldLeak")
    private static volatile MeViewModelFactory INSTANCE;
    private final Application mApplication;

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
    private MeViewModelFactory(Application application) {
        this.mApplication = application;
    }
    @VisibleForTesting
    public static void destroyInstance() {
        INSTANCE = null;
    }

    @NonNull
    @Override
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(NewsTypeAddViewModel.class)) {
            return (T) new NewsTypeAddViewModel(mApplication, new NewsTypeAddModel(mApplication));
        }else if (modelClass.isAssignableFrom(NewsTypeListViewModel.class)) {
            return (T) new NewsTypeListViewModel(mApplication, new NewsTypeListModel(mApplication));
        }else if (modelClass.isAssignableFrom(NewsDetailAddViewModel.class)) {
            return (T) new NewsDetailAddViewModel(mApplication, new NewsDetailAddModel(mApplication));
        }
        throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
    }
}
