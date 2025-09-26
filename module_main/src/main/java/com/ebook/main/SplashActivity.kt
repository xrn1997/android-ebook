package com.ebook.main

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.blankj.utilcode.util.SPUtils
import com.ebook.common.event.KeyCode
import com.ebook.common.provider.ILoginProvider
import com.ebook.main.databinding.ActivitySplashBinding
import com.therouter.TheRouter
import com.xrn1997.common.mvvm.view.BaseActivity

/**
 * 这个页面理论上已经没用了，当前唯一的作用就是提前自动登录(未来会迁移到一个后台service)
 * 以及显示一张图片，哈哈哈。
 */
@SuppressLint("CustomSplashScreen")
class SplashActivity : BaseActivity<ActivitySplashBinding>() {
    private val mHandler = Handler(Looper.getMainLooper())
    private val mRunnableToMain = Runnable { this.startMainActivity() }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        mHandler.postDelayed(mRunnableToMain, 3000)
        binding.idBtnSkip.setOnClickListener {
            mHandler.removeCallbacks(mRunnableToMain)
            startMainActivity()
        }
    }

    override fun enableToolbar(): Boolean {
        return false
    }

    override fun enableFitsSystemWindows(): Boolean {
        return false
    }

    override fun initData() {
        val username = SPUtils.getInstance().getString(KeyCode.Login.SP_USERNAME)
        val password = SPUtils.getInstance().getString(KeyCode.Login.SP_PASSWORD)
        //  Log.d(TAG, "SplashActivity initData: username: " + username + ",password: " + password);
        if ((!TextUtils.isEmpty(username)) && (!TextUtils.isEmpty(password))) {
            TheRouter.get(ILoginProvider::class.java)?.login(username, password) //启动应用后自动登录
        }
    }

    override fun onBindViewBinding(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        attachToParent: Boolean
    ): ActivitySplashBinding {
        return ActivitySplashBinding.inflate(inflater, parent, attachToParent)
    }

    private fun startMainActivity() {
        //打开主界面
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        mHandler.removeCallbacks(mRunnableToMain)
    }
}