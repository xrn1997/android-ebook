package com.ebook.common.mvp.view;

/**
 * Description: <ILoadView><br>
 */
public interface ILoadView {
    //显示初始加载的View，初始进来加载数据需要显示的View
    void showInitLoadView();

    //隐藏初始加载的View
    void hideInitLoadView();
}
