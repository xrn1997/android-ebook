package com.ebook.login

import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ebook.common.event.KeyCode
import com.ebook.login.mvvm.viewmodel.ModifyPwdViewModel
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseMvvmActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * 密码设置页（双模式，对齐 ebook-server ADR-0002 的密码管理双路径）：
 *
 * - [MODE_RESET]：忘记密码第二步，由 [VerifyUserActivity] 携 email+验证码跳入，
 *   只显示「新密码×2」，提交走验证码重置端点；
 * - [MODE_LOGGED_IN]（默认）：已登录改密，显示「旧密码+新密码×2」，
 *   提交走改密端点（旧密码服务端校验）。
 *
 * 路由：本页持有 [KeyCode.Login.MODIFY_PWD_PATH]，由 [VerifyUserActivity]（MODIFY_PATH）
 * 经 TheRouter 直跳而来；「我的-设置」入口进入时不带 mode extra，即默认已登录改密。
 *
 * 必须继承 [BaseMvvmActivity]：[ModifyPwdViewModel] 改密/重置成功后靠 `sendToast` + `sendFinish`
 * 收尾（这页 finish 不掉就会在登录成功后被回退 expose），而这些命令只由基类的
 * `MvvmBinder` 消费，裸 `BaseActivity` 下静默失效（同 [RegisterActivity]）。
 */
@AndroidEntryPoint
@Route(path = KeyCode.Login.MODIFY_PWD_PATH)
class ModifyPwdActivity : BaseMvvmActivity<ModifyPwdViewModel>() {
    override val viewModel: ModifyPwdViewModel by viewModels()

    /**
     * RESET 模式下用「重置密码」覆写 Toolbar 标题：
     * 基类默认回退 manifest label（修改密码），而该 label 无法感知路由携带的模式。
     */
    override fun initPage() {
        if (intent.getStringExtra(EXTRA_MODE) == MODE_RESET) {
            toolbarTitle.value = getString(R.string.title_reset_pwd)
        }
    }

    @Composable
    override fun PageContent() {
        // RESET 模式所需的 email/验证码由上一步经路由参数携带
        val isResetMode = intent.getStringExtra(EXTRA_MODE) == MODE_RESET
        val email = intent.getStringExtra(EXTRA_EMAIL).orEmpty()
        val code = intent.getStringExtra(EXTRA_CODE).orEmpty()
        ModifyPwdScreen(
            isResetMode = isResetMode,
            onModifyLogged = { oldPwd, newPwd1, newPwd2 ->
                viewModel.modify(oldPwd, newPwd1, newPwd2)
            },
            onReset = { newPwd1, newPwd2 ->
                viewModel.reset(email, code, newPwd1, newPwd2)
            }
        )
    }

    companion object {
        /** intent/路由参数键：页面模式（[MODE_RESET] / [MODE_LOGGED_IN]） */
        const val EXTRA_MODE = "mode"

        /** intent/路由参数键：重置模式下的账号邮箱 */
        const val EXTRA_EMAIL = "email"

        /** intent/路由参数键：重置模式下的邮箱验证码 */
        const val EXTRA_CODE = "code"

        /** 忘记密码重置模式（验证码 + 新密码） */
        const val MODE_RESET = "reset"

        /** 已登录改密模式（旧密码 + 新密码） */
        const val MODE_LOGGED_IN = "logged_in"
    }
}

/**
 * 密码设置表单：引导文案 + 重置模式隐藏旧密码框，提交语义随模式切换。
 */
@Composable
fun ModifyPwdScreen(
    isResetMode: Boolean,
    onModifyLogged: (String, String, String) -> Unit,
    onReset: (String, String) -> Unit
) {
    var oldPassword by remember { mutableStateOf("") }
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
            // 流程引导文案：随模式切换文案，标题已由 Toolbar 承载（见 initPage）
            Text(
                text = stringResource(
                    if (isResetMode) R.string.reset_pwd_subtitle else R.string.modify_pwd_subtitle
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 仅已登录改密需要旧密码；重置模式用户不知道旧密码
            if (!isResetMode) {
                OutlinedTextField(
                    value = oldPassword,
                    onValueChange = { oldPassword = it },
                    label = { Text(stringResource(R.string.print_old_pwd)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))
            }

            OutlinedTextField(
                value = password1,
                onValueChange = { password1 = it },
                label = { Text(stringResource(R.string.print_new_pwd)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = password2,
                onValueChange = { password2 = it },
                label = { Text(stringResource(R.string.print_new_pwd_again)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    if (isResetMode) {
                        onReset(password1, password2)
                    } else {
                        onModifyLogged(oldPassword, password1, password2)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(AuthButtonHeight)
            ) {
                Text(
                    text = stringResource(R.string.confirm_modify),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
