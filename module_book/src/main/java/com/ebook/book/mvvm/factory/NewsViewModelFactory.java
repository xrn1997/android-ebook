package com.ebook.book.mvvm.factory;

import android.annotation.SuppressLint;
import android.app.Application;
import com.ebook.book.mvvm.model.NewsDetailModel;
import com.ebook.book.mvvm.model.NewsListModel;
import com.ebook.book.mvvm.model.NewsTypeModel;
import com.ebook.book.mvvm.viewmodel.NewsDetailViewModel;
import com.ebook.book.mvvm.viewmodel.NewsListViewModel;
import com.ebook.book.mvvm.viewmodel.NewsTypeViewModel;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;


public class NewsViewModelFactory extends ViewModelProvider.NewInstanceFactory {
    @SuppressLint("StaticFieldLeak")
    private static volatile NewsViewModelFactory INSTANCE;
    private final Application mApplication;

    public static NewsViewModelFactory getInstance(Application application) {
        if (INSTANCE == null) {
            synchronized (NewsViewModelFactory.class) {
                if (INSTANCE == null) {
                    INSTANCE = new NewsViewModelFactory(application);
                }
            }
        }
        return INSTANCE;
    }
    private NewsViewModelFactory(Application application) {
        this.mApplication = application;
    }
    @VisibleForTesting
    public static void destroyInstance() {
        INSTANCE = null;
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(NewsDetailViewModel.class)) {
            return (T) new NewsDetailViewModel(mApplication, new NewsDetailModel(mApplication));
        }else if (modelClass.isAssignableFrom(NewsListViewModel.class)) {
            return (T) new NewsListViewModel(mApplication, new NewsListModel(mApplication));
        }else if (modelClass.isAssignableFrom(NewsTypeViewModel.class)) {
            return (T) new NewsTypeViewModel(mApplication, new NewsTypeModel(mApplication));
        }
        throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
    }
}
