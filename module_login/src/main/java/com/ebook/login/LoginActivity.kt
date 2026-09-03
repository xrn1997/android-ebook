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
import androidx.compose.ui.unit.dp
import com.ebook.common.event.KeyCode
import com.ebook.login.mvvm.viewmodel.LoginViewModel
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseMvvmActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * 登录页：标准 M3 表单页（邮箱 + 密码，对齐 ebook-server ADR-0002：邮箱为登录主标识）。
 *
 * 历史上本页使用固定品牌背景图 + inverse 语义色对；认证域 UI 统一改造时改为
 * 与应用主体一致的标准风格——background 底色 + OutlinedTextField + 语义色，
 * 深浅色模式随主题自动适配（背景与决策见 docs/login-modernization-spec.md 状态注记）。
 *
 * 本页 [enableToolbar] 关闭、[enableFitsSystemWindows] 关闭（内容延伸至状态栏，
 * 由内容自行 [statusBarsPadding] 避让），品牌标题区取代顶栏。
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

    @Composable
    override fun PageContent() {
        val viewModel: LoginViewModel = viewModel
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
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.print_email)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 密码输入框：64 位上限与服务端约束一致
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        if (it.length <= 64) {
                            password = it
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
                Button(
                    onClick = { viewModel.login(email, password) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        text = stringResource(R.string.login),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 注册和忘记密码：两端对齐的次级入口
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = { toRegisterActivity() }) {
                        Text(text = stringResource(R.string.register_entry))
                    }

                    TextButton(onClick = { toForgetPwdActivity() }) {
                        Text(text = stringResource(R.string.fgt_pwd))
                    }
                }
            }
        }
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
