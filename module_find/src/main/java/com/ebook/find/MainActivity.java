package com.ebook.find;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.view.KeyEvent;

import com.ebook.common.mvvm.BaseActivity;
import com.ebook.common.util.ToastUtil;
import com.ebook.find.R;
import com.ebook.find.fragment.MainFindFragment;

public class MainActivity extends BaseActivity {


    @Override
    public int onBindLayout() {
        return R.layout.activity_main;
    }

    @Override
    public void initView() {
        getSupportFragmentManager().beginTransaction().replace(R.id.frame_content, MainFindFragment.newInstance()).commit();
    }

    @Override
    public void initData() {

    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            exit();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean enableToolbar() {
        return false;
    }

    private long exitTime = 0;

    public void exit() {
        if ((System.currentTimeMillis() - exitTime) > 2000) {
            ToastUtil.showToast("再按一次退出程序");
            exitTime = System.currentTimeMillis();
        } else {
            finish();
            System.exit(0);
        }
    }
}
