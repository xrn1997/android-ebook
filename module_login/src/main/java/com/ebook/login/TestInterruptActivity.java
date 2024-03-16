package com.ebook.login;

import android.widget.TextView;

import com.ebook.common.event.KeyCode;
import com.ebook.common.mvvm.BaseActivity;
import com.therouter.router.Autowired;
import com.therouter.router.Route;

@Route(path = KeyCode.Login.TEST_PATH, params = {"needLogin", "true"})
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
