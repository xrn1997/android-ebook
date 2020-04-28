package com.ebook.common.mvvm.view;

import android.content.Context;


public interface IBaseView {
    void initView();
    void initListener();
    void initData();
    void finishActivity();
    void showInitLoadView(boolean show);
    void showNoDataView(boolean show);
    void showTransLoadingView(boolean show);
    void showNetWorkErrView(boolean show);
    Context getContext();
}
