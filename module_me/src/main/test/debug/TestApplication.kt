package debug

import com.ebook.common.event.KeyCode
import dagger.hilt.android.HiltAndroidApp

/**
 * module_me 独立运行时的 Application。
 *
 * 登录/改密路由换成本模块的模拟登录页；needLogin 页面由共享的 [TestLoginInterceptor]
 * 按同一判据拦截（不再就地重写一份）。
 */
@HiltAndroidApp
class TestApplication : BaseTestApplication() {
    override val pathReplacements: Map<String, String> = mapOf(
        KeyCode.Login.LOGIN_PATH to KeyCode.Me.TEST_LOGIN_PATH,
        KeyCode.Login.MODIFY_PATH to KeyCode.Me.TEST_LOGIN_PATH,
    )

    override fun routerInterceptors() = listOf(
        TestLoginInterceptor(KeyCode.Me.TEST_LOGIN_PATH),
    )
}
