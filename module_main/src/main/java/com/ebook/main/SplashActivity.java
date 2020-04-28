package com.ebook.main;


import androidx.lifecycle.ViewModelProvider;

import android.content.Intent;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import androidx.databinding.ViewDataBinding;

import com.blankj.utilcode.util.SPUtils;
import com.ebook.api.CommonService;
import com.ebook.common.event.KeyCode;
import com.ebook.common.mvvm.BaseMvvmActivity;
import com.ebook.login.mvvm.factory.LoginViewModelFactory;
import com.ebook.login.mvvm.viewmodel.LoginViewModel;


public class SplashActivity extends BaseMvvmActivity<ViewDataBinding, LoginViewModel> {
    private Button mBtnSkip;
    private Handler mHandler = new Handler();
    private Runnable mRunnableToMain = new Runnable() {
        @Override
        public void run() {
            startMainActivity();
        }
    };

    @Override
    public int onBindLayout() {
        return R.layout.activity_splash;
    }

    @Override
    public void initView() {
        mBtnSkip = (Button) findViewById(R.id.id_btn_skip);
        mHandler.postDelayed(mRunnableToMain, 3000);
    }

    @Override
    public boolean enableToolbar() {
        return false;
    }

    @Override
    public void initData() {
        String username = SPUtils.getInstance().getString(KeyCode.Login.SP_USERNAME);
        String password = SPUtils.getInstance().getString(KeyCode.Login.SP_PASSWORD);
        Log.d(TAG, "SplashActivity initData: username: " + username + ",password: " + password);
        if ((!TextUtils.isEmpty(username)) && (!TextUtils.isEmpty(password))) {
            mViewModel.login(username, password);//启动应用后自动登录
        }

    }

    @Override
    public void initListener() {
        super.initListener();
        mBtnSkip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mHandler.removeCallbacks(mRunnableToMain);
                startMainActivity();
            }
        });
    }

    //    public boolean isOnline() {
//        //判断网络状态
//        ConnectivityManager connMgr = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
//        NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();
//        return (networkInfo != null && networkInfo.isConnected());
//    }
    public void startMainActivity() {
        //打开主界面
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override
    public Class<LoginViewModel> onBindViewModel() {
        return LoginViewModel.class;
    }

    @Override
    public ViewModelProvider.Factory onBindViewModelFactory() {
        return LoginViewModelFactory.getInstance(getApplication());
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