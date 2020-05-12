package com.ebook.login.interceptor;

import android.content.Context;

import com.alibaba.android.arouter.facade.Postcard;
import com.alibaba.android.arouter.facade.annotation.Interceptor;
import com.alibaba.android.arouter.facade.callback.InterceptorCallback;
import com.alibaba.android.arouter.facade.template.IInterceptor;
import com.blankj.utilcode.util.LogUtils;
import com.blankj.utilcode.util.SPUtils;
import com.ebook.common.event.KeyCode;


// 在跳转过程中处理登陆事件，这样就不需要在目标页重复做登陆检查
// 拦截器会在跳转之间执行，多个拦截器会按优先级顺序依次执行
@Interceptor(name = "login", priority = 6)
public class LoginInterceptorImpl implements IInterceptor {
    @Override
    public void process(Postcard postcard, InterceptorCallback callback) {
        String path = postcard.getPath();
        LogUtils.e(path);
        boolean isLogin = SPUtils.getInstance().getBoolean(KeyCode.Login.SP_IS_LOGIN, false);
        LogUtils.e("isLogin:" + isLogin);
        if (isLogin) { // 如果已经登录不拦截
            callback.onContinue(postcard);
        } else {  // 如果没有登录
            switch (path) {
                // 不需要登录的直接进入这个页面
                case KeyCode.Login.Login_PATH:
                case KeyCode.Login.Register_PATH:
                case KeyCode.Login.Modify_PATH:
                    callback.onContinue(postcard);
                    break;
                // 需要登录的直接拦截下来
                default:
                    callback.onInterrupt(null);
                    break;
            }
        }

    }

    @Override
    public void init(Context context) {//此方法只会走一次
        LogUtils.e("路由登录拦截器初始化成功");
    }

}
