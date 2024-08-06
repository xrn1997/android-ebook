package com.xrn1997.common.event

import io.reactivex.rxjava3.core.Observer
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable

/**
 * 简易Observer,如果传入CompositeDisposable,则自动添加Disposable
 * @author xrn1997
 */
abstract class SimpleObserver<T : Any>(
    compositeDisposable: CompositeDisposable? = null
) : Observer<T> {
    private val mCompositeDisposable = compositeDisposable
    override fun onSubscribe(d: Disposable) {
        mCompositeDisposable?.add(d)
    }

    override fun onComplete() {}
}