package com.ebook.common.interceptor

import android.util.Log
import com.blankj.utilcode.util.SPUtils
import com.ebook.common.event.KeyCode
import com.therouter.router.RouteItem
import com.therouter.router.interceptor.RouterReplaceInterceptor
import com.therouter.router.matchRouteMap

/**
 * 在跳转过程中处理登陆事件，这样就不需要在目标页重复做登陆检查
 * 拦截器会在跳转之间执行，多个拦截器会按优先级顺序依次执行
 */
class LoginInterceptor : RouterReplaceInterceptor() {
    override val priority = 6

    companion object {
        private val TAG = LoginInterceptor::class.java.simpleName
    }

    override fun replace(routeItem: RouteItem?): RouteItem? {
        if (routeItem == null) {
            Log.e(TAG, "目标地址不存在")
            return null
        }
        val isLogin = SPUtils.getInstance().getBoolean(KeyCode.Login.SP_IS_LOGIN, false)
        if (isLogin) {
            // 如果已经登录不拦截
            return routeItem
        }
        return if (routeItem.getExtras().getString("needLogin").toBoolean()) {
            val loginItem = matchRouteMap(KeyCode.Login.LOGIN_PATH)
            loginItem?.getExtras()?.putString(KeyCode.Login.PATH, routeItem.path)
            loginItem
        } else routeItem
        // 不需要拦截
    }
}
