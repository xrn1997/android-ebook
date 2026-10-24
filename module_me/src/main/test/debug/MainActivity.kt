package debug

import androidx.compose.runtime.Composable
import com.ebook.common.provider.IMeProvider
import com.therouter.TheRouter
import dagger.hilt.android.AndroidEntryPoint

/**
 * module_me 独立运行入口：共享基座负责主题、edge-to-edge 与双击退出，
 * 这里只组合本模块的首页 Provider。
 *
 * 状态栏图标用浅色：MePage 头部渐变在深浅色模式下都是深色调，由页面内的
 * AdaptStatusBarIcons 在进入组合后继续接管。
 */
@AndroidEntryPoint
class MainActivity : DebugHostActivity() {

    override val forceLightStatusBarIcons: Boolean = true

    @Composable
    override fun DebugHostContent() {
        TheRouter.get(IMeProvider::class.java)?.mainMePage?.invoke()
    }
}
