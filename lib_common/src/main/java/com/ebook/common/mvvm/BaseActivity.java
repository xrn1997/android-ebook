package com.ebook.common.mvvm;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.os.Bundle;

import androidx.annotation.LayoutRes;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.alibaba.android.arouter.launcher.ARouter;
import com.ebook.common.R;
import com.ebook.common.mvvm.view.IBaseView;
import com.ebook.common.util.NetUtil;
import com.ebook.common.view.LoadingTransView;

import com.ebook.common.event.common.BaseActivityEvent;
import com.ebook.common.manager.ActivityManager;
import com.ebook.common.view.LoadingInitView;
import com.ebook.common.view.NetErrorView;
import com.ebook.common.view.NoDataView;
import com.hwangjr.rxbus.RxBus;
import com.trello.rxlifecycle3.components.support.RxAppCompatActivity;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;


public abstract class BaseActivity extends RxAppCompatActivity implements IBaseView {
    protected static final String TAG = BaseActivity.class.getSimpleName();
    protected TextView mTxtTitle;
    protected Toolbar mToolbar;
    protected NetErrorView mNetErrorView;
    protected NoDataView mNoDataView;
    protected LoadingInitView mLoadingInitView;
    protected LoadingTransView mLoadingTransView;
    private RelativeLayout mViewStubContent;
    private ViewStub mViewStubToolbar;
    private ViewStub mViewStubInitLoading;
    private ViewStub mViewStubTransLoading;
    private ViewStub mViewStubNoData;
    private ViewStub mViewStubError;
    private ViewGroup mContentView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);//由系统选择屏幕方向
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);//竖屏
        super.setContentView(R.layout.activity_root1);
        mContentView = (ViewGroup) findViewById(android.R.id.content);
        initCommonView();
        initContentView();
        //在baseActivity自动注入属性
        ARouter.getInstance().inject(this);
        initView();
        initListener();
        initData();
        EventBus.getDefault().register(this);
        RxBus.get().register(this);
        ActivityManager.getInstance().addActivity(this);
    }


    protected void initCommonView() {
        mViewStubToolbar = findViewById(R.id.view_stub_toolbar);
        mViewStubContent = findViewById(R.id.view_stub_content);
        mViewStubInitLoading = findViewById(R.id.view_stub_init_loading);
        mViewStubTransLoading = findViewById(R.id.view_stub_trans_loading);
        mViewStubError = findViewById(R.id.view_stub_error);
        mViewStubNoData = findViewById(R.id.view_stub_nodata);

        if (enableToolbar()) {
            mViewStubToolbar.setLayoutResource(onBindToolbarLayout());
            View view = mViewStubToolbar.inflate();
            initToolbar(view);
        }
    }

    @Override
    public void setContentView(@LayoutRes int layoutResID) {
        if (mViewStubContent != null) {
            initContentView(layoutResID);
        }
    }

    public void initContentView() {
        initContentView(onBindLayout());
    }

    private void initContentView(@LayoutRes int layoutResID) {
        View view = LayoutInflater.from(this).inflate(layoutResID, mViewStubContent, false);
        mViewStubContent.setId(android.R.id.content);
        mContentView.setId(View.NO_ID);
        mViewStubContent.removeAllViews();
        mViewStubContent.addView(view);
    }

    protected void initToolbar(View view) {
        mToolbar = view.findViewById(R.id.toolbar_root);
        mTxtTitle = view.findViewById(R.id.toolbar_title);
        if (mToolbar != null) {
            setSupportActionBar(mToolbar);
            getSupportActionBar().setDisplayShowTitleEnabled(false);
            mToolbar.setNavigationOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onBackPressed();
                }
            });
        }
    }

    @Override
    protected void onTitleChanged(CharSequence title, int color) {
        super.onTitleChanged(title, color);
        if (mTxtTitle != null && !TextUtils.isEmpty(title)) {
            mTxtTitle.setText(title);
        }
        //可以再次覆盖设置title
        String tootBarTitle = getTootBarTitle();
        if (mTxtTitle != null && !TextUtils.isEmpty(tootBarTitle)) {
            mTxtTitle.setText(tootBarTitle);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
        RxBus.get().unregister(this);
        ActivityManager.getInstance().finishActivity(this);
    }

    public int onBindToolbarLayout() {
        return R.layout.common_toolbar;
    }

    public abstract int onBindLayout();

    public abstract void initView();

    public abstract void initData();

    public void initListener() {
    }

    @Override
    public void finishActivity() {
        finish();
    }

    public String getTootBarTitle() {
        return "";
    }

    public boolean enableToolbar() {
        return true;
    }

    public void showInitLoadView(boolean show) {
        if (mLoadingInitView == null) {
            View view = mViewStubInitLoading.inflate();
            mLoadingInitView = view.findViewById(R.id.view_init_loading);
        }
        mLoadingInitView.setVisibility(show ? View.VISIBLE : View.GONE);
        mLoadingInitView.loading(show);
    }


    public void showNetWorkErrView(boolean show) {
        if (mNetErrorView == null) {
            View view = mViewStubError.inflate();
            mNetErrorView = view.findViewById(R.id.view_net_error);
            mNetErrorView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!NetUtil.checkNetToast()) {
                        return;
                    }
                    showNetWorkErrView(false);
                    initData();
                }
            });
        }
        mNetErrorView.setVisibility(show ? View.VISIBLE : View.GONE);
    }


    public void showNoDataView(boolean show) {
        if (mNoDataView == null) {
            View view = mViewStubNoData.inflate();
            mNoDataView = view.findViewById(R.id.view_no_data);
        }
        mNoDataView.setVisibility(show ? View.VISIBLE : View.GONE);
    }


    public void showTransLoadingView(boolean show) {
        if (mLoadingTransView == null) {
            View view = mViewStubTransLoading.inflate();
            mLoadingTransView = view.findViewById(R.id.view_trans_loading);
        }
        mLoadingTransView.setVisibility(show ? View.VISIBLE : View.GONE);
        mLoadingTransView.loading(show);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public <T> void onEvent(BaseActivityEvent<T> event) {
    }

    @Override
    public Context getContext() {
        return this;
    }
}
