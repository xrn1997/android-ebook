package debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ebook.common.event.KeyCode
import com.ebook.common.util.SPUtil
import com.ebook.login.R
import com.therouter.TheRouter.build
import com.therouter.router.Route
import com.xrn1997.common.ui.theme.MyApplicationTheme
import com.xrn1997.common.util.setStatusBarColor
import com.xrn1997.common.util.ToastUtil.showShort

/**
 * module_login 独立运行（isModule=true）入口：提供登录/注册/拦截测试/退出登录四个按钮，
 * 用于单模块调试登录链路。
 *
 * - 必须包裹 [MyApplicationTheme]：裸 MaterialTheme 固定浅色 baseline 配色，
 *   会与经 BaseActivity 自带主题（动态取色/深色跟随）的二级页形成配色分裂
 * - 必须开启 edge-to-edge：对齐集成宿主 BaseActivity 的沉浸式行为，
 *   独立宿主需自行补齐（参考 module_me/module_book 的 test/debug 宿主）
 *
 * [Route] 到 [KeyCode.Main.MAIN_PATH]：独立模式不编译 module_main，真实主页路由不存在，
 * 而 [com.ebook.login.mvvm.viewmodel.LoginViewModel] 登录成功后的「主动跳登录」分支要靠该路径
 * CLEAR_TOP 清掉中间页回主界面；TheRouter 找不到路由时只记日志不报错，登录页只能靠自身 finish
 * 退回上一层（会把未关闭的中间页露出来）。把调试宿主挂到同一路径，兜底跳转在独立模式下
 * 同样能落到一个可见的确定页面，两种构建模式行为对齐。本 source set 只在 isModule=true 时
 * 参与编译，不会与集成模式的真主页抢路由。
 */
@Route(path = KeyCode.Main.MAIN_PATH)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 状态栏图标跟随深浅色自动适配，对齐集成宿主 BaseActivity.setStatusBarColor() 默认行为
        setStatusBarColor()
        setContent {
            MyApplicationTheme {
                DebugEntryScreen(
                    onLogin = {
                        if (SPUtil.get(KeyCode.Login.SP_IS_LOGIN, false)) {
                            showShort(this, "已经登录")
                        } else {
                            build(KeyCode.Login.LOGIN_PATH).navigation()
                        }
                    },
                    onRegister = { build(KeyCode.Login.REGISTER_PATH).navigation() },
                    onInterrupt = {
                        val bundle = Bundle()
                        bundle.putBoolean("123", true)
                        build(KeyCode.Login.TEST_INTERRUPT_PATH)
                            .withString("msg", "被therouter拦截的参数：")
                            .withInt("key", 1)
                            .withBundle("bundle2", bundle)
                            .navigation()
                    },
                    onExit = {
                        showShort(this, "退出登录成功")
                        SPUtil.remove(KeyCode.Login.SP_IS_LOGIN)
                    }
                )
            }
        }
    }
}

/** 调试入口按钮列表：复刻原 activity_main.xml 的四按钮布局（登录/注册/拦截/退出登录）。 */
@Composable
private fun DebugEntryScreen(
    onLogin: () -> Unit,
    onRegister: () -> Unit,
    onInterrupt: () -> Unit,
    onExit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Button(
            onClick = onLogin,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Text(text = stringResource(R.string.login))
        }

        Button(
            onClick = onRegister,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Text(text = stringResource(R.string.register))
        }

        Button(
            onClick = onInterrupt,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Text(text = stringResource(R.string.interrupt))
        }

        Button(
            onClick = onExit,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = stringResource(R.string.logout))
        }
    }
}
