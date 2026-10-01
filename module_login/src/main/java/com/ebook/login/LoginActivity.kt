package com.ebook.login

import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.ebook.common.event.KeyCode
import com.ebook.common.ui.preview.AppPreview
import com.ebook.login.mvvm.viewmodel.LoginViewModel
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseMvvmActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * 登录页：标准 M3 表单页（邮箱 + 密码；邮箱为登录主标识）。
 *
 * 历史上本页使用固定品牌背景图 + inverse 语义色对；认证域 UI 统一改造时改为
 * 与应用主体一致的标准风格——background 底色 + OutlinedTextField + 语义色，
 * 深浅色模式随主题自动适配（背景与决策见 docs/login-modernization-spec.md 状态注记）。
 *
 * 本页 [enableToolbar] 关闭、[enableFitsSystemWindows] 关闭（内容延伸至状态栏，
 * 由内容自行 [statusBarsPadding] 避让），品牌标题区取代顶栏。
 *
 * 分层：本页是**有状态外壳**，只负责三件事——表单编辑态、intent 预填的落点、跨页跳转；
 * 排版全在 [LoginScreen] 那个无状态根里（参数只有值与回调），于是它能被预览、
 * 也能被 `LoginScreensRenderTest` 在 JVM 上组合。
 */
@AndroidEntryPoint
@Route(path = KeyCode.Login.LOGIN_PATH)
class LoginActivity : BaseMvvmActivity<LoginViewModel>() {
    override val viewModel: LoginViewModel by viewModels()

    /**
     * 登录页是 singleTask：已存在实例时新 intent 经 onNewIntent 投递，
     * 而 PageContent 的初始 LaunchedEffect 不会重跑——用状态触发重组，
     * 否则注册成功等场景携带的预填参数（如 email）无法被消费。
     */
    private var prefillEmail by mutableStateOf("")

    /**
     * 禁止显示Toolbar，默认为true
     */
    override fun enableToolbar(): Boolean {
        return false
    }

    /**
     * 外壳：`email`/`password` 仍是本页的 `remember` 编辑态，两个 [LaunchedEffect] 的写入点
     * 也留在这里（`viewModel.bundle = intent.extras` 这一句必须和预填同一个时机发生，
     * 挪进无状态根就等于让排版层去读 Activity）。值与回调整体下传 [LoginScreen]。
     */
    @Composable
    override fun PageContent() {
        // 状态管理
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            email = intent.getStringExtra("email").orEmpty()
            viewModel.bundle = intent.extras
        }
        // singleTask 复用实例时的预填通道（见 prefillEmail 注释）
        LaunchedEffect(prefillEmail) {
            if (prefillEmail.isNotEmpty()) {
                email = prefillEmail
                viewModel.bundle = intent.extras
            }
        }
        LoginScreen(
            email = email,
            password = password,
            onEmailChange = { email = it },
            onPasswordChange = { password = it },
            onLogin = { viewModel.login(email, password) },
            onRegisterClick = { toRegisterActivity() },
            onForgetPwdClick = { toForgetPwdActivity() },
        )
    }

    override fun enableFitsSystemWindows(): Boolean {
        return false
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        prefillEmail = intent.getStringExtra("email").orEmpty()
    }

    private fun toRegisterActivity() {
        startActivity(Intent(this, RegisterActivity::class.java))
    }

    private fun toForgetPwdActivity() {
        startActivity(Intent(this, VerifyUserActivity::class.java))
    }
}

/**
 * 登录表单（**无状态根**）：品牌标题区 + 邮箱 / 密码 + 主操作 + 两个次级入口。
 *
 * 形参只有不可变状态与回调，不接 ViewModel / Activity / Context——`@Preview` 只能标注无参
 * Composable、设计期不经 Hilt，因此屏幕必须收成这一层才能被预览（约定见 AGENTS.md
 * 「屏幕的无状态根」）。
 *
 * 没有「校验错误文本 / 提交中禁用」这类入参可传，是本页的事实而不是遗漏：非空校验、
 * 账号密码正确性都由 ViewModel 判定后经 `sendToast` 提示（客户端不做假语义校验），
 * 提交在途走基类消费 `BaseViewModel.uiState` 的 loading 覆盖层。把 `isSubmitting` 传进来
 * 去禁用按钮会改行为（原本靠 VM 的 `isLoggingIn` 防重复点击），故不传。
 *
 * @param email 邮箱输入值（登录主标识；注册/重置成功跳回本页时由路由参数预填）
 * @param password 密码输入值
 * @param onEmailChange 邮箱输入变更
 * @param onPasswordChange 密码输入变更，超过 64 位不回调（与服务端约束一致）
 * @param onLogin 点击「登　录」
 * @param onRegisterClick 点击「注册」
 * @param onForgetPwdClick 点击「忘记密码」
 */
@Composable
fun LoginScreen(
    email: String,
    password: String,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLogin: () -> Unit,
    onRegisterClick: () -> Unit,
    onForgetPwdClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // enableFitsSystemWindows()=false：内容延伸到状态栏下，这里手动避让
                .statusBarsPadding()
                .padding(horizontal = 24.dp)
        ) {
            // 品牌标题区：取代顶栏，主色品牌字 + 弱化的副标题
            Spacer(modifier = Modifier.height(48.dp))
            Text(
                text = stringResource(R.string.ebook),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.login_subtitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(48.dp))

            // 邮箱输入框（格式校验交给服务端业务码，客户端不做假语义校验）
            OutlinedTextField(
                value = email,
                onValueChange = onEmailChange,
                label = { Text(stringResource(R.string.print_email)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 密码输入框：64 位上限与服务端约束一致（超限不回调，输入停在 64 位）
            OutlinedTextField(
                value = password,
                onValueChange = {
                    if (it.length <= 64) {
                        onPasswordChange(it)
                    }
                },
                label = { Text(stringResource(R.string.print_pwd)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(32.dp))

            // 登录主操作按钮
            // 高度与注册/验证/改密同一个常量、字级不覆写（用 M3 默认 labelLarge）：
            // 原先这里写死 56.dp 且覆盖成 titleMedium，登录↔注册来回切时按钮会跳高 4dp、字号也差两级
            Button(
                onClick = onLogin,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(AuthButtonHeight)
            ) {
                Text(
                    text = stringResource(R.string.login),
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 注册和忘记密码：两端对齐的次级入口
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onRegisterClick) {
                    Text(text = stringResource(R.string.register_entry))
                }

                TextButton(onClick = onForgetPwdClick) {
                    Text(text = stringResource(R.string.fgt_pwd))
                }
            }
        }
    }
}

/**
 * 预览：登录页**空表单**（深浅两档）。
 *
 * 空表单是每次冷启动都要经过的一态，也是最容易画错的一态：label 未上浮时落在输入框的
 * 基线上，品牌标题区又把首屏撑得很满——只预览填过的样子看不出这一点。
 * 深浅两档由官方 `@PreviewLightDark` 翻 `uiMode` 给出，与运行时 `ThemeMode.SYSTEM` 同一个判定。
 */
@PreviewLightDark
@Composable
private fun LoginScreenEmptyPreview() {
    AppPreview {
        LoginScreen(
            email = "",
            password = "",
            onEmailChange = {},
            onPasswordChange = {},
            onLogin = {},
            onRegisterClick = {},
            onForgetPwdClick = {},
        )
    }
}

/**
 * 预览：登录页三种「输入已有内容」的形态——手填、注册成功后预填邮箱、超长邮箱地址。
 *
 * 这三档都是跳回本页时真实会出现的状态（`RegisterViewModel.register` 与
 * `ModifyPwdViewModel.reset` 成功后都带 email 跳登录），而「预填了邮箱、密码仍是空」
 * 与「两个框都有值」的 label 上浮态不同，只拍一种就看不出另一种的留位。第三档看的是
 * `singleLine` 输入框在地址远超宽度时的表现（不折行，靠横向滚动），这类边界只能对着图判断。
 * 密码经 `PasswordVisualTransformation` 渲染成点位，图上读不到长度与内容。
 */
@Preview(showBackground = true)
@Composable
private fun LoginScreenPrefilledPreview(
    @PreviewParameter(LoginPrefillProvider::class) prefill: LoginPrefillCase,
) {
    AppPreview {
        LoginScreen(
            email = prefill.email,
            password = prefill.password,
            onEmailChange = {},
            onPasswordChange = {},
            onLogin = {},
            onRegisterClick = {},
            onForgetPwdClick = {},
        )
    }
}

/**
 * 登录页预填样例：本页的状态全是裸字符串（邮箱 / 密码），而 `PreviewSamples` 只收 ebook 域实体
 * （书架条目、书源定义），没有账号类出口，所以样例在这一页就地给——先例是 module_find 书城页里
 * 同样就地构造的 `sampleBookTypes` 那份。
 */
private data class LoginPrefillCase(
    val email: String,
    val password: String,
    val label: String,
)

/** 三档：手填中 / 注册成功后只预填邮箱 / 超长地址（看单行输入框的横向行为） */
private class LoginPrefillProvider : PreviewParameterProvider<LoginPrefillCase> {
    private val cases = listOf(
        LoginPrefillCase("reader@example.com", "mima123456", "手填邮箱与密码"),
        LoginPrefillCase("new-user@example.com", "", "注册成功预填邮箱"),
        LoginPrefillCase("a-very-long-local-part-for-layout-check@some-really-long-domain-name.example.com", "", "超长邮箱"),
    )

    override val values: Sequence<LoginPrefillCase>
        get() = cases.asSequence()

    override fun getDisplayName(index: Int): String = cases[index].label
}
