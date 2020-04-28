package com.ebook.common.mvp.presenter;

import android.content.Context;

import com.ebook.common.mvp.contract.BaseRefreshContract;
import com.ebook.common.mvp.model.BaseModel;
import com.ebook.common.mvp.view.BaseRefreshView;

/**
 * Description: <BaseRefreshPresenter><br>
 */
public abstract class BaseRefreshPresenter<M extends BaseModel,V extends BaseRefreshView<T>,T> extends BasePresenter<M,V> implements BaseRefreshContract.Presenter {

    public BaseRefreshPresenter(Context context, V view, M model) {
        super(context, view, model);
    }
}
