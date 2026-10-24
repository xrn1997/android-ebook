package debug

import androidx.compose.runtime.Composable
import com.ebook.common.provider.IFindProvider
import com.therouter.TheRouter
import dagger.hilt.android.AndroidEntryPoint

/**
 * module_find 独立运行入口：共享基座负责主题、edge-to-edge、状态栏与双击退出，
 * 这里只组合本模块的首页 Provider。
 */
@AndroidEntryPoint
class MainActivity : DebugHostActivity() {

    @Composable
    override fun DebugHostContent() {
        TheRouter.get(IFindProvider::class.java)?.mainFindPage?.invoke()
    }
}
