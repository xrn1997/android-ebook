package debug

import com.ebook.common.BookApplication
import com.ebook.common.interceptor.LoginInterceptor
import com.therouter.router.addRouterReplaceInterceptor
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TestApplication : BookApplication() {
    override fun onCreate() {
        super.onCreate()
        addRouterReplaceInterceptor(LoginInterceptor())
    }
}
