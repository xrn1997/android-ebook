package debug

import androidx.compose.runtime.Composable
import com.ebook.common.provider.IBookProvider
import com.therouter.TheRouter
import dagger.hilt.android.AndroidEntryPoint

/**
 * module_book 独立运行入口：共享基座负责主题、edge-to-edge、状态栏与双击退出，
 * 这里只组合本模块的首页 Provider。
 *
 * 独立宿主没有书城 Tab，传 null：书架空态据此不渲染「去书城找书」。
 */
@AndroidEntryPoint
class MainActivity : DebugHostActivity() {

    @Composable
    override fun DebugHostContent() {
        TheRouter.get(IBookProvider::class.java)?.mainBookPage?.invoke(null)
    }
}
