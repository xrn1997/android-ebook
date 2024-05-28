package com.ebook.login;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import com.ebook.login.databinding.ActivityModifyPwdBinding;
import com.ebook.login.mvvm.factory.LoginViewModelFactory;
import com.ebook.login.mvvm.viewmodel.ModifyPwdViewModel;
import com.hwangjr.rxbus.RxBus;
import com.xrn1997.common.mvvm.view.BaseMvvmActivity;


public class ModifyPwdActivity extends BaseMvvmActivity<ActivityModifyPwdBinding, ModifyPwdViewModel> {
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RxBus.get().register(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        RxBus.get().unregister(this);
    }

    @NonNull
    @Override
    public Class<ModifyPwdViewModel> onBindViewModel() {
        return ModifyPwdViewModel.class;
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
    public void initData() {
        Bundle bundle = this.getIntent().getExtras();
        if (bundle != null) {
            String username = (String) bundle.get("username");
            mViewModel.username.set(username);
        }
    }

    @Override
    public int onBindVariableId() {
        return BR.viewModel;
    }

    @Override
    public int onBindLayout() {
        return R.layout.activity_modify_pwd;
    }

    @Override
    public void initView() {

    }
}
