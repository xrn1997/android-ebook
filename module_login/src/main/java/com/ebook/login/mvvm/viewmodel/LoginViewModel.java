package com.ebook.login.mvvm.viewmodel;

import android.app.Application;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.databinding.ObservableField;

import com.alibaba.android.arouter.launcher.ARouter;
import com.blankj.utilcode.util.SPUtils;
import com.ebook.api.RetrofitManager;
import com.ebook.api.dto.RespDTO;
import com.ebook.api.entity.LoginDTO;
import com.ebook.api.entity.User;
import com.ebook.api.http.ExceptionHandler;
import com.ebook.common.event.KeyCode;
import com.ebook.common.event.RxBusTag;
import com.ebook.common.event.SingleLiveEvent;
import com.ebook.common.mvvm.viewmodel.BaseViewModel;
import com.ebook.common.util.ToastUtil;
import com.ebook.login.mvvm.model.LoginModel;
import com.hwangjr.rxbus.RxBus;

import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;

public class LoginViewModel extends BaseViewModel<LoginModel> {
    private static String TAG = LoginViewModel.class.getSimpleName();
    public ObservableField<String> username = new ObservableField<>();
    public ObservableField<String> password = new ObservableField<>();
    public String path;//被拦截的路径
    public Bundle bundle;//被拦截的信息
    private SingleLiveEvent<Void> mVoidSingleLiveEvent;

    public LoginViewModel(@NonNull Application application, LoginModel model) {
        super(application, model);
    }

    public void login() {
        //重载login()
        login(username.get(), password.get());
    }

    public void login(String username, String password) {
        if (TextUtils.isEmpty(username)) {//用户名为空
            ToastUtil.showToast("用户名不能为空");
            //  Log.d(TAG, "login: " + username);
            return;
        }
        if (username.length() < 11) { // 手机号码不足11位
            ToastUtil.showToast("请输入正确的手机号");
            return;
        }
        if (TextUtils.isEmpty(password)) {//密码为空
            ToastUtil.showToast("密码不能为空");
            return;
        }

        mModel.login(username, password).subscribe(new Observer<RespDTO<LoginDTO>>() {
            @Override
            public void onSubscribe(Disposable d) {
                //  postShowInitLoadViewEvent(true);
            }

            @Override
            public void onNext(RespDTO<LoginDTO> loginDTORespDTO) {
                //   Log.d(TAG, "onNext: start");
                if (loginDTORespDTO.code == ExceptionHandler.APP_ERROR.SUCC) {
                    RetrofitManager.getInstance().TOKEN = "Bearer " + loginDTORespDTO.data.getToken();
                    User user = loginDTORespDTO.data.getUser();
                    user.setPassword(password);//返回的是加密过的密码，不能使用，需要记住本地输入的密码。
                    loginOnNext(user);//非自动登录
                    RxBus.get().post(RxBusTag.SET_PROFIE_PICTURE_AND_NICKNAME, new Object());//通知其更新UI
                } else if (loginDTORespDTO.code == ExceptionHandler.SYSTEM_ERROR.UNAUTHORIZED) {
                    RxBus.get().post(RxBusTag.SET_PROFIE_PICTURE_AND_NICKNAME, new Object());
                    SPUtils.getInstance().clear();
                    //   Log.d(TAG, "登录失效 is login 状态：" + SPUtils.getInstance().getString(KeyCode.Login.SP_IS_LOGIN));
                    Log.v(TAG, "error:" + loginDTORespDTO.error);
                } else {
                    Log.v(TAG, "error:" + loginDTORespDTO.error);
                }
            }

            @Override
            public void onError(Throwable e) {
                //   postShowInitLoadViewEvent(false);
            }

            @Override
            public void onComplete() {
                //   postShowInitLoadViewEvent(false);
            }
        });
    }

    private void loginOnNext(User user) {
        //不是自动登录则调用以下语句
        SPUtils spUtils = SPUtils.getInstance();
        if (!spUtils.getBoolean(KeyCode.Login.SP_IS_LOGIN)) {
            spUtils.put(KeyCode.Login.SP_IS_LOGIN, true);
            spUtils.put(KeyCode.Login.SP_USERNAME, user.getUsername());
            spUtils.put(KeyCode.Login.SP_PASSWORD, user.getPassword());
            spUtils.put(KeyCode.Login.SP_NICKNAME, user.getNickname());
            spUtils.put(KeyCode.Login.SP_USER_ID, user.getId());
            spUtils.put(KeyCode.Login.SP_IMAGE, user.getImage());
            postShowTransLoadingViewEvent(false);
            toAimActivity();
            postFinishActivityEvent();
            ToastUtil.showToast("登录成功");
            // Log.d(TAG, "onNext: finish");
        }
    }

    private void toAimActivity() {
        if (!TextUtils.isEmpty(path)) {
            ARouter router = ARouter.getInstance();
            if (!bundle.isEmpty()) {
                router.build(path).with(bundle).navigation();
            } else {
                router.build(path).navigation();
            }
        }
    }


}
