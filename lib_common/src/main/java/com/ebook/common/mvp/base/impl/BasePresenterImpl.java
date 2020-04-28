package com.ebook.common.mvp.base.impl;



import com.ebook.common.mvp.base.IPresenter;
import com.ebook.common.mvp.base.IView;

import androidx.annotation.NonNull;

public abstract class BasePresenterImpl<T extends IView> implements IPresenter {
    protected T mView;

    @Override
    public void attachView(@NonNull IView iView) {
        mView = (T) iView;
    }
}
