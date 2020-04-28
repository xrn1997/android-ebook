package com.ebook.find.fragment;

import android.view.View;

import com.ebook.common.mvvm.BaseFragment;
import com.ebook.find.R;


public class MainFindFragment extends BaseFragment {
    public static final String TAG = MainFindFragment.class.getSimpleName();
    public static MainFindFragment newInstance() {
        return new MainFindFragment();
    }
    @Override
    public int onBindLayout() {
        return R.layout.fragment_find_main;
    }

    @Override
    public void initView(View view) {

    }

    @Override
    public void initData() {

    }

    @Override
    public String getToolbarTitle() {
        return null;
    }
}
