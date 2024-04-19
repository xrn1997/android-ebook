package com.ebook.login;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModelProvider;

import com.ebook.common.event.KeyCode;
import com.ebook.login.databinding.ActivityRegisterBinding;
import com.ebook.login.mvvm.factory.LoginViewModelFactory;
import com.ebook.login.mvvm.viewmodel.RegisterViewModel;
import com.therouter.router.Route;
import com.xrn1997.common.mvvm.view.BaseMvvmActivity;


@Route(path = KeyCode.Login.REGISTER_PATH)
public class RegisterActivity extends BaseMvvmActivity<ActivityRegisterBinding, RegisterViewModel> {

    @Override
    public int onBindLayout() {
        return R.layout.activity_register;
    }

    @NonNull
    @Override
    public Class<RegisterViewModel> onBindViewModel() {
        return RegisterViewModel.class;
    }

    @NonNull
    @Override
    public ViewModelProvider.Factory onBindViewModelFactory() {
        return LoginViewModelFactory.INSTANCE;
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

    @Override
    public void initData() {
    }
}