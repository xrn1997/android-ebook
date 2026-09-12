package debug

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ebook.common.event.KeyCode
import com.therouter.TheRouter
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseActivity

/**
 * 模块独立运行时的书源管理桩页：替代 module_me 的真实书源管理页，
 * 使 module_find 可脱离宿主独立调试「零源 → 去导入」这条链路。
 *
 * 路由由 [TestApplication] 的 PathReplaceInterceptor 从 [KeyCode.Me.BOOK_SOURCE_PATH]
 * 重定向到 [KeyCode.Find.TEST_BOOK_SOURCE_PATH]，与 [TestDetailActivity] 同一套写法。
 */
@Route(path = KeyCode.Find.TEST_BOOK_SOURCE_PATH)
class TestBookSourceActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        TheRouter.inject(this)
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun PageContent() {
        Column(Modifier.fillMaxSize()) {
            Text("独立运行：书源管理占位页")
        }
    }

    override fun initData() {

    }
}
