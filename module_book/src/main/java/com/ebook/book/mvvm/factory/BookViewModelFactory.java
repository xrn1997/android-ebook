package com.ebook.book.mvvm.factory;

import android.annotation.SuppressLint;
import android.app.Application;

import com.ebook.book.mvvm.model.BookCommentsModel;
import com.ebook.book.mvvm.model.BookListModel;
import com.ebook.book.mvvm.viewmodel.BookCommentsViewModel;
import com.ebook.book.mvvm.viewmodel.BookListViewModel;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

public class BookViewModelFactory extends ViewModelProvider.NewInstanceFactory {
    @SuppressLint("StaticFieldLeak")
    private static volatile BookViewModelFactory INSTANCE;
    private final Application mApplication;

    public static BookViewModelFactory getInstance(Application application) {
        if (INSTANCE == null) {
            synchronized (BookViewModelFactory.class) {
                if (INSTANCE == null) {
                    INSTANCE = new BookViewModelFactory(application);
                }
            }
        }
        return INSTANCE;
    }

    private BookViewModelFactory(Application application) {
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
        if (modelClass.isAssignableFrom(BookListViewModel.class)) {
            return (T) new BookListViewModel(mApplication, new BookListModel(mApplication));
        } else if (modelClass.isAssignableFrom(BookCommentsViewModel.class)) {
            return (T) new BookCommentsViewModel(mApplication, new BookCommentsModel(mApplication));
        }
        throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
    }
}
