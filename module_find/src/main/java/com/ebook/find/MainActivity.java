package com.ebook.find;

import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ebook.common.util.ToastUtil;
import com.ebook.find.databinding.ActivityMainBinding;
import com.ebook.find.fragment.MainFindFragment;
import com.xrn1997.common.mvvm.view.BaseActivity;

public class MainActivity extends BaseActivity<ActivityMainBinding> {


    private long exitTime = 0;

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

    public void exit() {
        if ((System.currentTimeMillis() - exitTime) > 2000) {
            ToastUtil.showToast("再按一次退出程序");
            exitTime = System.currentTimeMillis();
        } else {
            finish();
            System.exit(0);
        }
    }

    @NonNull
    @Override
    public ActivityMainBinding onBindViewBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, boolean attachToParent) {
        return ActivityMainBinding.inflate(inflater, container, attachToParent);
    }
}
