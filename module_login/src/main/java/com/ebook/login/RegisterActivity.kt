package com.ebook.login

import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ebook.common.event.KeyCode
import com.ebook.common.ui.preview.AppPreview
import com.ebook.login.mvvm.viewmodel.RegisterViewModel
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseMvvmActivity
import dagger.hilt.android.AndroidEntryPoint

/** 认证域表单主操作按钮高度（登录/注册/验证/改密统一） */
internal val AuthButtonHeight = 52.dp

/** 认证域表单页水平边距（比内容页 16dp 略宽，表单更聚焦） */
internal val AuthPagePadding = 24.dp

/**
 * 注册页：三步注册
 * （邮箱 → 获取验证码 → 验证码 + 密码）。注册不发 token，成功后跳登录页。
 *
 * 必须继承 [BaseMvvmActivity] 而非裸 `BaseActivity`：ViewModel 的一次性命令通道
 * （`sendFinish`/`sendToast`）与 loading 覆盖层只由基类的 `MvvmBinder` 消费，
 * 换成裸基类不会编译报错、只会静默失效——注册成功后 `sendFinish()` 不生效，
 * 注册页残留在返回栈里，登录成功后回退又会看到它（同时表单校验提示 Toast 全部丢失）。
 */
@AndroidEntryPoint
@Route(path = KeyCode.Login.REGISTER_PATH)
class RegisterActivity : BaseMvvmActivity<RegisterViewModel>() {
    override val viewModel: RegisterViewModel by viewModels()

    /**
     * 外壳：只 collect 倒计时并把值与回调下传 [RegisterScreen]（无状态根本身不接 ViewModel）。
     */
    @Composable
    override fun PageContent() {
        // 发码倒计时驻留 ViewModel：横竖屏切换不丢进度（与服务端 60 秒频控对齐）
        val countdown by viewModel.codeCountdown.collectAsStateWithLifecycle()
        RegisterScreen(
            countdownSeconds = countdown,
            onSendCode = { email ->
                viewModel.sendCode(email)
            },
            onRegister = { email, code, password1, password2 ->
                viewModel.register(email, code, password1, password2)
            }
        )
    }
}

/**
 * 注册表单（**无状态根**）：引导文案 + 邮箱 / 验证码（内嵌倒计时发码按钮）/ 密码 / 确认密码。
 *
 * 用户名不在注册时收集——服务端自动生成占位用户名，用户可后期自改（对齐服务端注册契约）。
 *
 * 形参只有值与回调（不接 ViewModel），故可预览；四个输入框的值是本页自己的
 * `remember` 编辑态，**刻意不下传**：VM 只在点击那一刻收整份表单，把编辑态搬到
 * [RegisterActivity.PageContent] 只会让参数表变长而换不到任何东西（登录页必须搬是因为
 * 那里有 intent 预填这个外部写入点，这里没有）。校验与结果提示同样不在这一层——
 * 非空/6 位/两次一致由 [RegisterViewModel] 判定后经 `sendToast` 给出，页面上没有
 * 「错误文本」入参可传。
 *
 * @param countdownSeconds 发码倒计时剩余秒数，0 = 可发码（由 ViewModel 在发码成功后驱动）
 */
@Composable
fun RegisterScreen(
    countdownSeconds: Int,
    onSendCode: (String) -> Unit,
    onRegister: (String, String, String, String) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password1 by remember { mutableStateOf("") }
    var password2 by remember { mutableStateOf("") }

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
                text = stringResource(R.string.register_subtitle),
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
                value = code,
                onValueChange = { code = it },
                countdownSeconds = countdownSeconds,
                onSendCode = { onSendCode(email) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = password1,
                onValueChange = { password1 = it },
                label = { Text(stringResource(R.string.print_pwd)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = password2,
                onValueChange = { password2 = it },
                label = { Text(stringResource(R.string.print_pwd_again)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = { onRegister(email, code, password1, password2) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(AuthButtonHeight)
            ) {
                Text(
                    text = stringResource(R.string.register),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * 验证码输入框：右侧内嵌「获取验证码 / N 秒后重发」倒计时按钮。
 *
 * 注册页与验证身份页共用；倒计时状态由各自 ViewModel 的 `codeCountdown` 驱动，
 * 倒计时期间按钮禁用，与服务端 60 秒发码频控（A0241）对齐。
 *
 * @param countdownSeconds 剩余倒计时秒数，0 = 可点击发码
 * @param onSendCode 点击发码按钮回调（邮箱非空等前置校验由 ViewModel 承担）
 */
@Composable
internal fun AuthCodeField(
    value: String,
    onValueChange: (String) -> Unit,
    countdownSeconds: Int,
    onSendCode: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.print_verify_code)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        // trailing 槽位放倒计时按钮：与输入同行，视线无需在输入框与独立按钮间往返
        trailingIcon = {
            TextButton(
                onClick = onSendCode,
                enabled = countdownSeconds <= 0
            ) {
                Text(
                    text = if (countdownSeconds > 0) {
                        stringResource(R.string.code_countdown, countdownSeconds)
                    } else {
                        stringResource(R.string.get_verify_code)
                    }
                )
            }
        },
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * 预览：注册页的**两档发码态**（深浅各两张）。
 *
 * 挑这两档而不是别的，是因为「按钮此刻能不能点」完全由 [RegisterScreen] 的入参决定，
 * 而写反了不报错、不闪退：倒计时期间仍显示「获取验证码」就等于放任用户连点，
 * 一路撞服务端的 60 秒频控（A0241）。倒计时文案 `%1$d 秒后重发` 是格式化串，
 * 只在渲染时看得出宽度够不够（trailing 槽位放不下时按钮会被挤到框外）。
 *
 * 四个输入框恒为空——那是本页自己持有的 `remember` 编辑态（见 [RegisterScreen] 的说明），
 * 于是这几张图同时也是「引导语 + 五个控件的垂直节奏」这一版式本身。
 */
@PreviewLightDark
@Composable
private fun RegisterScreenPreview(
    @PreviewParameter(CodeCountdownProvider::class) countdownSeconds: Int,
) {
    AppPreview {
        RegisterScreen(
            countdownSeconds = countdownSeconds,
            onSendCode = {},
            onRegister = { _, _, _, _ -> },
        )
    }
}

/**
 * 预览：共用的验证码输入框本体（可发码 / 倒计时禁用两档并排）。
 *
 * 这一格是注册页与验证身份页共用的组件，而它唯一的排版风险就是 trailing 槽位：
 * 「60 秒后重发」比「获取验证码」宽，按钮挤不进槽位时输入框与按钮会互相压缩——
 * 两档并排放在同一张图上才比得出高度是否一致（同一行控件在两种文案下跳高是最难发现的）。
 */
@Preview(showBackground = true, widthDp = 360)
@Composable
private fun AuthCodeFieldPreview() {
    AppPreview {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AuthPagePadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AuthCodeField(value = "", onValueChange = {}, countdownSeconds = 0, onSendCode = {})
            AuthCodeField(value = "123456", onValueChange = {}, countdownSeconds = 60, onSendCode = {})
        }
    }
}

/** 发码倒计时的两档样例：0 = 可点，37 = 已发码且在 60 秒频控窗口内 */
private class CodeCountdownProvider : PreviewParameterProvider<Int> {
    private val countdownCases = listOf(0, 37)

    override val values: Sequence<Int>
        get() = countdownCases.asSequence()

    override fun getDisplayName(index: Int): String = if (index == 0) "可发码" else "倒计时中"
}
