package com.ebook.main;

import androidx.annotation.NonNull;

import com.ebook.common.provider.IBookProvider;
import com.ebook.common.provider.INewsProvider;
import com.ebook.common.util.ToastUtil;
import com.ebook.main.entity.MainChannel;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import android.util.Log;
import android.view.MenuItem;

import com.alibaba.android.arouter.facade.annotation.Autowired;
import com.ebook.common.mvvm.BaseActivity;
import com.ebook.common.provider.IFindProvider;
import com.ebook.common.provider.IMeProvider;


public class MainActivity extends BaseActivity {
    @Autowired(name = "/bookshelf/main")
    IBookProvider mBookProvider;

//    @Autowired(name = "/news/main")
//    INewsProvider mNewsProvider;

    @Autowired(name = "/find/main")
    IFindProvider mFindProvider;

    @Autowired(name = "/me/main")
    IMeProvider mMeProvider;

//    private Fragment mNewsFragment;
    private Fragment mBookFragment;
    private Fragment mFindFragment;
    private Fragment mMeFragment;

    private Fragment mCurrFragment;

    @Override
    public int onBindLayout() {
        return R.layout.activity_main;
    }
    /*
     * 禁止显示Toolbar，默认为true
     * */
    @Override
    public boolean enableToolbar() {
        return false;
    }

    @Override
    public void initView() {
        //底部导航栏的功能实现
        BottomNavigationView navigation = findViewById(R.id.navigation);//获取底部导航栏ID
        navigation.setOnNavigationItemSelectedListener(new BottomNavigationView.OnNavigationItemSelectedListener() {
            //底部导航栏item监听
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem menuItem) {
                int i = menuItem.getItemId();
                if (i == R.id.navigation_trip) {
                    //ToastUtil.showToast("书架");
                    switchContent(mCurrFragment, mBookFragment, MainChannel.BOOKSHELF.name);
                    mCurrFragment = mBookFragment;

                    return true;
                } else if (i == R.id.navigation_discover) {
                   // ToastUtil.showToast("书库");
                    switchContent(mCurrFragment, mFindFragment, MainChannel.FiINDBOOK.name);
                    mCurrFragment = mFindFragment;

                    return true;
                } else if (i == R.id.navigation_me) {
                    //ToastUtil.showToast("我的");
                    switchContent(mCurrFragment, mMeFragment, MainChannel.ME.name);
                    mCurrFragment = mMeFragment;

                    return true;
                }
                return false;
            }
        });
        if (mBookProvider != null) {
            Log.d(TAG, "initView: 获得book页面");
            mBookFragment = mBookProvider.getMainBookFragment();
        }
        if (mFindProvider != null) {
            Log.d(TAG, "initView: 获得查询页面");
            mFindFragment = mFindProvider.getMainFindFragment();
        }
        if (mMeProvider != null) {
            Log.d(TAG, "initView: 获得个人页面");
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
        //Fragment页面跳转
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
