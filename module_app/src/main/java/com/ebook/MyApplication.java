package com.ebook;

import com.ebook.api.RetrofitManager;
import com.ebook.common.BaseApplication;
import com.ebook.db.GreenDaoManager;

import com.ebook.login.interceptor.LoginInterceptor;
import com.therouter.router.NavigatorKt;


public class MyApplication extends BaseApplication {

    @Override
    public void onCreate() {
        super.onCreate();
        RetrofitManager.init(this);
        GreenDaoManager.init(this);
        // 登录拦截
        NavigatorKt.addRouterReplaceInterceptor(new LoginInterceptor());
    }

}
