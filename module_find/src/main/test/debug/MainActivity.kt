package debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ebook.common.provider.IFindProvider
import com.therouter.TheRouter
import com.xrn1997.common.ui.theme.MyApplicationTheme
import com.xrn1997.common.util.setStatusBarColor
import com.xrn1997.common.util.ToastUtil.showShort
import dagger.hilt.android.AndroidEntryPoint
import kotlin.system.exitProcess

/**
 * module_find 独立运行（isModule=true）入口：直接组合 Provider 暴露的 Compose 页面，
 * 与 module_main 宿主内的渲染路径一致。
 *
 * - 必须包裹 [MyApplicationTheme]：裸 MaterialTheme 固定浅色 baseline 配色（浅紫），
 *   会与经 BaseActivity 自带主题的二级页（跟随壁纸取色）形成配色分裂；
 *   同时缺失深色模式跟随（对齐 module_me 宿主的同款修复）
 * - 必须开启 edge-to-edge：书城页 TopAppBar 需延伸到状态栏后形成沉浸式顶栏，
 *   不开启时状态栏区域残留窗口底色（集成宿主由 BaseActivity 处理，此处需自行对齐）
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var exitTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 书城页顶栏为 surface 浅色底（深色模式为暗色），状态栏图标跟随深浅色自动适配；
        // 集成宿主由 BaseActivity.setStatusBarColor() 统一处理，此处对齐同一默认行为
        setStatusBarColor()
        setContent {
            MyApplicationTheme {
                BackHandler {
                    exit()
                }
                TheRouter.get(IFindProvider::class.java)?.mainFindPage?.invoke()
            }
        }
    }

    /** 双击返回退出（2 秒内连按两次），防误触。 */
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
