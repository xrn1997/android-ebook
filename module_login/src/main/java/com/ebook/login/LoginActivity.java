package com.ebook.login;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.alibaba.android.arouter.facade.annotation.Autowired;
import com.alibaba.android.arouter.facade.annotation.Route;
import com.ebook.common.event.KeyCode;
import com.ebook.common.mvvm.BaseMvvmActivity;
import com.ebook.login.databinding.ActivityLoginBinding;
import com.ebook.login.mvvm.factory.LoginViewModelFactory;
import com.ebook.login.mvvm.viewmodel.LoginViewModel;

import androidx.lifecycle.ViewModelProvider;

@Route(path = KeyCode.Login.Login_PATH)
public class LoginActivity extends BaseMvvmActivity<ActivityLoginBinding, LoginViewModel> {
    private TextView mTvRegister;
    private TextView mTvForgetPwd;
    @Autowired
    public String path;

    private Bundle mBundle;//储存被拦截的信息

    @Override
    public int onBindLayout() {
        return R.layout.activity_login;
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
    public int onBindVariableId() {
        return BR.viewModel;
    }


    /**
     * 禁止显示Toolbar，默认为true
     */
    @Override
    public boolean enableToolbar() {
        return false;
    }

    @Override
    public void initView() {
        super.initView();
        mTvRegister = (TextView) findViewById(R.id.id_tv_register);
        mTvForgetPwd = (TextView) findViewById(R.id.id_tv_fgt_pwd);
    }

    @Override
    public void initListener() {
        super.initListener();
        mTvRegister.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toRegisterActivity();
            }
        });

        mTvForgetPwd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toForgetPwdActivity();
            }
        });
    }

    @Override
    public void initData() {

    }

    @Override
    protected void onStart() {
        super.onStart();
        Bundle bundle = this.getIntent().getExtras();
        String username = "", password = "";
        if (bundle != null && !bundle.isEmpty()) {
            username = (String) bundle.get("username");
            password = (String) bundle.get("password");
        }
        Log.e(TAG, "登录页面接收的参数，username: " + username + ",password: " + password);
        if (!(TextUtils.isEmpty(username)) && (!TextUtils.isEmpty(password))) {
            mViewModel.username.set(username);
            mViewModel.password.set(password);
        }
        if ((!TextUtils.isEmpty(path)) && mBundle == null) {
            mViewModel.path = path;
            if (bundle != null && !bundle.isEmpty()) {
                mBundle = bundle;
                mViewModel.bundle = mBundle;
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    private void toRegisterActivity() {
        Intent intent = new Intent(this, RegisterActivity.class);
        startActivity(intent);
    }

    private void toForgetPwdActivity() {
        Intent intent = new Intent(this, VerifyUserActivity.class);
        startActivity(intent);
    }

    @Override
    public void initViewObservable() {

    }


}
