package com.ebook.common.interceptor

import android.util.Log
import com.ebook.common.event.KeyCode
import com.ebook.common.util.SPUtil
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
        Log.e(TAG, "replace: $routeItem")
        val isLogin = SPUtil.get(KeyCode.Login.SP_IS_LOGIN, false)
        if (isLogin) {
            Log.d(TAG, "已登录，不拦截")
            return routeItem
        }

        val needLogin = routeItem.getExtras().getString("needLogin")?.toBoolean() ?: false
        if (!needLogin) {
            Log.d(TAG, "目标页不需要登录，继续跳转")
            return routeItem
        }

        Log.d(TAG, "未登录，需要拦截到登录页")
        return matchRouteMap(KeyCode.Login.LOGIN_PATH)
    }
}
