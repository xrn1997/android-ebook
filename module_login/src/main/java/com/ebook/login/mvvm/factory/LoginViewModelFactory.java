package com.ebook.login.mvvm.factory;

import android.annotation.SuppressLint;
import android.app.Application;

import com.ebook.login.mvvm.model.ModifyPwdModel;
import com.ebook.login.mvvm.model.LoginModel;
import com.ebook.login.mvvm.model.RegisterModel;
import com.ebook.login.mvvm.viewmodel.ModifyPwdViewModel;
import com.ebook.login.mvvm.viewmodel.LoginViewModel;
import com.ebook.login.mvvm.viewmodel.RegisterViewModel;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
public class LoginViewModelFactory extends ViewModelProvider.NewInstanceFactory {
    @SuppressLint("StaticFieldLeak")
    private static volatile LoginViewModelFactory INSTANCE;
    private final Application mApplication;

    public static LoginViewModelFactory getInstance(Application application) {
        if (INSTANCE == null) {
            synchronized (LoginViewModelFactory.class) {
                if (INSTANCE == null) {
                    INSTANCE = new LoginViewModelFactory(application);
                }
            }
        }
        return INSTANCE;
    }

    private LoginViewModelFactory(Application application) {
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
        if (modelClass.isAssignableFrom(LoginViewModel.class)) {
            return (T) new LoginViewModel(mApplication, new LoginModel(mApplication));
        } else if (modelClass.isAssignableFrom(RegisterViewModel.class)) {
            return (T) new RegisterViewModel(mApplication, new RegisterModel(mApplication));
        } else if (modelClass.isAssignableFrom(ModifyPwdViewModel.class)) {
            return (T) new ModifyPwdViewModel(mApplication, new ModifyPwdModel(mApplication));
        }
        throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
    }
}
