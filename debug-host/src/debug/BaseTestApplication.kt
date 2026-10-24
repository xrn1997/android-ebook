package debug

import com.ebook.common.BookApplication
import com.therouter.router.addPathReplaceInterceptor
import com.therouter.router.addRouterReplaceInterceptor
import com.therouter.router.interceptor.PathReplaceInterceptor
import com.therouter.router.interceptor.RouterReplaceInterceptor

/**
 * 独立运行（`isModule=true`）宿主的共享 Application。
 *
 * 五个模块原本各抄一份 `TestApplication`，路径替换逐模块重写，登录拦截还有一份把正式
 * `LoginInterceptor` 的判据就地重抄。这里只保留公共初始化与两个扩展点：各模块的 `TestApplication` 仍负责声明
 * `@HiltAndroidApp`（每个独立 APK 的唯一根），并用 [pathReplacements] / [routerInterceptors]
 * 交出自己那份配置。共享源码目录由 `AndroidComponentConventionPlugin` 只在独立态并入。
 */
abstract class BaseTestApplication : BookApplication() {

    /** 跨模块路由替换表：键为原路径，值为本模块内可承接它的路径 */
    protected open val pathReplacements: Map<String, String> = emptyMap()

    /** 需要额外安装的路由拦截器（如未登录跳转、真实登录拦截） */
    protected open fun routerInterceptors(): List<RouterReplaceInterceptor> = emptyList()

    override fun onCreate() {
        super.onCreate()
        if (pathReplacements.isNotEmpty()) {
            addPathReplaceInterceptor(object : PathReplaceInterceptor() {
                override fun replace(path: String?): String? =
                    path?.let { pathReplacements[it] } ?: path
            })
        }
        routerInterceptors().forEach(::addRouterReplaceInterceptor)
    }
}
