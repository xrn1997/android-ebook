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
