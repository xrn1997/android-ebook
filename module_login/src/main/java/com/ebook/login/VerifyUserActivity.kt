package com.ebook.login

import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ebook.common.event.KeyCode
import com.ebook.login.mvvm.viewmodel.ModifyPwdViewModel
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseMvvmActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * 忘记密码流程第一步：邮箱验证码验证身份（纯邮箱验证，不依赖用户名）。
 *
 * 输入邮箱 → 获取验证码（服务端发邮件）→ 输入验证码 → 进入第二步 [ModifyPwdActivity]（RESET 模式）。
 * 验证码正确性由服务端在重置时校验（A0132 验证码错误 / A0241 尝试超限），客户端不做本地校验。
 *
 * 本页持有 [KeyCode.Login.MODIFY_PATH]（验证身份的根入口）；[ModifyPwdActivity] 持有
 * [KeyCode.Login.MODIFY_PWD_PATH]，两页路由不得重复。
 *
 * 必须继承 [BaseMvvmActivity]：[ModifyPwdViewModel] 发出的 `sendToast`/`sendFinish` 与 loading
 * 覆盖层只由基类的 `MvvmBinder` 消费，裸 `BaseActivity` 下会静默失效（同 [RegisterActivity]）。
 */
@AndroidEntryPoint
@Route(path = KeyCode.Login.MODIFY_PATH)
class VerifyUserActivity : BaseMvvmActivity<ModifyPwdViewModel>() {
    override val viewModel: ModifyPwdViewModel by viewModels()

    @Composable
    override fun PageContent() {
        // 发码倒计时驻留 ViewModel：横竖屏切换不丢进度（与服务端 60 秒频控对齐）
        val countdown by viewModel.codeCountdown.collectAsStateWithLifecycle()
        VerifyUserScreen(
            countdownSeconds = countdown,
            onSendCode = { email ->
                viewModel.sendForgotCode(email)
            },
            onNext = { email, code ->
                viewModel.toResetPage(email, code)
            }
        )
    }
}

/**
 * 验证身份表单：引导文案 + 邮箱 / 验证码（内嵌倒计时发码按钮）/ 下一步。
 *
 * @param countdownSeconds 发码倒计时剩余秒数，0 = 可发码（由 ViewModel 在发码成功后驱动）
 */
@Composable
fun VerifyUserScreen(
    countdownSeconds: Int,
    onSendCode: (String) -> Unit,
    onNext: (String, String) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var verifyCode by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 顶部对齐而非垂直居中：小屏/横屏下键盘弹起与内容加长时可滚动
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AuthPagePadding)
                .padding(top = 24.dp, bottom = 24.dp)
        ) {
            // 流程引导文案：先让用户知道这一页要做什么
            Text(
                text = stringResource(R.string.verify_user_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text(stringResource(R.string.print_email)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            AuthCodeField(
                value = verifyCode,
                onValueChange = { verifyCode = it },
                countdownSeconds = countdownSeconds,
                onSendCode = { onSendCode(email) }
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = { onNext(email, verifyCode) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(AuthButtonHeight)
            ) {
                Text(
                    text = stringResource(R.string.next),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
