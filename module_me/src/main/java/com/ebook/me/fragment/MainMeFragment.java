package com.ebook.me.fragment;

import android.content.Intent;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.Button;

import com.alibaba.android.arouter.launcher.ARouter;
import com.bumptech.glide.Glide;
import com.ebook.common.event.KeyCode;
import com.ebook.common.event.RxBusTag;
import com.ebook.common.interceptor.LoginNavigationCallbackImpl;
import com.ebook.common.mvvm.BaseFragment;
import com.ebook.common.view.SettingBarView;
import com.ebook.me.CommentActivity;
import com.ebook.me.EditInfromActivity;
import com.ebook.me.NewsDetailAddActivity;
import com.ebook.me.NewsTypeListActivity;
import com.ebook.me.R;
import com.ebook.common.view.profilePhoto.CircleImageView;
import com.hwangjr.rxbus.annotation.Subscribe;
import com.hwangjr.rxbus.annotation.Tag;
import com.hwangjr.rxbus.thread.EventThread;


public class MainMeFragment extends BaseFragment {

    private SettingBarView mSetNewsType;
    private SettingBarView mSetNewsDetail;
    private SettingBarView mSetting;
    private Button mButton;
    private SettingBarView mSetComment;
    private SettingBarView mSetInform;
    private CircleImageView mCircleImageView;

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
        mSetComment = view.findViewById(R.id.view_my_comment);
        mSetInform = view.findViewById(R.id.view_my_inform);
        mButton = view.findViewById(R.id.btn_login);
        mSetting = view.findViewById(R.id.view_setting);
        mCircleImageView = view.findViewById(R.id.view_user_image);
    }

    @Override
    public void initListener() {
        mSetComment.setOnClickSettingBarViewListener(new SettingBarView.OnClickSettingBarViewListener() {
            @Override
            public void onClick() {
                startActivity(new Intent(mActivity, CommentActivity.class));
            }
        });
        mSetInform.setOnClickSettingBarViewListener(new SettingBarView.OnClickSettingBarViewListener() {
            @Override
            public void onClick() {
                startActivity(new Intent(mActivity, EditInfromActivity.class));
            }
        });
        mSetNewsType.setOnClickSettingBarViewListener(new SettingBarView.OnClickSettingBarViewListener() {
            @Override
            public void onClick() {
                startActivity(new Intent(mActivity, NewsTypeListActivity.class));
            }
        });
        mSetNewsDetail.setOnClickSettingBarViewListener(new SettingBarView.OnClickSettingBarViewListener() {
            @Override
            public void onClick() {
                startActivity(new Intent(mActivity, NewsDetailAddActivity.class));
            }
        });
        mButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
//                ARouter.getInstance().build(KeyCode.Login.Test_PATH)
//                        .withString("msg", "ARouter传递过来的需要登录的参数msg")
//                        .navigation(getActivity(), new LoginNavigationCallbackImpl());
                ARouter.getInstance().build(KeyCode.Login.Login_PATH)
                        .navigation();
            }
        });
        mSetting.setOnClickSettingBarViewListener(new SettingBarView.OnClickSettingBarViewListener() {
            @Override
            public void onClick() {
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

    @Subscribe(thread = EventThread.MAIN_THREAD,
            tags = {
            @Tag(RxBusTag.MODIFY_PROFIE_PICTURE)
            }
    )
    public void setProfiePicture(Bitmap bitmap) {
                Glide.with(mActivity)
                        .load(bitmap)
                        .into(mCircleImageView);
    }
}
