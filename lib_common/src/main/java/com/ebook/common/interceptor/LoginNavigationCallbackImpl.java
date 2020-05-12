package com.ebook.common.interceptor;

import android.os.Bundle;

import com.alibaba.android.arouter.facade.Postcard;
import com.alibaba.android.arouter.facade.callback.NavigationCallback;
import com.alibaba.android.arouter.launcher.ARouter;
import com.blankj.utilcode.util.LogUtils;
import com.ebook.common.event.KeyCode;


public class LoginNavigationCallbackImpl implements NavigationCallback {
    @Override //找到了
    public void onFound(Postcard postcard) {

    }

    @Override //找不到了
    public void onLost(Postcard postcard) {

    }

    @Override    //跳转成功了
    public void onArrival(Postcard postcard) {

    }

    @Override
    public void onInterrupt(Postcard postcard) {
        String path = postcard.getPath();
        LogUtils.v(path);
        Bundle bundle = postcard.getExtras();
        // 被登录拦截了下来了
        // 需要跳转到登录页面，把参数跟被登录拦截下来的路径传递给登录页面，登录成功后再进行跳转被拦截的页面
        ARouter.getInstance().build(KeyCode.Login.Login_PATH)
                .with(bundle)
                .withString(KeyCode.Login.PATH, path)
                .navigation();
    }
}
