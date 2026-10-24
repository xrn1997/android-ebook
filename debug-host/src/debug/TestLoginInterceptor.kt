package debug

import com.ebook.common.event.KeyCode
import com.ebook.common.util.SPUtil
import com.therouter.router.RouteItem
import com.therouter.router.interceptor.RouterReplaceInterceptor
import com.therouter.router.matchRouteMap

/**
 * 独立运行的登录拦截：needLogin 页面未登录时统一跳本模块的模拟登录页。
 *
 * 与正式 `LoginInterceptor` 同一判据（登录态 + `needLogin` 参数），区别只在落点：
 * 独立态 module_login 不在依赖图里，`LOGIN_PATH` 没有路由，必须重定向到模块内的测试登录页。
 * 原先这段在 module_me 的 `TestApplication` 里内联重写，现收敛成一份可配置实现。
 */
class TestLoginInterceptor(private val testLoginPath: String) : RouterReplaceInterceptor() {

    override val priority = 6

    override fun replace(routeItem: RouteItem?): RouteItem? {
        if (routeItem == null) return null
        if (SPUtil.get(KeyCode.Login.SP_IS_LOGIN, false)) return routeItem
        val needLogin = routeItem.getExtras().getString("needLogin")?.toBoolean() ?: false
        if (!needLogin) return routeItem
        return matchRouteMap(testLoginPath)
    }
}
