//package com.ebook.login.interceptor;
//
//import android.content.Context;
//
//import androidx.annotation.Nullable;
//
//import com.blankj.utilcode.util.LogUtils;
//import com.blankj.utilcode.util.SPUtils;
//import com.ebook.common.event.KeyCode;
//import com.therouter.router.Navigator;
//import com.therouter.router.RouteItem;
//import com.therouter.router.interceptor.RouterReplaceInterceptor;
//
//
//// 在跳转过程中处理登陆事件，这样就不需要在目标页重复做登陆检查
//// 拦截器会在跳转之间执行，多个拦截器会按优先级顺序依次执行
//public class LoginInterceptorImpl implements RouterReplaceInterceptor {
//    @Override
//    public void process(Navigator postcard, InterceptorCallback callback) {
//        String path = postcard.getPath();
//        LogUtils.e(path);
//        boolean isLogin = SPUtils.getInstance().getBoolean(KeyCode.Login.SP_IS_LOGIN, false);
//        LogUtils.e("isLogin:" + isLogin);
//        if (isLogin) { // 如果已经登录不拦截
//            callback.onContinue(postcard);
//        } else {  // 如果没有登录
//            switch (path) {
//                // 不需要登录的直接进入这个页面
//                case KeyCode.Login.Login_PATH:
//                case KeyCode.Login.Register_PATH:
//                case KeyCode.Login.Modify_PATH:
//                    callback.onContinue(postcard);
//                    break;
//                // 需要登录的直接拦截下来
//                default:
//                    callback.onInterrupt(null);
//                    break;
//            }
//        }
//
//    }
//    @Nullable
//    @Override
//    public RouteItem replace(@Nullable RouteItem routeItem) {
//        return null;
//    }
//}
