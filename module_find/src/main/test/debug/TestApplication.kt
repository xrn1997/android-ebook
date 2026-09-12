package debug

import com.ebook.common.BookApplication
import com.ebook.common.event.KeyCode
import com.therouter.router.addPathReplaceInterceptor
import com.therouter.router.interceptor.PathReplaceInterceptor
import dagger.hilt.android.HiltAndroidApp

/**
 * module_find 独立运行时的 Application（[module/AndroidManifest.xml] 引用）。
 *
 * 继承 [BookApplication] 复用公共初始化，并通过 TheRouter PathReplaceInterceptor
 * 替换两条跨模块路由、使模块独立运行时不依赖别的业务模块：
 * - 书籍详情（[KeyCode.Book.DETAIL_PATH]）→ 模块内的 [TestDetailActivity]
 *   （[KeyCode.Find.TEST_DETAIL_PATH]），不再依赖 module_book 的详情页；
 * - 书源管理（[KeyCode.Me.BOOK_SOURCE_PATH]）→ 模块内的 [TestBookSourceActivity]
 *   （[KeyCode.Find.TEST_BOOK_SOURCE_PATH]），让书城空态的「去导入书源」按钮在独立模式下也点得动。
 */
@HiltAndroidApp
class TestApplication : BookApplication() {
    override fun onCreate() {
        super.onCreate()
        addPathReplaceInterceptor(object : PathReplaceInterceptor() {
            override fun replace(path: String?): String? {
                if (path == KeyCode.Book.DETAIL_PATH) {
                    return KeyCode.Find.TEST_DETAIL_PATH
                }
                if (path == KeyCode.Me.BOOK_SOURCE_PATH) {
                    return KeyCode.Find.TEST_BOOK_SOURCE_PATH
                }
                return path
            }
        })
    }
}
