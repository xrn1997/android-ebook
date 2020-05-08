package com.ebook.me;

import com.ebook.common.mvvm.BaseActivity;
import com.ebook.common.mvvm.BaseMvvmActivity;
import com.ebook.me.mvvm.factory.MeViewModelFactory;
import com.ebook.me.mvvm.viewmodel.CommentViewModel;

import androidx.databinding.ViewDataBinding;
import androidx.lifecycle.ViewModelProvider;

public class CommentActivity extends BaseMvvmActivity<ViewDataBinding, CommentViewModel> {

    @Override
    public int onBindLayout() {
        return R.layout.activity_comment;
    }

    @Override
    public Class<CommentViewModel> onBindViewModel() {
        return CommentViewModel.class;
    }

    @Override
    public ViewModelProvider.Factory onBindViewModelFactory() {
        return  MeViewModelFactory.getInstance(getApplication());
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

    }

    @Override
    public boolean enableToolbar() {
        return true;
    }

    @Override
    public void initData() {

    }
}
