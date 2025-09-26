package com.ebook.login

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ebook.common.event.KeyCode
import com.ebook.login.mvvm.viewmodel.LoginViewModel
import com.hwangjr.rxbus.RxBus
import com.therouter.router.Autowired
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseMvvmActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
@Route(path = KeyCode.Login.LOGIN_PATH)
class LoginActivity : BaseMvvmActivity<LoginViewModel>() {
    override val mViewModel: LoginViewModel by viewModels()
    @Autowired
    @JvmField
    var path: String = String()

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RxBus.get().register(this)
    }

    public override fun onDestroy() {
        super.onDestroy()
        RxBus.get().unregister(this)
    }

    /**
     * 禁止显示Toolbar，默认为true
     */
    override fun enableToolbar(): Boolean {
        return false
    }

    override fun initData() {
    }

    @Composable
    override fun InitView() {
        val viewModel: LoginViewModel = mViewModel
        // 获取当前上下文
        val context = LocalContext.current

        // 状态管理
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            val intent = (context as Activity).intent
            username = intent.getStringExtra("username") ?: ""
            password = intent.getStringExtra("password") ?: ""

            // 处理路径参数
            if (path.isNotEmpty()) {
                viewModel.path = path
                intent.extras?.let { bundle ->
                    viewModel.bundle = bundle
                }
            }
        }
        LoginScreen(
            username = username,
            onUsernameChange = { username = it },
            password = password,
            onPasswordChange = { password = it },
            onLogin = { viewModel.login(username, password) },
            onRegister = { toRegisterActivity() },
            onForgotPwd = { toForgetPwdActivity() }
        )
    }

    override fun enableFitsSystemWindows(): Boolean {
        return false
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun toRegisterActivity() {
        startActivity(Intent(this, RegisterActivity::class.java))
    }

    private fun toForgetPwdActivity() {
        startActivity(Intent(this, VerifyUserActivity::class.java))
    }

    @Composable
    fun LoginScreen(
        username: String,
        onUsernameChange: (String) -> Unit,
        password: String,
        onPasswordChange: (String) -> Unit,
        onLogin: () -> Unit,
        onRegister: () -> Unit,
        onForgotPwd: () -> Unit
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 背景图部分
            Image(
                painter = painterResource(R.drawable.login_cover),
                contentDescription = null,
                contentScale = ContentScale.Crop, // 控制图片裁剪方式
                modifier = Modifier.fillMaxSize()
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 标题
                Text(
                    text = stringResource(R.string.ebook),
                    color = Color.White,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 40.dp)
                )

                // 用户名输入框
                CustomTextField(
                    value = username,
                    onValueChange = onUsernameChange,
                    hint = stringResource(R.string.print_tel),
                    keyboardType = KeyboardType.Number,
                    maxLength = 11,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 40.dp)
                )

                // 密码输入框
                CustomTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    hint = stringResource(R.string.print_pwd),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardType = KeyboardType.Password,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )

                // 登录按钮
                Button(
                    onClick = onLogin,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = Color.White
                    ),
                    elevation = ButtonDefaults.buttonElevation(0.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(
                            shape = RoundedCornerShape(8.dp),
                            brush = Brush.horizontalGradient(
                                colors = listOf(Color(0xFF6650a4), Color(0xFF2196F3))
                            )
                        )
                ) {
                    Text(
                        text = stringResource(R.string.login),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color.White
                    )
                }

                // 注册和忘记密码
                Row(
                    modifier = Modifier.padding(top = 30.dp),
                    horizontalArrangement = Arrangement.spacedBy(100.dp)
                ) {
                    TextButton(onClick = onRegister) {
                        Text(
                            text = stringResource(R.string.tel_register),
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    TextButton(onClick = onForgotPwd) {
                        Text(
                            text = stringResource(R.string.fgt_pwd),
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    /**
     * 输入框
     */
    @Composable
    fun CustomTextField(
        value: String,
        onValueChange: (String) -> Unit,
        hint: String,
        modifier: Modifier = Modifier,
        keyboardType: KeyboardType = KeyboardType.Text,
        visualTransformation: VisualTransformation = VisualTransformation.None,
        maxLength: Int = Int.MAX_VALUE
    ) {
        TextField(
            value = value,
            onValueChange = {
                if (it.length <= maxLength) onValueChange(it)
            },
            placeholder = { Text(hint, color = Color.White.copy(alpha = 0.7f)) },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = visualTransformation,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                cursorColor = Color.White,
                focusedIndicatorColor = Color.White,
                unfocusedIndicatorColor = Color.White.copy(alpha = 0.5f)
            ),
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(color = Color.White),
            modifier = modifier
                .background(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.White.copy(alpha = 0.2f)
                )
        )
    }
}
