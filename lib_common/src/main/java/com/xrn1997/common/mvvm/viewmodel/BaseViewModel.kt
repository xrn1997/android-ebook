package com.xrn1997.common.mvvm.viewmodel

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import com.xrn1997.common.event.SingleLiveEvent
import com.xrn1997.common.mvvm.IBaseModel
import com.xrn1997.common.mvvm.IBaseViewModel
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.functions.Consumer

/**
 * ViewModel基类
 * @author xrn1997
 */
@Suppress("unused")
open class BaseViewModel<M : IBaseModel>(
    application: Application,
    @JvmField val mModel: M
) : AndroidViewModel(application), IBaseViewModel, Consumer<Disposable> {

    private var defaultUIChangeLiveData: UIChangeLiveData? = null
    val mUIChangeLiveData: UIChangeLiveData
        get() {
            if (defaultUIChangeLiveData == null) {
                defaultUIChangeLiveData = UIChangeLiveData()
            }
            return defaultUIChangeLiveData as UIChangeLiveData
        }

    /**
     *  createLiveData 参数对象为空时返回SingeLiveEvent
     * @param liveData SingleLiveEvent<T>?
     * @return SingleLiveEvent<T>
     */
    protected fun <T> createLiveData(liveData: SingleLiveEvent<T>?): SingleLiveEvent<T> =
        liveData ?: SingleLiveEvent()

    /**
     * UIChange LiveData
     * @author xrn1997
     */
    inner class UIChangeLiveData {
        private var showLoadingViewEvent: SingleLiveEvent<Boolean>? = null
        private var showNoDataViewEvent: SingleLiveEvent<Boolean>? = null
        private var showNetWorkErrViewEvent: SingleLiveEvent<Boolean>? = null
        private var startActivityEvent: SingleLiveEvent<Map<String, Any>>? = null
        private var finishActivityEvent: SingleLiveEvent<Void>? = null
        private var onBackPressedEvent: SingleLiveEvent<Void>? = null
        val mShowLoadingViewEvent
            get() = createLiveData(showLoadingViewEvent).also {
                showLoadingViewEvent = it
            }
        val mShowNoDataViewEvent
            get() = createLiveData(showNoDataViewEvent).also { showNoDataViewEvent = it }
        val mShowNetWorkErrViewEvent
            get() = createLiveData(showNetWorkErrViewEvent).also { showNetWorkErrViewEvent = it }
        val mStartActivityEvent
            get() = createLiveData(startActivityEvent).also { startActivityEvent = it }
        val mFinishActivityEvent
            get() = createLiveData(finishActivityEvent).also { finishActivityEvent = it }
        val mOnBackPressedEvent
            get() = createLiveData(onBackPressedEvent).also { onBackPressedEvent = it }
    }
    /**
     * 显示无数据视图
     * @param show Boolean true为显示，false为隐藏
     */
    fun postShowNoDataViewEvent(show: Boolean) {
        mUIChangeLiveData.mShowNoDataViewEvent.postValue(show)
    }

    /**
     * 显示加载视图（半透明背景）
     * @param show Boolean true为显示，false为隐藏
     */
    fun postShowLoadingViewEvent(show: Boolean) {
        mUIChangeLiveData.mShowLoadingViewEvent.postValue(show)
    }

    /**
     * 显示网络错误视图
     * @param show Boolean true为显示，false为隐藏
     */
    fun postShowNetWorkErrViewEvent(show: Boolean) {
        mUIChangeLiveData.mShowNetWorkErrViewEvent.postValue(show)
    }

    /**
     * 启动Activity事件
     * @param clz Class<*> 类::class.java
     * @param bundle Bundle? 数据
     */
    fun postStartActivityEvent(clz: Class<*>, bundle: Bundle? = null) {
        val params: MutableMap<String, Any> = HashMap()
        params[ParameterField.CLASS] = clz
        if (bundle != null) {
            params[ParameterField.BUNDLE] = bundle
        }
        mUIChangeLiveData.mStartActivityEvent.postValue(params)
    }

    /**
     * 停止Activity事件
     */
    fun postFinishActivityEvent() {
        mUIChangeLiveData.mFinishActivityEvent.call()
    }

    /**
     * 返回事件
     */
    fun postOnBackPressedEvent() {
        mUIChangeLiveData.mOnBackPressedEvent.call()
    }

    /**
     * add disposable
     * @param t Disposable 一次性订阅
     * @throws Exception
     */
    @Throws(Exception::class)
    override fun accept(t: Disposable) {
        mModel.addSubscribe(t)
    }

    /**
     * clear disposable
     */
    override fun onCleared() {
        super.onCleared()
        mModel.onCleared()
    }

    companion object {
        object ParameterField {
            var CLASS = "CLASS"
            var CANONICAL_NAME = "CANONICAL_NAME"
            var BUNDLE = "BUNDLE"
        }
    }
}