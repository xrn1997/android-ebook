package com.ebook.find.mvvm.factory;

import android.annotation.SuppressLint;
import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.ebook.find.mvvm.model.LibraryModel;
import com.ebook.find.mvvm.viewmodel.LibraryViewModel;

public class FindViewModelFactory extends ViewModelProvider.NewInstanceFactory {
    @SuppressLint("StaticFieldLeak")
    private static volatile FindViewModelFactory INSTANCE;
    private final Application mApplication;

    private FindViewModelFactory(Application application) {
        this.mApplication = application;
    }

    public static FindViewModelFactory getInstance(Application application) {
        if (INSTANCE == null) {
            synchronized (FindViewModelFactory.class) {
                if (INSTANCE == null) {
                    INSTANCE = new FindViewModelFactory(application);
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
        if (modelClass.isAssignableFrom(LibraryViewModel.class)) {
            return (T) new LibraryViewModel(mApplication, new LibraryModel(mApplication));
        }
        throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
    }
}
