package debug

import com.ebook.common.event.KeyCode
import dagger.hilt.android.HiltAndroidApp

/**
 * module_book 独立运行时的 Application：把登录路由换成本模块的模拟登录页。
 *
 * 公共初始化、mock 绑定与宿主基座都在 `debug-host`。
 */
@HiltAndroidApp
class TestApplication : BaseTestApplication() {
    override val pathReplacements: Map<String, String> = mapOf(
        KeyCode.Login.LOGIN_PATH to KeyCode.Book.TEST_LOGIN_PATH,
    )
}
