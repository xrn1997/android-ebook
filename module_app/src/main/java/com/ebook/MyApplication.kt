package com.ebook

import com.ebook.common.BookApplication
import com.ebook.common.interceptor.LoginInterceptor
import com.ebook.db.ObjectBoxManager.init
import com.therouter.router.addRouterReplaceInterceptor
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MyApplication : BookApplication() {
    override fun onCreate() {
        super.onCreate()
        init(this)
        // 登录拦截
        addRouterReplaceInterceptor(LoginInterceptor())
    }
}
