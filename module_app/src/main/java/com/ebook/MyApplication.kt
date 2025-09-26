package com.ebook

import com.ebook.common.interceptor.LoginInterceptor
import com.ebook.db.ObjectBoxManager.init
import com.therouter.router.addRouterReplaceInterceptor
import com.xrn1997.common.BaseApplication
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MyApplication : BaseApplication() {
    override fun onCreate() {
        super.onCreate()
        init(this)
        // 登录拦截
        addRouterReplaceInterceptor(LoginInterceptor())
    }
}
