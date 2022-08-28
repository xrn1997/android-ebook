package com.ebook.main;


import android.view.KeyEvent;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.alibaba.android.arouter.facade.annotation.Autowired;
import com.ebook.common.mvvm.BaseActivity;
import com.ebook.common.provider.IBookProvider;
import com.ebook.common.provider.IFindProvider;
import com.ebook.common.provider.IMeProvider;
import com.ebook.common.util.ToastUtil;
import com.ebook.main.entity.MainChannel;
import com.google.android.material.bottomnavigation.BottomNavigationView;


public class MainActivity extends BaseActivity {
    @Autowired(name = "/book/main")
    IBookProvider mBookProvider;

    @Autowired(name = "/find/main")
    IFindProvider mFindProvider;

    @Autowired(name = "/me/main")
    IMeProvider mMeProvider;

    private Fragment mBookFragment;
    private Fragment mFindFragment;
    private Fragment mMeFragment;
    private Fragment mCurrFragment;
    private long exitTime = 0;

    @Override
    public int onBindLayout() {
        return R.layout.activity_main;
    }

    @Override
    public boolean enableToolbar() {
        return false;
    }

    @Override
    public void initView() {

        BottomNavigationView navigation = findViewById(R.id.navigation_main);
        navigation.setOnNavigationItemSelectedListener(menuItem -> {
            int i = menuItem.getItemId();
            if (i == R.id.navigation_trip) {
                switchContent(mCurrFragment, mBookFragment, MainChannel.BOOKSHELF.name);
                mCurrFragment = mBookFragment;

                return true;
            } else if (i == R.id.navigation_discover) {
                switchContent(mCurrFragment, mFindFragment, MainChannel.BOOKSTORE.name);
                mCurrFragment = mFindFragment;

                return true;
            } else if (i == R.id.navigation_me) {
                switchContent(mCurrFragment, mMeFragment, MainChannel.ME.name);
                mCurrFragment = mMeFragment;

                return true;
            }
            return false;
        });
        if (mBookProvider != null) {
            mBookFragment = mBookProvider.getMainBookFragment();
        }
        if (mFindProvider != null) {
            mFindFragment = mFindProvider.getMainFindFragment();
        }
        if (mMeProvider != null) {
            mMeFragment = mMeProvider.getMainMeFragment();
        }
        mCurrFragment = mBookFragment;
        if (mBookFragment != null) {
            getSupportFragmentManager().beginTransaction().replace(R.id.frame_content, mBookFragment, MainChannel.BOOKSHELF.name).commit();
        }
    }

    @Override
    public void initData() {

    }

    public void switchContent(Fragment from, Fragment to, String tag) {
        if (from == null || to == null) {
            return;
        }
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        if (!to.isAdded()) {
            transaction.hide(from).add(R.id.frame_content, to, tag).commit();
        } else {
            transaction.hide(from).show(to).commit();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            exit();
            return true;
        }
        return super.onKeyDown(keyCode, event);
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
}
