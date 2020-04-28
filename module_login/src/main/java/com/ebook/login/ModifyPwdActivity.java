package com.ebook.login;

import android.os.Bundle;

import com.ebook.common.mvvm.BaseMvvmActivity;

import com.ebook.login.databinding.ActivityModifyPwdBinding;
import com.ebook.login.mvvm.factory.LoginViewModelFactory;
import com.ebook.login.mvvm.viewmodel.ModifyPwdViewModel;

import androidx.lifecycle.ViewModelProvider;

public class ModifyPwdActivity extends BaseMvvmActivity<ActivityModifyPwdBinding, ModifyPwdViewModel>  {
    @Override
    public Class<ModifyPwdViewModel> onBindViewModel() {
        return ModifyPwdViewModel.class;
    }

    @Override
    public ViewModelProvider.Factory onBindViewModelFactory() {
        return LoginViewModelFactory.getInstance(getApplication());
    }

    @Override
    public void initViewObservable() {

    }

    @Override
    public void initData() {
        super.initData();
        Bundle bundle=this.getIntent().getExtras();
        if(bundle!=null){
            String username=(String)bundle.get("username");
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
}
