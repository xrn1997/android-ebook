package com.ebook.basebook.base.activity;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;


import com.ebook.basebook.base.manager.AppActivityManager;
import com.ebook.basebook.base.IPresenter;
import com.ebook.basebook.base.IView;
import com.hwangjr.rxbus.RxBus;
import com.trello.rxlifecycle3.components.support.RxAppCompatActivity;

import androidx.annotation.NonNull;

public abstract class BaseActivity<T extends IPresenter> extends RxAppCompatActivity implements IView {
    public final static String start_share_ele = "start_with_share_ele";
    protected Bundle savedInstanceState;
    protected T mPresenter;
    private Boolean startShareAnim = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.savedInstanceState = savedInstanceState;
        if (getIntent() != null) {
            startShareAnim = getIntent().getBooleanExtra(start_share_ele, false);
        }
        AppActivityManager.getInstance().add(this);
        RxBus.get().register(this);
        initSDK();
        onCreateActivity();
        mPresenter = initInjector();
        attachView();
        initData();
        bindView();
        bindEvent();
        firstRequest();
    }

    /**
     * 首次逻辑操作
     */
    protected void firstRequest() {

    }

    /**
     * 事件触发绑定
     */
    protected void bindEvent() {

    }

    /**
     * 控件绑定
     */
    protected void bindView() {

    }

    /**
     * P层绑定V层
     */
    private void attachView() {
        if (null != mPresenter) {
            mPresenter.attachView(this);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    /**
     * P层解绑V层
     */
    private void detachView() {
        if (null != mPresenter) {
            mPresenter.detachView();
        }
    }

    /**
     * SDK初始化
     */
    protected void initSDK() {

    }

    /**
     * P层绑定   若无则返回null;
     *
     * @return
     */
    protected abstract T initInjector();

    /**
     * 布局载入  setContentView()
     */
    protected abstract void onCreateActivity();

    /**
     * 数据初始化
     */
    protected abstract void initData();

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        detachView();
        RxBus.get().unregister(this);
        AppActivityManager.getInstance().remove(this);
    }

    ////////////////////////////////启动Activity转场动画/////////////////////////////////////////////

    protected void startActivityForResultByAnim(Intent intent, int requestCode, int animIn, int animExit) {
        startActivityForResult(intent, requestCode);
        overridePendingTransition(animIn, animExit);
    }

    protected void startActivityByAnim(Intent intent, int animIn, int animExit) {
        startActivity(intent);
        overridePendingTransition(animIn, animExit);
    }

    protected void startActivityForResultByAnim(Intent intent, int requestCode, @NonNull View view, @NonNull String transitionName, int animIn, int animExit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            startActivityForResult(intent, requestCode, ActivityOptions.makeSceneTransitionAnimation(this, view, transitionName).toBundle());
        } else {
            startActivityForResultByAnim(intent, requestCode, animIn, animExit);
        }
    }

    protected void startActivityByAnim(Intent intent, @NonNull View view, @NonNull String transitionName, int animIn, int animExit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            intent.putExtra(start_share_ele, true);
            startActivity(intent, ActivityOptions.makeSceneTransitionAnimation(this, view, transitionName).toBundle());
        } else {
            startActivityByAnim(intent, animIn, animExit);
        }
    }

    public Context getContext() {
        return this;
    }

    public Boolean getStart_share_ele() {
        return startShareAnim;
    }
}