package com.ebook.login;

import android.widget.TextView;

import com.alibaba.android.arouter.facade.annotation.Autowired;
import com.alibaba.android.arouter.facade.annotation.Route;
import com.ebook.common.event.KeyCode;
import com.ebook.common.mvvm.BaseActivity;

@Route(path = KeyCode.Login.Test_PATH)
public class TestInterruptActivity extends BaseActivity {

    @Autowired
    public String msg;
    private TextView tvMsg;

    @Override
    public int onBindLayout() {
        return R.layout.activity_test;
    }

    @Override
    public void initView() {
        tvMsg = findViewById(R.id.tv_msg);
    }

    @Override
    public void initData() {
        tvMsg.setText(msg);
    }
}
