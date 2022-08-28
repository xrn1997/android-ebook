package com.ebook.common.mvvm;

import androidx.annotation.Nullable;
import androidx.databinding.ViewDataBinding;
import androidx.lifecycle.Observer;

import com.ebook.common.mvvm.viewmodel.BaseRefreshViewModel;
import com.refresh.lib.DaisyRefreshLayout;


public abstract class BaseMvvmRefreshActivity<V extends ViewDataBinding, VM extends BaseRefreshViewModel> extends BaseMvvmActivity<V, VM> {
    protected DaisyRefreshLayout mRefreshLayout;

    @Override
    public void initContentView() {
        super.initContentView();
        initRefreshView();
    }

    @Override
    protected void initBaseViewObservable() {
        super.initBaseViewObservable();
        initBaseViewRefreshObservable();
    }

    @SuppressWarnings("unchecked")
    private void initBaseViewRefreshObservable() {
        mViewModel.getUCRefresh().getAutoRefresLiveEvent().observe(this, new Observer() {
            @Override
            public void onChanged(@Nullable Object o) {
                autoLoadData();
            }
        });
        mViewModel.getUCRefresh().getStopRefresLiveEvent().observe(this, new Observer() {
            @Override
            public void onChanged(@Nullable Object o) {
                stopRefresh();
            }
        });
        mViewModel.getUCRefresh().getStopLoadMoreLiveEvent().observe(this, new Observer() {
            @Override
            public void onChanged(@Nullable Object o) {
                stopLoadMore();
            }
        });
    }

    public abstract DaisyRefreshLayout getRefreshLayout();

    public void initRefreshView() {
        mRefreshLayout = getRefreshLayout();
    }

    public void stopRefresh() {
        mRefreshLayout.setRefreshing(false);
    }

    public void stopLoadMore() {
        mRefreshLayout.setLoadMore(false);
    }

    public void autoLoadData() {
        mRefreshLayout.autoRefresh();
    }
}
