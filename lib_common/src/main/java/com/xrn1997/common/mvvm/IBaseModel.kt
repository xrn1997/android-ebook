package com.xrn1997.common.mvvm

import io.reactivex.rxjava3.disposables.Disposable

/**
 * BaseModel接口
 * @author xrn1997
 */
interface IBaseModel {
    /**
     *  清除CompositeDisposable对象
     * @date 2022/3/2 21:31
     */
    fun onCleared()

    /**
     * 添加CompositeDisposable对象
     * @date 2022/3/2 21:32
     */
    fun addSubscribe(disposable: Disposable)
}