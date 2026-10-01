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
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ebook.common.event.KeyCode
import com.ebook.common.ui.preview.AppPreview
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

    /**
     * 外壳：只 collect 倒计时并把值与回调下传 [VerifyUserScreen]（无状态根本身不接 ViewModel）。
     */
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
 * 验证身份表单（**无状态根**）：引导文案 + 邮箱 / 验证码（内嵌倒计时发码按钮）/ 下一步。
 *
 * 形参只有值与回调，故可预览（约定见 AGENTS.md「屏幕的无状态根」）。邮箱与验证码两个输入框
 * 是本页自己的 `remember` 编辑态，不下传——本页没有外部写入点（下一步的 email+验证码
 * 是**出参**，经 [onNext] 交给 ViewModel 再走路由），把编辑态搬到外壳换不到任何东西。
 * 验证码位数/非空校验由 [ModifyPwdViewModel] 判定后经 `sendToast` 提示，页面上没有错误文本入参。
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

/**
 * 预览：验证身份页的**两档发码态**（深浅各两张）。
 *
 * 与注册页是同一个判据、同一个坑：倒计时期间那颗按钮必须换成「N 秒后重发」并禁用
 * （[AuthCodeField] 里那颗按钮的 `enabled` ），写反了不报错，只是让用户连点撞 A0241。
 * 本页与注册页唯一的排版差别是底部只有一颗「下一步」（没有密码两栏），
 * 所以这几张图同时也是那一段垂直节奏的凭证。
 */
@PreviewLightDark
@Composable
private fun VerifyUserScreenPreview(
    @PreviewParameter(VerifyUserCountdownProvider::class) countdownSeconds: Int,
) {
    AppPreview {
        VerifyUserScreen(
            countdownSeconds = countdownSeconds,
            onSendCode = {},
            onNext = { _, _ -> },
        )
    }
}

/** 发码倒计时的两档样例：0 = 可点，45 = 刚发过码、频控窗口还剩 45 秒 */
private class VerifyUserCountdownProvider : PreviewParameterProvider<Int> {
    private val countdownCases = listOf(0, 45)

    override val values: Sequence<Int>
        get() = countdownCases.asSequence()

    override fun getDisplayName(index: Int): String = if (index == 0) "可发码" else "倒计时中"
}
