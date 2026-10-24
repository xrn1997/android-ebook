package debug

import com.ebook.common.interceptor.LoginInterceptor
import dagger.hilt.android.HiltAndroidApp

/**
 * module_login 独立运行时的 Application。
 *
 * 登录页与拦截测试页都在本模块，直接复用正式 [LoginInterceptor]，无需路径替换。
 */
@HiltAndroidApp
class TestApplication : BaseTestApplication() {
    override fun routerInterceptors() = listOf(LoginInterceptor())
}
