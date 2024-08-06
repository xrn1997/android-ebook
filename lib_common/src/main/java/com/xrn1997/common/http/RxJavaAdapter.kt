package com.xrn1997.common.http

import android.util.Log
import com.blankj.utilcode.util.ToastUtils
import com.xrn1997.common.constant.ErrorCode
import com.xrn1997.common.dto.RespDTO
import com.xrn1997.common.http.ExceptionHandler.handleException
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.ObservableTransformer
import io.reactivex.rxjava3.functions.Function
import io.reactivex.rxjava3.schedulers.Schedulers

/**
 * RxJavaAdapter类
 * @author xrn1997
 */
@Suppress("unused")
object RxJavaAdapter {

    private const val TAG = "RxJavaAdapter"

    /**
     * 线程调度器,以上代码均在IO线程中执行,以下代码均在主线程中执行
     */
    fun <T : Any> schedulersTransformer(): ObservableTransformer<T, T> {
        return ObservableTransformer { upstream ->
            upstream.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
        }
    }

    /**
     * 处理意外
     * @see
     */
    fun <T : Any> exceptionTransformer(): ObservableTransformer<T, T> {
        return ObservableTransformer { observable: Observable<T> ->
            observable
                //这里可以取出BaseResponse中的Result
                .map(AppErrorHandler())
                .onErrorResumeNext(HttpErrorHandler())
        }
    }

    /**
     * HTTP异常处理
     */
    class HttpErrorHandler<T : Any> : Function<Throwable, Observable<T>> {
        override fun apply(t: Throwable): Observable<T> {
            Log.d(TAG, "HTTP异常处理")
            val exception = handleException(t)
            Log.d(TAG, exception.toString())
            if (exception.code == ExceptionHandler.SystemError.NETWORK_ERROR) {
                Log.d(TAG, "网络不给力哦！")
            }
            return Observable.error(exception)
        }
    }

    /**
     * 应用通用处理
     * @see RespDTO
     */
    class AppErrorHandler<T : Any> : Function<T, T> {

        @Throws(Exception::class)
        override fun apply(o: T): T {
            Log.d(TAG, "应用通用处理")
            val respDTO: RespDTO<*> = o as RespDTO<*>
            if (respDTO.code != ErrorCode.SUCCESS.code) {
                ToastUtils.showShort(respDTO.message)
            }
            return o
        }
    }

}
