package com.ebook.login;

;

import com.alibaba.android.arouter.facade.annotation.Route;
import com.ebook.common.event.KeyCode;
import com.ebook.common.mvvm.BaseMvvmActivity;
import com.ebook.login.databinding.ActivityRegisterBinding;
import com.ebook.login.mvvm.factory.LoginViewModelFactory;
import com.ebook.login.mvvm.viewmodel.RegisterViewModel;

import androidx.lifecycle.ViewModelProvider;

@Route(path = KeyCode.Login.Register_PATH)
public class RegisterActivity extends BaseMvvmActivity<ActivityRegisterBinding, RegisterViewModel> {

    @Override
    public int onBindLayout() {
        return R.layout.activity_register;
    }

    @Override
    public Class<RegisterViewModel> onBindViewModel() {
        return RegisterViewModel.class;
    }

    @Override
    public ViewModelProvider.Factory onBindViewModelFactory() {
        return LoginViewModelFactory.getInstance(getApplication());
    }

    @Override
    public void initViewObservable() {
    }

    @Override
    public int onBindVariableId() {
        return BR.viewModel;
    }

    @Override
    public void initView() {
        setTitle("注册");
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
    }
}