package com.ebook.main;


import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Handler;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModelProvider;

import com.blankj.utilcode.util.SPUtils;
import com.ebook.common.event.KeyCode;
import com.ebook.login.mvvm.factory.LoginViewModelFactory;
import com.ebook.login.mvvm.viewmodel.LoginViewModel;
import com.ebook.main.databinding.ActivitySplashBinding;
import com.xrn1997.common.mvvm.view.BaseMvvmActivity;

//TODO 适配Android 12+
@SuppressLint("CustomSplashScreen")
public class SplashActivity extends BaseMvvmActivity<ActivitySplashBinding, LoginViewModel> {
    private final Handler mHandler = new Handler();
    private final Runnable mRunnableToMain = this::startMainActivity;

    @Override
    public int onBindLayout() {
        return R.layout.activity_splash;
    }

    @Override
    public void initView() {
        mHandler.postDelayed(mRunnableToMain, 3000);
        getBinding().idBtnSkip.setOnClickListener(view -> {
            mHandler.removeCallbacks(mRunnableToMain);
            startMainActivity();
        });
    }

    @Override
    public boolean enableToolbar() {
        return false;
    }

    @Override
    public boolean enableFitsSystemWindows() {
        return false;
    }

    @Override
    public void initData() {
        String username = SPUtils.getInstance().getString(KeyCode.Login.SP_USERNAME);
        String password = SPUtils.getInstance().getString(KeyCode.Login.SP_PASSWORD);
        //  Log.d(TAG, "SplashActivity initData: username: " + username + ",password: " + password);
        if ((!TextUtils.isEmpty(username)) && (!TextUtils.isEmpty(password))) {
            mViewModel.login(username, password);//启动应用后自动登录
        }
    }

    public void startMainActivity() {
        //打开主界面
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @NonNull
    @Override
    public Class<LoginViewModel> onBindViewModel() {
        return LoginViewModel.class;
    }

    @NonNull
    @Override
    public ViewModelProvider.Factory onBindViewModelFactory() {
        return LoginViewModelFactory.INSTANCE;
    }

    @Override
    public void initViewObservable() {
    }

    @Override
    public int onBindVariableId() {
        return 0;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacks(mRunnableToMain);
    }
}