package com.ebook.common.mvp;

import com.ebook.common.mvp.model.BaseModel;
import com.ebook.common.mvp.presenter.BaseRefreshPresenter;
import com.ebook.common.mvp.view.BaseRefreshView;
import com.refresh.lib.DaisyRefreshLayout;

/**
 * Description: <下拉刷新、上拉加载更多的Activity><br>
 * Author:      gxl<br>
 * Date:        2018/2/26<br>
 * Version:     V1.0.0<br>
 * Update:     <br>
 */
public abstract class BaseRefreshActivity<M extends BaseModel, V extends BaseRefreshView<T>, P extends BaseRefreshPresenter<M, V, T>, T> extends BaseMvpActivity<M, V, P> implements BaseRefreshView<T> {
    protected DaisyRefreshLayout mRefreshLayout;

    @Override
    protected void initCommonView() {
        super.initCommonView();
        initRefreshView();
    }

    public void initRefreshView() {
        mRefreshLayout = findViewById(onBindRreshLayout());
        // 下拉刷新
        mRefreshLayout.setOnRefreshListener(this::onRefreshEvent);
        // 上拉加载
        mRefreshLayout.setOnLoadMoreListener(this::onLoadMoreEvent);
        // 自动加载
        mRefreshLayout.setOnAutoLoadListener(this::onAutoLoadEvent);
    }

    protected abstract int onBindRreshLayout();

    @Override
    public void enableRefresh(boolean b) {
        mRefreshLayout.setEnableRefresh(b);
    }

    @Override
    public void enableLoadMore(boolean b) {
        mRefreshLayout.setEnableLoadMore(b);
    }

    @Override
    public void stopRefresh() {
        mRefreshLayout.setRefreshing(false);
    }

    @Override
    public void stopLoadMore() {
        mRefreshLayout.setLoadMore(false);
    }

    @Override
    public void autoLoadData() {
        mRefreshLayout.autoRefresh();
    }
}
