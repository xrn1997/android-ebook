package com.ebook.login;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModelProvider;

import com.ebook.common.event.KeyCode;
import com.ebook.login.databinding.ActivityVerifyUserBinding;
import com.ebook.login.mvvm.factory.LoginViewModelFactory;
import com.ebook.login.mvvm.viewmodel.ModifyPwdViewModel;
import com.therouter.router.Route;
import com.xrn1997.common.mvvm.view.BaseMvvmActivity;

@Route(path = KeyCode.Login.MODIFY_PATH)
public class VerifyUserActivity extends BaseMvvmActivity<ActivityVerifyUserBinding, ModifyPwdViewModel> {

    @NonNull
    @Override
    public Class<ModifyPwdViewModel> onBindViewModel() {
        return ModifyPwdViewModel.class;
    }

    @NonNull
    @Override
    public ViewModelProvider.Factory onBindViewModelFactory() {
        return LoginViewModelFactory.INSTANCE;
    }

    @SuppressLint("DefaultLocale")
    private void getVerifyCode() {
        // 生成六位随机数字的验证码
        mViewModel.mVerifyCode = String.format("%06d", (int) (Math.random() * 1000000 % 1000000));
        // 弹出提醒对话框，提示用户六位验证码数字
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("请记住验证码");
        builder.setMessage("手机号" + mViewModel.username.get() + "，本次验证码是" + mViewModel.mVerifyCode + "，请输入验证码");
        builder.setPositiveButton("好的", null);
        AlertDialog alert = builder.create();
        alert.show();
    }

    @Override
    public void initView() {
        //获取验证码按钮
        Button mVerifyCodeButton = findViewById(R.id.btn_verifycode);
        mVerifyCodeButton.setOnClickListener(v -> getVerifyCode());
    }

    @Override
    public void initViewObservable() {
    }

    @Override
    public int onBindVariableId() {
        return BR.viewModel;
    }

    @Override
    public int onBindLayout() {
        return R.layout.activity_verify_user;
    }

    @Override
    public void initData() {

    }
}
