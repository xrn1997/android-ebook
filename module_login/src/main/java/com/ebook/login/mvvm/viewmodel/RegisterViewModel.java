package com.ebook.login.mvvm.viewmodel;

import android.app.Application;
import android.text.TextUtils;
import android.util.Log;

import com.ebook.api.dto.RespDTO;
import com.ebook.api.http.ExceptionHandler;
import com.ebook.api.user.LoginDTO;
import com.ebook.common.event.SingleLiveEvent;
import com.ebook.common.mvvm.viewmodel.BaseViewModel;
import com.ebook.common.util.ToastUtil;
import com.ebook.login.mvvm.model.RegisterModel;

import java.util.Objects;

import androidx.annotation.NonNull;
import androidx.databinding.ObservableField;
import androidx.databinding.ObservableInt;
import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;

public class RegisterViewModel extends BaseViewModel<RegisterModel> {
    private static String TAG = RegisterViewModel.class.getSimpleName();
    private SingleLiveEvent<Void> mVoidSingleLiveEvent;
    public ObservableField<String> username = new ObservableField<>();
    public ObservableField<String> password_1 = new ObservableField<>();
    public ObservableField<String> password_2 = new ObservableField<>();
    public RegisterViewModel(@NonNull Application application, RegisterModel model) {
        super(application, model);
    }
    public void register(){

        if (TextUtils.isEmpty(username.get())) {//用户名为空
            ToastUtil.showToast("用户名不能为空");
            return;
        }
        if ( TextUtils.getTrimmedLength(username.get()) < 11) { // 手机号码不足11位
            ToastUtil.showToast("请输入正确的手机号");
            return;
        }
        if(TextUtils.isEmpty(password_1.get())||TextUtils.isEmpty((password_2.get()))){
            ToastUtil.showToast("密码未填写完整");
            return;
        }
        if (!TextUtils.equals(password_1.get(),password_2.get())) {//两次密码不一致
            ToastUtil.showToast("两次密码不一致");
            return;
        }
        //Log.d(TAG, password_1.get());
        //Log.d(TAG, password_2.get());
        mModel.register(username.get(),password_1.get()).subscribe(new Observer<RespDTO<LoginDTO>>() {
            @Override
            public void onSubscribe(Disposable d) {

            }

            @Override
            public void onNext(RespDTO<LoginDTO> loginDTORespDTO) {
                if (loginDTORespDTO.code == ExceptionHandler.APP_ERROR.SUCC) {
                    ToastUtil.showToast("注册成功");
                    postFinishActivityEvent();
                } else {
                    Log.v(TAG, "error:" + loginDTORespDTO.error);
                }
            }

            @Override
            public void onError(Throwable e) {
                getmVoidSingleLiveEvent().call();
            }

            @Override
            public void onComplete() {
                getmVoidSingleLiveEvent().call();
            }
        });
    }
    private SingleLiveEvent<Void> getmVoidSingleLiveEvent() {
        return mVoidSingleLiveEvent = createLiveData(mVoidSingleLiveEvent);
    }
}
