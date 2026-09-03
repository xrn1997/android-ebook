package debug

import com.ebook.common.BookApplication
import com.ebook.common.event.KeyCode
import com.ebook.common.util.SPUtil
import com.therouter.router.RouteItem
import com.therouter.router.addPathReplaceInterceptor
import com.therouter.router.addRouterReplaceInterceptor
import com.therouter.router.interceptor.PathReplaceInterceptor
import com.therouter.router.interceptor.RouterReplaceInterceptor
import com.therouter.router.matchRouteMap
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TestApplication : BookApplication() {
    override fun onCreate() {
        super.onCreate()
        // 登录态拦截：needLogin 页面未登录时统一跳登录。
        // 与正式 App 的 LoginInterceptor 逻辑一致，但独立运行时 LOGIN_PATH（module_login）
        // 未集成、路由不存在，若用 LoginInterceptor 会 matchRouteMap(LOGIN_PATH) 返回 null
        // 导致导航中断（点了没反应），故这里直接拦截到本模块的模拟登录页 TEST_LOGIN_PATH。
        addRouterReplaceInterceptor(object : RouterReplaceInterceptor() {
            override val priority = 6
            override fun replace(routeItem: RouteItem?): RouteItem? {
                if (routeItem == null) return null
                if (SPUtil.get(KeyCode.Login.SP_IS_LOGIN, false)) return routeItem
                val needLogin = routeItem.getExtras().getString("needLogin")?.toBoolean() ?: false
                if (!needLogin) return routeItem
                return matchRouteMap(KeyCode.Me.TEST_LOGIN_PATH)
            }
        })
        //模块独立开发测试用，替换掉登录界面。
        addPathReplaceInterceptor(object : PathReplaceInterceptor() {
            override fun replace(path: String?): String? {
                if (path == KeyCode.Login.LOGIN_PATH) {
                    return KeyCode.Me.TEST_LOGIN_PATH
                }
                if (path == KeyCode.Login.MODIFY_PATH) {
                    return KeyCode.Me.TEST_LOGIN_PATH
                }
                return path
            }
        })
    }
}
