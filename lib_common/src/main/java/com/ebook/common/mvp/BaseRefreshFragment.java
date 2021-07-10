package com.ebook.common.mvp;

import android.view.View;

import com.ebook.common.mvp.model.BaseModel;
import com.ebook.common.mvp.presenter.BaseRefreshPresenter;
import com.ebook.common.mvp.view.BaseRefreshView;
import com.ebook.common.util.log.KLog;
import com.refresh.lib.BaseRefreshLayout;
import com.refresh.lib.DaisyRefreshLayout;

/**
 * Description: <下拉刷新、上拉加载更多的Fragment><br>
 * Author:      gxl<br>
 * Date:        2018/2/25<br>
 * Version:     V1.0.0<br>
 * Update:     <br>
 */
public abstract class BaseRefreshFragment<M extends BaseModel, V extends BaseRefreshView<T>, P extends BaseRefreshPresenter<M, V, T>, T> extends BaseMvpFragment<M, V, P> implements BaseRefreshView<T> {
    protected DaisyRefreshLayout mRefreshLayout;

    @Override
    public void initCommonView(View view) {
        super.initCommonView(view);
        initRefreshView(view);
    }

    public void initRefreshView(View view) {
        mRefreshLayout = view.findViewById(onBindRreshLayout());
        mRefreshLayout.setOnRefreshListener(this::onRefreshEvent);
        mRefreshLayout.setOnLoadMoreListener(this::onLoadMoreEvent);
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
        KLog.v("MYTAG", "autoLoadData start...");
        if (mRefreshLayout != null) {
            KLog.v("MYTAG", "autoLoadData1 start...");
            mRefreshLayout.autoRefresh();
        }
    }
}
