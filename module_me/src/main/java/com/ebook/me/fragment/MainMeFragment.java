package com.ebook.me.fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.blankj.utilcode.util.SPUtils;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.ebook.api.config.API;
import com.ebook.common.event.KeyCode;
import com.ebook.common.event.RxBusTag;
import com.ebook.common.view.SettingBarView;
import com.ebook.common.view.profilePhoto.CircleImageView;
import com.ebook.me.R;
import com.ebook.me.databinding.FragmentMeMainBinding;
import com.hwangjr.rxbus.annotation.Subscribe;
import com.hwangjr.rxbus.annotation.Tag;
import com.hwangjr.rxbus.thread.EventThread;
import com.therouter.TheRouter;
import com.xrn1997.common.mvvm.view.BaseFragment;


public class MainMeFragment extends BaseFragment<FragmentMeMainBinding> {

    private Button mButton;
    private CircleImageView mCircleImageView;
    private TextView mTextView;

    public static MainMeFragment newInstance() {
        return new MainMeFragment();
    }

    @Override
    public void initData() {
        updateView(new Object());
    }

    @NonNull
    @Override
    public String getToolBarTitle() {
        return "我的";
    }

    @Subscribe(thread = EventThread.MAIN_THREAD,
            tags = {
                    @Tag(RxBusTag.MODIFY_PROFIE_PICTURE)
            }
    )
    public void setProfilePicture(String path) {
        Glide.with(mActivity)
                .load(API.URL_HOST_USER + "user/image/" + path)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .fitCenter()
                .dontAnimate()
                .placeholder(ContextCompat.getDrawable(mActivity, R.drawable.image_default))
                .into(mCircleImageView);
    }

    @Subscribe(thread = EventThread.MAIN_THREAD,
            tags = {
                    @Tag(RxBusTag.SET_PROFIE_PICTURE_AND_NICKNAME)
            }
    )
    public void updateView(Object o) {
        if (!SPUtils.getInstance().getBoolean(KeyCode.Login.SP_IS_LOGIN)) {
            //未登录，显示按钮
            mTextView.setVisibility(View.GONE);
            mButton.setVisibility(View.VISIBLE);
            mCircleImageView.setImageDrawable(ContextCompat.getDrawable(mActivity, R.drawable.image_default));
        } else {
            //已登录，显示昵称
            mTextView.setVisibility(View.VISIBLE);
            mButton.setVisibility(View.GONE);
            setProfilePicture(SPUtils.getInstance().getString(KeyCode.Login.SP_IMAGE));
            mTextView.setText(SPUtils.getInstance().getString(KeyCode.Login.SP_NICKNAME));
        }
    }

    @Override
    public void initView() {
        SettingBarView mSetComment = getBinding().viewMyComment;
        SettingBarView mSetInform = getBinding().viewMyInform;
        mButton = getBinding().btnLogin;
        SettingBarView mSetting = getBinding().viewSetting;
        mCircleImageView = getBinding().viewUserImage;
        mTextView = getBinding().viewUserName;
        mSetComment.setOnClickSettingBarViewListener(() -> TheRouter.build(KeyCode.Me.COMMENT_PATH)
                .navigation(getActivity()));
        mSetInform.setOnClickSettingBarViewListener(() -> TheRouter.build(KeyCode.Me.MODIFY_PATH)
                .navigation(getActivity()));
        mButton.setOnClickListener(v -> TheRouter.build(KeyCode.Login.LOGIN_PATH)
                .navigation());
        mSetting.setOnClickSettingBarViewListener(() -> TheRouter.build(KeyCode.Me.SETTING_PATH)
                .navigation(getActivity()));
    }

    @NonNull
    @Override
    public FragmentMeMainBinding onBindViewBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, boolean attachToParent) {
        return FragmentMeMainBinding.inflate(inflater, container, attachToParent);
    }
}
