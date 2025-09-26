package debug

import com.ebook.common.BaseApplication
import com.ebook.common.event.KeyCode
import com.ebook.db.ObjectBoxManager
import com.therouter.router.addPathReplaceInterceptor
import com.therouter.router.interceptor.PathReplaceInterceptor
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class FindApplication : BaseApplication() {
    override fun onCreate() {
        super.onCreate()
        ObjectBoxManager.init(context)
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
