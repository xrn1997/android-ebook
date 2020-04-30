package com.ebook.me.fragment;

import android.content.Intent;
import android.view.View;
import android.widget.RelativeLayout;

import com.alibaba.android.arouter.launcher.ARouter;
import com.ebook.common.event.KeyCode;
import com.ebook.common.interceptor.LoginNavigationCallbackImpl;
import com.ebook.common.mvvm.BaseFragment;
import com.ebook.common.view.SettingBarView;
import com.ebook.me.NewsDetailAddActivity;
import com.ebook.me.NewsTypeListActivity;
import com.ebook.me.R;


public class MainMeFragment extends BaseFragment {

    private SettingBarView mSetNewsType;
    private SettingBarView mSetNewsDetail;
    private SettingBarView mSetting;
    private RelativeLayout mUserLayout;

    public static MainMeFragment newInstance() {
        return new MainMeFragment();
    }


    @Override
    public int onBindLayout() {
        return R.layout.fragment_me_main;
    }

    @Override
    public void initView(View view) {
        mSetNewsType = view.findViewById(R.id.view_setting_news_type);
        mSetNewsDetail = view.findViewById(R.id.view_setting_news_detail);
        mUserLayout = view.findViewById(R.id.view_user_layout);
        mSetting = view.findViewById(R.id.view_setting);

    }

    @Override
    public void initListener() {
        mSetNewsType.setOnClickSettingBarViewListener(new SettingBarView.OnClickSettingBarViewListener() {
            @Override
            public void onClick() {
                // Toast.makeText(mActivity, "添加新闻类型", Toast.LENGTH_SHORT).show();
                startActivity(new Intent(mActivity, NewsTypeListActivity.class));
            }
        });
        mSetNewsDetail.setOnClickSettingBarViewListener(new SettingBarView.OnClickSettingBarViewListener() {
            @Override
            public void onClick() {
                //Toast.makeText(mActivity, "添加新闻", Toast.LENGTH_SHORT).show();
                startActivity(new Intent(mActivity, NewsDetailAddActivity.class));
            }
        });
        mUserLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ARouter.getInstance().build(KeyCode.Login.Test_PATH)
                        .withString("msg", "ARouter传递过来的需要登录的参数msg")
                        .navigation(getActivity(), new LoginNavigationCallbackImpl());
            }
        });
        mSetting.setOnClickSettingBarViewListener(new SettingBarView.OnClickSettingBarViewListener() {
            @Override
            public void onClick() {
                //Toast.makeText(mActivity, "设置", Toast.LENGTH_SHORT).show();
                ARouter.getInstance().build(KeyCode.Me.Setting_PATH)
                        .navigation(getActivity(), new LoginNavigationCallbackImpl());
            }
        });
    }

    @Override
    public void initData() {

    }

    @Override
    public String getToolbarTitle() {
        return "我的";
    }


}
