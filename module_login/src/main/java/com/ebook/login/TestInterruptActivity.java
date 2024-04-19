package com.ebook.login;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ebook.common.event.KeyCode;
import com.ebook.login.databinding.ActivityTestBinding;
import com.therouter.router.Autowired;
import com.therouter.router.Route;
import com.xrn1997.common.mvvm.view.BaseActivity;

@Route(path = KeyCode.Login.TEST_PATH, params = {"needLogin", "true"})
public class TestInterruptActivity extends BaseActivity<ActivityTestBinding> {

    @Autowired
    public String msg;
    private TextView tvMsg;

    @Override
    public void initView() {
        tvMsg = findViewById(R.id.tv_msg);
    }

    @Override
    public void initData() {
        tvMsg.setText(msg);
    }

    @NonNull
    @Override
    public ActivityTestBinding onBindViewBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, boolean attachToParent) {
        return ActivityTestBinding.inflate(inflater, container, attachToParent);
    }
}
