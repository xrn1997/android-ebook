package debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import com.xrn1997.common.ui.theme.MyApplicationTheme
import com.xrn1997.common.util.ToastUtil.showShort
import com.xrn1997.common.util.setStatusBarColor
import kotlin.system.exitProcess

/**
 * 独立运行宿主的共享入口页基类。
 *
 * 四个有调试入口的模块原本各抄一份 `MainActivity` 的主题装配、edge-to-edge、状态栏图标与
 * 双击退出；这里收成一处，各模块只覆写 [DebugHostContent] 给出本模块的首页 Provider。
 * 与集成宿主的 `BaseActivity` 对齐：必须包 [MyApplicationTheme]（裸 MaterialTheme 会在
 * 深色模式下固定浅色 baseline），必须开启 edge-to-edge，状态栏图标默认跟随深浅色。
 */
abstract class DebugHostActivity : ComponentActivity() {

    /**
     * 是否强制状态栏图标为浅色；null = 跟随系统深浅色（默认）。
     *
     * 只在页头固定是深色调时才覆盖（module_me 的渐变头部）；其余模块与集成宿主
     * `BaseActivity` 对齐——浅色模式用深色图标、深色模式用浅色图标。
     */
    protected open val forceLightStatusBarIcons: Boolean? = null

    private var exitTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // setStatusBarColor(isLight) 的参数是「状态栏底色是否浅」，即图标是否深色；
        // 强制浅色图标要传 false，跟随系统则走无参重载（它按 isDarkMode() 求值）
        forceLightStatusBarIcons
            ?.let { setStatusBarColor(isLight = !it) }
            ?: setStatusBarColor()
        setContent {
            MyApplicationTheme {
                BackHandler { exitOnDoubleBack() }
                DebugHostContent()
            }
        }
    }

    /** 本模块独立运行时的首页内容 */
    @Composable
    protected abstract fun DebugHostContent()

    /** 双击返回退出（2 秒内连按两次），防误触 */
    private fun exitOnDoubleBack() {
        if ((System.currentTimeMillis() - exitTime) > 2000) {
            showShort(this, "再按一次退出程序")
            exitTime = System.currentTimeMillis()
        } else {
            finish()
            exitProcess(0)
        }
    }
}
