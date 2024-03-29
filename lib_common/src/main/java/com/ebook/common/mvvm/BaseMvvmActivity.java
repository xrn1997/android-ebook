package com.ebook.common.mvvm;

import android.content.Intent;
import android.os.Bundle;

import androidx.databinding.DataBindingUtil;
import androidx.databinding.ViewDataBinding;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelProvider.Factory;

import com.ebook.common.mvvm.viewmodel.BaseViewModel;
import com.ebook.common.util.log.KLog;

import java.util.Map;


public abstract class BaseMvvmActivity<V extends ViewDataBinding, VM extends BaseViewModel> extends BaseActivity {
    protected V mBinding;//MVVM中的V，负责视图显示
    protected VM mViewModel;//MVVM中的VM，负责处理视图的操作功能，与M进行数据交互。

    @Override
    public void initContentView() {
        initViewDataBinding();
        initBaseViewObservable();
        initViewObservable();
    }

    public abstract Class<VM> onBindViewModel();

    public abstract Factory onBindViewModelFactory();

    public abstract void initViewObservable();

    public abstract int onBindVariableId();

    public VM createViewModel() {
        return new ViewModelProvider(this, onBindViewModelFactory()).get(onBindViewModel());
    }

    private void initViewDataBinding() {
        mBinding = DataBindingUtil.setContentView(this, onBindLayout());
        int viewModelId = onBindVariableId();
        mViewModel = createViewModel();
        if (mBinding != null) {
            mBinding.setVariable(viewModelId, mViewModel);
        }
        getLifecycle().addObserver(mViewModel);
    }

    @SuppressWarnings("unchecked")
    protected void initBaseViewObservable() {
        mViewModel.getUC().getShowInitLoadViewEvent().observe(this, (Observer<Boolean>) this::showInitLoadView);
        mViewModel.getUC().getShowTransLoadingViewEvent().observe(this, (Observer<Boolean>) show -> {
            KLog.v("MYTAG", "view postShowTransLoadingViewEvent start...");
            showTransLoadingView(show);
        });
        mViewModel.getUC().getShowNoDataViewEvent().observe(this, (Observer<Boolean>) this::showNoDataView);
        mViewModel.getUC().getShowNetWorkErrViewEvent().observe(this, (Observer<Boolean>) this::showNetWorkErrView);
        mViewModel.getUC().getStartActivityEvent().observe(this, (Observer<Map<String, Object>>) params -> {
            Class<?> clz = (Class<?>) params.get(BaseViewModel.ParameterField.CLASS);
            Bundle bundle = (Bundle) params.get(BaseViewModel.ParameterField.BUNDLE);
            startActivity(clz, bundle);
        });
        mViewModel.getUC().getFinishActivityEvent().observe(this, (Observer<Void>) v -> finish());
        mViewModel.getUC().getOnBackPressedEvent().observe(this, (Observer<Void>) v -> onBackPressed());
    }

    public void startActivity(Class<?> clz, Bundle bundle) {
        Intent intent = new Intent(this, clz);
        if (bundle != null) {
            intent.putExtras(bundle);
        }
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mBinding != null) {
            mBinding.unbind();
        }
    }

    @Override
    public void initView() {

    }

    @Override
    public void initData() {
    }

    @Override
    public void initListener() {

    }
}
