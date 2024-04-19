package com.ebook.common.mvp.view;

/**
 * Description: <ITransView><br>
 */
public interface ITransView {
    //显示背景透明小菊花View,例如删除操作
    void showTransLoadingView();

    //隐藏背景透明小菊花View
    void hideTransLoadingView();
}
