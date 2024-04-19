package com.xrn1997.common.mvvm.model

import android.app.Application
import com.xrn1997.common.mvvm.IBaseModel
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable

/**
 *BaseModel 用于实现IBaseModel接口
 * @author xrn1997
 */
@Suppress("unused")
abstract class BaseModel(protected var mApplication: Application) : IBaseModel {
    private val _compositeDisposable: CompositeDisposable = CompositeDisposable()
    val mCompositeDisposable get() = _compositeDisposable

    override fun addSubscribe(disposable: Disposable) {
        _compositeDisposable.add(disposable)
    }

    override fun onCleared() {
        _compositeDisposable.clear()
    }

}