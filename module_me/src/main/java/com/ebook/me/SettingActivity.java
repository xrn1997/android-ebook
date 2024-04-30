package com.ebook.me;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.blankj.utilcode.util.SPUtils;
import com.ebook.api.RetrofitManager;
import com.ebook.common.event.KeyCode;
import com.ebook.common.event.RxBusTag;
import com.ebook.common.util.ToastUtil;
import com.ebook.me.databinding.ActivitySettingBinding;
import com.hwangjr.rxbus.RxBus;
import com.therouter.router.Route;
import com.xrn1997.common.mvvm.view.BaseActivity;

@Route(path = KeyCode.Me.SETTING_PATH, params = {"needLogin", "true"})
public class SettingActivity extends BaseActivity<ActivitySettingBinding> {
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RxBus.get().register(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        RxBus.get().unregister(this);
    }
    @Override
    public void initView() {
        Button mExitButton = findViewById(R.id.btn_exit);
        mExitButton.setOnClickListener(v -> {
            SPUtils.getInstance().clear();
            RetrofitManager.getInstance().TOKEN = "";
            ToastUtil.showToast("退出登录成功");
            RxBus.get().post(RxBusTag.SET_PROFILE_PICTURE_AND_NICKNAME, new Object());//更新UI
            finish();
        });
    }

    @Override
    public void initData() {
    }

    @NonNull
    @Override
    public ActivitySettingBinding onBindViewBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, boolean attachToParent) {
        return ActivitySettingBinding.inflate(inflater, container, attachToParent);
    }
}
