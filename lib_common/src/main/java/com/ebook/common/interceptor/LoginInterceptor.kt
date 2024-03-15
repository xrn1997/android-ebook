package com.ebook.common.interceptor

import com.ebook.common.event.KeyCode
import com.therouter.router.RouteItem
import com.therouter.router.interceptor.RouterReplaceInterceptor
import com.therouter.router.matchRouteMap


/**
 * @author      : xrn1997
 * @email       : rongnan.xu@iraytek.com
 * @date        : on 2024-03-15 13:57.
 * @description : 登录拦截器
 */
class LoginInterceptor : RouterReplaceInterceptor() {
    override fun replace(routeItem: RouteItem?): RouteItem? {
        if (routeItem!!.getExtras()["needLogin"].toString().toBoolean()) {
            return matchRouteMap(KeyCode.Login.Login_PATH)
        }
        return routeItem
    }
}