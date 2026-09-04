package debug

import com.ebook.common.BookApplication
import com.ebook.common.event.KeyCode
import com.therouter.router.addPathReplaceInterceptor
import com.therouter.router.interceptor.PathReplaceInterceptor
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TestApplication : BookApplication() {
    override fun onCreate() {
        super.onCreate()
        addPathReplaceInterceptor(object : PathReplaceInterceptor() {
            override fun replace(path: String?): String? {
                if (path == KeyCode.Login.LOGIN_PATH) {
                    return KeyCode.Book.TEST_LOGIN_PATH
                }
                return path
            }
        })
    }
}
