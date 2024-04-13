package com.ebook.me;

import android.widget.Button;

import com.blankj.utilcode.util.SPUtils;
import com.ebook.api.RetrofitManager;
import com.ebook.common.event.KeyCode;
import com.ebook.common.event.RxBusTag;
import com.ebook.common.mvvm.BaseActivity;
import com.ebook.common.util.ToastUtil;
import com.hwangjr.rxbus.RxBus;
import com.therouter.router.Route;

@Route(path = KeyCode.Me.SETTING_PATH, params = {"needLogin", "true"})
public class SettingActivity extends BaseActivity {
    private Button mExitButton;

    @Override
    public int onBindLayout() {
        return R.layout.activity_setting;
    }

    @Override
    public void initListener() {
        super.initListener();
        mExitButton.setOnClickListener(v -> {
            SPUtils.getInstance().clear();
            RetrofitManager.getInstance().TOKEN = "";
            ToastUtil.showToast("退出登录成功");
            RxBus.get().post(RxBusTag.SET_PROFIE_PICTURE_AND_NICKNAME, new Object());//更新UI
            finish();
        });
    }

    @Override
    public void initView() {
        mExitButton = findViewById(R.id.btn_exit);
    }

    @Override
    public void initData() {

    }
}
