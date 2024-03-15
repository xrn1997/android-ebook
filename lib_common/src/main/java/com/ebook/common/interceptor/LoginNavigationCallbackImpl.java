package com.ebook.common.interceptor;

import android.os.Bundle;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.LogUtils;
import com.ebook.common.event.KeyCode;
import com.therouter.TheRouter;
import com.therouter.router.Navigator;
import com.therouter.router.interceptor.NavigationCallback;


public class LoginNavigationCallbackImpl extends NavigationCallback {
    @Override //找到了
    public void onFound(@NonNull Navigator postcard) {

    }

    @Override //找不到了
    public void onLost(@NonNull Navigator postcard) {

    }

    @Override    //跳转成功了
    public void onArrival(@NonNull Navigator postcard) {

    }

    public void onInterrupt(Navigator postcard) {
        String path = postcard.getUrl();
        LogUtils.v(path);
        Bundle bundle = postcard.getExtras();
        // 被登录拦截了下来了
        // 需要跳转到登录页面，把参数跟被登录拦截下来的路径传递给登录页面，登录成功后再进行跳转被拦截的页面
        TheRouter.build(KeyCode.Login.Login_PATH)
                .with(bundle)
                .withString(KeyCode.Login.PATH, path)
                .navigation();
    }
}
