package com.ebook.me;

import com.ebook.common.mvvm.BaseMvvmActivity;
import com.ebook.me.databinding.ActivityModifyNicknameBinding;
import com.ebook.me.mvvm.factory.MeViewModelFactory;
import com.ebook.me.mvvm.viewmodel.ModifyViewModel;

import androidx.lifecycle.ViewModelProvider;

public class ModifyNicknameActivity extends BaseMvvmActivity<ActivityModifyNicknameBinding, ModifyViewModel> {
    @Override
    public Class<ModifyViewModel> onBindViewModel() {
        return ModifyViewModel.class;
    }

    @Override
    public ViewModelProvider.Factory onBindViewModelFactory() {
        return MeViewModelFactory.getInstance(getApplication());
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
}
