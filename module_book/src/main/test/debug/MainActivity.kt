package debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ebook.common.provider.IBookProvider
import com.therouter.TheRouter
import com.xrn1997.common.ui.theme.MyApplicationTheme
import com.xrn1997.common.util.setStatusBarColor
import com.xrn1997.common.util.ToastUtil.showShort
import dagger.hilt.android.AndroidEntryPoint
import kotlin.system.exitProcess

/**
 * module_book 独立运行（isModule=true）入口：直接组合 Provider 暴露的 Compose 页面，
 * 与 module_main 宿主内的渲染路径一致。
 *
 * - 必须包裹 [MyApplicationTheme]：裸 MaterialTheme 固定浅色 baseline 配色（浅紫），
 *   会与经 BaseActivity 自带主题的二级页（跟随壁纸取色）形成配色分裂；
 *   同时缺失深色模式跟随（对齐 module_me/module_find 宿主的同款修复）
 * - 必须开启 edge-to-edge：独立宿主无人消费窗口 insets，页面顶栏自行以
 *   statusBarsPadding 避让状态栏（注意 padding 须位于固定高度修饰符外层，
 *   否则 48dp 顶栏内容区会被状态栏 insets 压缩为零高导致白屏）
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var exitTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 状态栏图标跟随深浅色自动适配，对齐集成宿主 BaseActivity.setStatusBarColor() 默认行为
        setStatusBarColor()
        setContent {
            MyApplicationTheme {
                BackHandler {
                    exit()
                }
                TheRouter.get(IBookProvider::class.java)?.mainBookPage?.invoke()
            }
        }
    }

    private fun exit() {
        if ((System.currentTimeMillis() - exitTime) > 2000) {
            showShort(this, "再按一次退出程序")
            exitTime = System.currentTimeMillis()
        } else {
            finish()
            exitProcess(0)
        }
    }
}
