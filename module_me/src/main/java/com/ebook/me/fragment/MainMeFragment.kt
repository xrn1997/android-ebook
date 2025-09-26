package com.ebook.me.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.blankj.utilcode.util.SPUtils
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.ebook.common.event.KeyCode
import com.ebook.common.event.RxBusTag
import com.ebook.common.view.profilePhoto.CircleImageView
import com.ebook.me.R
import com.ebook.me.databinding.FragmentMeMainBinding
import com.hwangjr.rxbus.RxBus
import com.hwangjr.rxbus.annotation.Subscribe
import com.hwangjr.rxbus.annotation.Tag
import com.hwangjr.rxbus.thread.EventThread
import com.therouter.TheRouter.build
import com.xrn1997.common.mvvm.view.BaseFragment
import com.xrn1997.common.util.getThemeColor

class MainMeFragment : BaseFragment<FragmentMeMainBinding>() {
    private lateinit var mButton: Button
    private lateinit var mCircleImageView: CircleImageView
    private lateinit var mTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RxBus.get().register(this)
    }

    override fun enableToolbar(): Boolean {
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        RxBus.get().unregister(this)
    }

    override fun initData() {
        updateView(Any())
    }

    override var toolBarTitle: String
        get() = "我的"
        set(toolBarTitle) {
            super.toolBarTitle = toolBarTitle
        }

    override fun initView() {
        mButton = binding.btnLogin
        mCircleImageView = binding.viewUserImage
        mTextView = binding.viewUserName
        binding.viewMyComment.setOnClickSettingBarViewListener {
            build(KeyCode.Me.COMMENT_PATH).navigation(activity)
        }
        binding.viewMyInform.setOnClickSettingBarViewListener {
            build(KeyCode.Me.MODIFY_PATH).navigation(activity)
        }
        mButton.setOnClickListener {
            build(KeyCode.Login.LOGIN_PATH).navigation()
        }
        binding.viewSetting.setOnClickSettingBarViewListener {
            build(KeyCode.Me.SETTING_PATH).navigation(activity)
        }
        context?.let {
            mToolbarView?.setBackgroundColor(it.getThemeColor(com.google.android.material.R.attr.colorSurface))
            mToolbarView?.setToolbarTitleColor(it.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
        }
    }

    override fun onBindViewBinding(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        attachToParent: Boolean
    ): FragmentMeMainBinding {
        return FragmentMeMainBinding.inflate(inflater, parent, attachToParent)
    }

    @Subscribe(thread = EventThread.MAIN_THREAD, tags = [Tag(RxBusTag.MODIFY_PROFILE_PICTURE)])
    fun setProfilePicture(path: String) {
        Glide.with(mActivity)
            .load(path)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .fitCenter()
            .dontAnimate()
            .placeholder(ContextCompat.getDrawable(mActivity, R.drawable.image_default))
            .into(mCircleImageView)
    }

    @Subscribe(
        thread = EventThread.MAIN_THREAD,
        tags = [Tag(RxBusTag.SET_PROFILE_PICTURE_AND_NICKNAME)]
    )
    fun updateView(o: Any?) {
        if (!SPUtils.getInstance().getBoolean(KeyCode.Login.SP_IS_LOGIN)) {
            //未登录，显示按钮
            mTextView.visibility = View.GONE
            mButton.visibility = View.VISIBLE
            mCircleImageView.setImageDrawable(
                ContextCompat.getDrawable(
                    mActivity,
                    R.drawable.image_default
                )
            )
        } else {
            //已登录，显示昵称
            mTextView.visibility = View.VISIBLE
            mButton.visibility = View.GONE
            setProfilePicture(SPUtils.getInstance().getString(KeyCode.Login.SP_IMAGE))
            mTextView.text = SPUtils.getInstance().getString(KeyCode.Login.SP_NICKNAME)
        }
    }

    companion object {
        @JvmStatic
        fun newInstance(): MainMeFragment {
            return MainMeFragment()
        }
    }
}
