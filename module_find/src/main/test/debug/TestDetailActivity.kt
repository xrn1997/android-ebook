package debug

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ebook.common.event.FROM_BOOKSHELF
import com.ebook.common.event.FROM_SEARCH
import com.ebook.common.event.KeyCode
import com.ebook.db.entity.SearchBookEntity
import com.therouter.TheRouter
import com.therouter.router.Autowired
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseActivity

/**
 * 模块独立运行时的书籍详情桩页：仅展示来源标记与书籍基本信息，
 * 替代 module_book 的真实详情页，使 module_find 可脱离宿主独立调试。
 *
 * 路由由 [TestApplication] 的 PathReplaceInterceptor 从 [KeyCode.Book.DETAIL_PATH]
 * 重定向到 [KeyCode.Find.TEST_DETAIL_PATH]。
 */
@Route(path = KeyCode.Find.TEST_DETAIL_PATH)
class TestDetailActivity : BaseActivity() {
    @Autowired(name = "from")
    var openFrom = -1

    @Autowired(name = "data")
    var searchBook: SearchBookEntity? = null

    @Autowired(name = "data_key")
    var dataKey: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        TheRouter.inject(this)
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun PageContent() {
        Column(Modifier.fillMaxSize()) {
            if (openFrom == FROM_BOOKSHELF) {
                Text("来自书架")
            } else if (openFrom == FROM_SEARCH) {
                Text("来自搜索")
            }
            searchBook?.let {
                Text(it.name)
                Text(it.author)
            }
        }
    }

    override fun initData() {

    }
}