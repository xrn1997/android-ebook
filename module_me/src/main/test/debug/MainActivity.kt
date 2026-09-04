package debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ebook.common.provider.IMeProvider
import com.therouter.TheRouter
import com.xrn1997.common.ui.theme.MyApplicationTheme
import com.xrn1997.common.util.setStatusBarColor
import dagger.hilt.android.AndroidEntryPoint

/**
 * module_me 独立运行（isModule=true）入口：直接组合 Provider 暴露的 Compose 页面，
 * 与 module_main 宿主内的渲染路径一致。
 *
 * - 必须包裹 [MyApplicationTheme]：裸 MaterialTheme 固定浅色 baseline 配色，
 *   会导致本页在系统深色模式下不切换（二级页经 BaseActivity 已有主题，唯独入口缺失）
 * - 必须开启 edge-to-edge：MePage 头部渐变需要延伸到状态栏后形成沉浸式头部，
 *   不开启时窗口内容不进入状态栏区域，渐变顶部被截断（集成宿主同样需关闭基类的
 *   系统栏偏移，见 module_main MainActivity.enableFitsSystemWindows）
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 状态栏图标浅色的初值（首帧前的窗口状态）：MePage 头部渐变在深浅色模式下均为深色调，
        // 图标需浅色才可读；进入组合后由 MePage 的 AdaptStatusBarIcons 按渐变亮度接管
        setStatusBarColor(isLight = false)
        setContent {
            MyApplicationTheme {
                TheRouter.get(IMeProvider::class.java)?.mainMePage?.invoke()
            }
        }
    }
}
