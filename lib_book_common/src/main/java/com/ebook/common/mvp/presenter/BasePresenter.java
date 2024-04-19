package com.ebook.common.mvp.presenter;

import android.content.Context;

import com.ebook.common.mvp.model.BaseModel;
import com.trello.rxlifecycle3.LifecycleProvider;


/**
 * Description: <BasePresenter><br>
 */
public abstract class BasePresenter<M extends BaseModel, V> {
    protected Context mContext;
    protected V mView;
    protected M mModel;

    public BasePresenter(Context context, V view, M model) {
        mContext = context;
        mView = view;
        mModel = model;
    }

    public void detach() {
        detachView();
        detachModel();
    }

    public void detachView() {
        mView = null;
    }


    public void detachModel() {
        mModel.destory();
        mModel = null;
    }

    public void injectLifecycle(LifecycleProvider lifecycle) {
        if (mModel != null) {
            mModel.injectLifecycle(lifecycle);
        }
    }
}
