package com.ebook.login.mvvm.viewmodel;

import android.app.Application;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.databinding.ObservableField;

import com.blankj.utilcode.util.SPUtils;
import com.ebook.api.dto.RespDTO;
import com.ebook.api.http.ExceptionHandler;
import com.ebook.common.event.KeyCode;
import com.ebook.common.event.RxBusTag;
import com.ebook.common.util.ToastUtil;
import com.ebook.login.ModifyPwdActivity;
import com.ebook.login.mvvm.model.ModifyPwdModel;
import com.hwangjr.rxbus.RxBus;
import com.therouter.TheRouter;
import com.xrn1997.common.event.SingleLiveEvent;
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel;

import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;

public class ModifyPwdViewModel extends BaseViewModel<ModifyPwdModel> {
    private static final String TAG = ModifyPwdViewModel.class.getSimpleName();
    public ObservableField<String> username = new ObservableField<>();
    public ObservableField<String> verifyCode = new ObservableField<>();
    public ObservableField<String> password_1 = new ObservableField<>();
    public ObservableField<String> password_2 = new ObservableField<>();
    public String mVerifyCode; // 验证码
    private SingleLiveEvent<Void> mVoidSingleLiveEvent;

    public ModifyPwdViewModel(@NonNull Application application, ModifyPwdModel model) {
        super(application, model);
    }

    public void verify() {
        if (TextUtils.isEmpty(username.get())) {//用户名为空
            ToastUtil.showToast("手机号不能为空");
            return;
        } else if (TextUtils.getTrimmedLength(username.get()) < 11) { // 手机号码不足11位
            ToastUtil.showToast("请输入正确的手机号");
            return;
        }
        if (!TextUtils.equals(verifyCode.get(), mVerifyCode)) {
            ToastUtil.showToast("请输入正确的验证码");
            return;
        }
        postFinishActivityEvent();
        toFgtPwdActivity();
    }

    private void toFgtPwdActivity() {
        Bundle bundle = new Bundle();
        bundle.putString("username", username.get());
        Log.e(TAG, "toFgtPwdActivity: username:" + username.get());
        postStartActivityEvent(ModifyPwdActivity.class, bundle);
    }

    public void modify() {
        if (TextUtils.isEmpty(password_1.get()) || TextUtils.isEmpty((password_2.get()))) {
            ToastUtil.showToast("密码未填写完整");
            return;
        }
        if (!TextUtils.equals(password_1.get(), password_2.get())) {//两次密码不一致
            ToastUtil.showToast("两次密码不一致");
            return;
        }
        Log.d(TAG, "modify: username: " + username.get() + ",password: " + password_1.get());
        mModel.modifyPwd(username.get(), password_1.get()).subscribe(new Observer<>() {

            @Override
            public void onSubscribe(Disposable d) {

            }

            @Override
            public void onNext(RespDTO<Integer> loginDTORespDTO) {
                //  Log.d(TAG, "修改密码onNext: start");
                if (loginDTORespDTO.code == ExceptionHandler.APP_ERROR.SUCC) {
                    ToastUtil.showToast("修改成功");
                    SPUtils.getInstance().clear();
                    RxBus.get().post(RxBusTag.SET_PROFILE_PICTURE_AND_NICKNAME);
                    Bundle bundle = new Bundle();
                    bundle.putString("username", username.get());
                    bundle.putString("password", password_1.get());
                    TheRouter.build(KeyCode.Login.LOGIN_PATH)
                            .with(bundle)
                            .navigation();
                    //    Log.d(TAG, "修改密码onNext: finish");
                } else {
                    Log.v(TAG, "修改密码error:" + loginDTORespDTO.error);
                }
            }

            @Override
            public void onError(Throwable e) {
                getMVoidSingleLiveEvent().call();
            }

            @Override
            public void onComplete() {
                //   Log.d(TAG, "修改密码onComplete: start");
                getMVoidSingleLiveEvent().call();
                postFinishActivityEvent();
            }
        });
    }

    private SingleLiveEvent<Void> getMVoidSingleLiveEvent() {
        // Log.d(TAG, "getMVoidSingleLiveEvent: start");
        mVoidSingleLiveEvent = createLiveData(mVoidSingleLiveEvent);
        // Log.d(TAG, "getMVoidSingleLiveEvent: end");
        return mVoidSingleLiveEvent;
    }
}
