package debug

import com.ebook.common.BaseApplication
import com.ebook.common.interceptor.LoginInterceptor
import com.therouter.router.addRouterReplaceInterceptor
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class LoginApplication : BaseApplication() {
    override fun onCreate() {
        super.onCreate()
        addRouterReplaceInterceptor(LoginInterceptor())
    }
}
