package com.ebook.basebook.base.impl;


import androidx.annotation.NonNull;

import com.ebook.basebook.base.IPresenter;
import com.ebook.basebook.base.IView;

public abstract class BasePresenterImpl<T extends IView> implements IPresenter {
    protected T mView;

    @Override
    public void attachView(@NonNull IView iView) {
        mView = (T) iView;
    }
}
