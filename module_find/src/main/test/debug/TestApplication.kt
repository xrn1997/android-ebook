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
 * 将书籍详情路由（[KeyCode.Book.DETAIL_PATH]）替换为模块内的 [TestDetailActivity]
 * （[KeyCode.Find.TEST_DETAIL_PATH]），使模块独立运行时不依赖 module_book 的详情页。
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
                return path
            }
        })
    }
}
