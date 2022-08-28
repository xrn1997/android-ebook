package com.ebook.api.http;

import android.widget.Toast;

import com.ebook.api.RetrofitManager;
import com.ebook.api.dto.RespDTO;
import com.trello.rxlifecycle3.LifecycleProvider;
import com.trello.rxlifecycle3.LifecycleTransformer;
import com.trello.rxlifecycle3.android.ActivityEvent;

import io.reactivex.Observable;
import io.reactivex.ObservableTransformer;
import io.reactivex.SingleTransformer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.annotations.NonNull;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;

public class RxAdapter {
    /**
     * 生命周期绑定
     *
     * @param lifecycle Activity
     */
    public static <T> LifecycleTransformer<T> bindUntilEvent(@NonNull LifecycleProvider<ActivityEvent> lifecycle) {
        if (lifecycle != null) {
            return lifecycle.bindUntilEvent(ActivityEvent.DESTROY);
        } else {
            throw new IllegalArgumentException("context not the LifecycleProvider type");
        }
    }

    /**
     * 线程调度器
     */
    public static SingleTransformer singleSchedulersTransformer() {
        return upstream -> upstream.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    public static SingleTransformer singleExceptionTransformer() {

        return observable -> observable
                .map(new HandleFuc())  //这里可以取出BaseResponse中的Result
                .onErrorResumeNext(new HttpResponseFunc());
    }

    /**
     * 线程调度器
     */
    public static ObservableTransformer schedulersTransformer() {
        return upstream -> upstream.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    /**
     * 处理意外
     */
    public static ObservableTransformer exceptionTransformer() {

        return observable -> observable
                .map(new HandleFuc())  //这里可以取出BaseResponse中的Result
                .onErrorResumeNext(new HttpResponseFunc());
    }

    private static class HttpResponseFunc<T> implements Function<Throwable, Observable<T>> {
        @Override
        public Observable<T> apply(Throwable t) {
            ResponseThrowable exception = ExceptionHandler.handleException(t);
            if (exception.code == ExceptionHandler.SYSTEM_ERROR.TIMEOUT_ERROR) {
                Toast.makeText(RetrofitManager.mContext, "连接超时！", Toast.LENGTH_SHORT).show();
            }
            return Observable.error(exception);
        }
    }

    private static class HandleFuc implements Function<Object, Object> {

        @Override
        public Object apply(Object o) throws Exception {
            if (o instanceof RespDTO) {
                RespDTO respDTO = (RespDTO) o;
                if (respDTO.code != ExceptionHandler.APP_ERROR.SUCC) {
                    Toast.makeText(RetrofitManager.mContext, respDTO.error, Toast.LENGTH_SHORT).show();
                }
            }
            return o;
        }
    }

}
