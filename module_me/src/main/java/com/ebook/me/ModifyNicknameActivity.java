package com.ebook.me;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModelProvider;

import com.ebook.me.databinding.ActivityModifyNicknameBinding;
import com.ebook.me.mvvm.factory.MeViewModelFactory;
import com.ebook.me.mvvm.viewmodel.ModifyViewModel;
import com.xrn1997.common.mvvm.view.BaseMvvmActivity;

public class ModifyNicknameActivity extends BaseMvvmActivity<ActivityModifyNicknameBinding, ModifyViewModel> {
    @NonNull
    @Override
    public Class<ModifyViewModel> onBindViewModel() {
        return ModifyViewModel.class;
    }

    @NonNull
    @Override
    public ViewModelProvider.Factory onBindViewModelFactory() {
        return MeViewModelFactory.INSTANCE;
    }

    @Override
    public void initViewObservable() {
    }

    @Override
    public int onBindVariableId() {
        return BR.viewModel;
    }

    @Override
    public int onBindLayout() {
        return R.layout.activity_modify_nickname;
    }

    @Override
    public void initView() {
    }

    @Override
    public void initData() {
    }
}
