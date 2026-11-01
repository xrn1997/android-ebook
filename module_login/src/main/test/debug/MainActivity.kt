package debug

import android.os.Bundle
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
import androidx.lifecycle.lifecycleScope
import com.ebook.common.domain.UserSessionManager
import com.ebook.common.event.KeyCode
import com.ebook.common.util.SPUtil
import com.ebook.login.R
import com.therouter.TheRouter.build
import com.therouter.router.Route
import com.xrn1997.common.util.ToastUtil.showShort
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * module_login 独立运行入口：登录/注册/拦截测试/退出登录四个按钮。
 *
 * 共享基座负责主题、edge-to-edge、状态栏与双击退出；本页只在 [DebugHostContent] 里给出
 * 调试入口。`@Route` 到 [KeyCode.Main.MAIN_PATH] 的原因不变：独立模式不编译 module_main，
 * 登录成功后的「主动跳主页」要靠该路径清栈；找不到路由时只记日志，登录页会露在中间页上。
 *
 * 退出登录经 [UserSessionManager.endSession] 清会话（三处镜像一并失效）——
 * 此前只 `SPUtil.remove(SP_IS_LOGIN)`，`user_session` 与内存态里的旧凭证会一直残留。
 */
@AndroidEntryPoint
@Route(path = KeyCode.Main.MAIN_PATH)
class MainActivity : DebugHostActivity() {

    @Inject
    lateinit var userSessionManager: UserSessionManager

    @Composable
    override fun DebugHostContent() {
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
                // endSession 的失败日志与线程契约都在接口里（ADR-0056），调试页只管发起
                lifecycleScope.launch { userSessionManager.endSession() }
            },
        )
    }
}

/** 调试入口按钮列表：复刻原 activity_main.xml 的四按钮布局（登录/注册/拦截/退出登录） */
@Composable
private fun DebugEntryScreen(
    onLogin: () -> Unit,
    onRegister: () -> Unit,
    onInterrupt: () -> Unit,
    onExit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Button(
            onClick = onLogin,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            Text(text = stringResource(R.string.login))
        }

        Button(
            onClick = onRegister,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            Text(text = stringResource(R.string.register))
        }

        Button(
            onClick = onInterrupt,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            Text(text = stringResource(R.string.interrupt))
        }

        Button(
            onClick = onExit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.logout))
        }
    }
}
