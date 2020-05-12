package com.ebook.me;

import android.view.View;
import android.widget.Button;

import com.alibaba.android.arouter.facade.annotation.Route;
import com.blankj.utilcode.util.SPUtils;
import com.ebook.api.RetrofitManager;
import com.ebook.common.event.KeyCode;
import com.ebook.common.event.RxBusTag;
import com.ebook.common.mvvm.BaseActivity;
import com.ebook.common.util.ToastUtil;
import com.hwangjr.rxbus.RxBus;

@Route(path = KeyCode.Me.Setting_PATH)
public class SettingActivity extends BaseActivity {
    private Button mExitButtn;

    @Override
    public int onBindLayout() {
        return R.layout.activity_setting;
    }

    @Override
    public void initListener() {
        super.initListener();
        mExitButtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SPUtils.getInstance().clear();
                RetrofitManager.getInstance().TOKEN = "";
                ToastUtil.showToast("退出登录成功");
                RxBus.get().post(RxBusTag.SET_PROFIE_PICTURE_AND_NICKNAME, new Object());//更新UI
                finish();
            }
        });
    }

    @Override
    public void initView() {
        mExitButtn = (Button) findViewById(R.id.btn_exit);
    }

    @Override
    public void initData() {

    }
}
