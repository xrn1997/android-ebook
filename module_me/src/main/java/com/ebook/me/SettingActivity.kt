package com.ebook.me

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import com.blankj.utilcode.util.SPUtils
import com.ebook.common.event.KeyCode
import com.ebook.common.event.RxBusTag
import com.ebook.me.databinding.ActivitySettingBinding
import com.hwangjr.rxbus.RxBus
import com.therouter.router.Route
import com.xrn1997.common.manager.RetrofitManager
import com.xrn1997.common.mvvm.view.BaseActivity
import com.xrn1997.common.util.ToastUtil

@Route(path = KeyCode.Me.SETTING_PATH, params = ["needLogin", "true"])
class SettingActivity : BaseActivity<ActivitySettingBinding>() {
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RxBus.get().register(this)
    }

    public override fun onDestroy() {
        super.onDestroy()
        RxBus.get().unregister(this)
    }

    override fun initView() {
        val mExitButton = binding.btnExit
        mExitButton.setOnClickListener {
            SPUtils.getInstance().clear()
            RetrofitManager.TOKEN = ""
            ToastUtil.showShort(this, "退出登录成功")
            RxBus.get().post(RxBusTag.SET_PROFILE_PICTURE_AND_NICKNAME, Any()) //更新UI
            finish()
        }
    }

    override fun initData() {
    }

    override fun onBindViewBinding(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        attachToParent: Boolean
    ): ActivitySettingBinding {
        return ActivitySettingBinding.inflate(inflater, parent, attachToParent)
    }
}
