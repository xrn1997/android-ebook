package com.ebook.common.mvp.view;

import android.content.Context;

/**
 * Description: <BaseView><br>
 */
public interface BaseView extends ILoadView,INoDataView,ITransView,INetErrView{
    void initView();
    void initListener();
    void initData();
    void finishActivity();
    Context getContext();
}
