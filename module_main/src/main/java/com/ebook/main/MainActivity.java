package com.ebook.main;


import android.util.Log;
import android.view.MenuItem;

import com.alibaba.android.arouter.facade.annotation.Autowired;
import com.ebook.common.mvvm.BaseActivity;
import com.ebook.common.provider.IBookProvider;
import com.ebook.common.provider.IFindProvider;
import com.ebook.common.provider.IMeProvider;
import com.ebook.common.provider.INewsProvider;
import com.ebook.main.entity.MainChannel;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;


public class MainActivity extends BaseActivity {
    @Autowired(name = "/book/main")
    IBookProvider mBookProvider;
//    @Autowired(name = "/news/main")
//    INewsProvider mNewsProvider;

    @Autowired(name = "/find/main")
    IFindProvider mFindProvider;

    @Autowired(name = "/me/main")
    IMeProvider mMeProvider;

    private Fragment mBookFragment;
    //private Fragment mNewsFragment;
    private Fragment mFindFragment;
    private Fragment mMeFragment;
    private Fragment mCurrFragment;

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
        navigation.setOnNavigationItemSelectedListener(new BottomNavigationView.OnNavigationItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem menuItem) {
                int i = menuItem.getItemId();
                if (i == R.id.navigation_trip) {
                    switchContent(mCurrFragment, mBookFragment, MainChannel.BOOKSHELF.name);
                    mCurrFragment = mBookFragment;

                    return true;
                } else if (i == R.id.navigation_discover) {
                    switchContent(mCurrFragment, mFindFragment, MainChannel.FiINDBOOK.name);
                    mCurrFragment = mFindFragment;

                    return true;
                } else if (i == R.id.navigation_me) {
                    switchContent(mCurrFragment, mMeFragment, MainChannel.ME.name);
                    mCurrFragment = mMeFragment;

                    return true;
                }
                return false;
            }
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
}
