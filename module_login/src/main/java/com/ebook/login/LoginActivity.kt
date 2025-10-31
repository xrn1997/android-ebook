package com.ebook.login

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
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseMvvmActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
@Route(path = KeyCode.Login.LOGIN_PATH)
class LoginActivity : BaseMvvmActivity<LoginViewModel>() {
    override val mViewModel: LoginViewModel by viewModels()

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
        // 状态管理
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            username = intent.getStringExtra("username").orEmpty()
            password = intent.getStringExtra("password").orEmpty()
            viewModel.bundle = intent.extras
        }
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
                    onValueChange = {
                        if (it.length <= 11) {
                            username = it
                        }
                    },
                    hint = stringResource(R.string.print_tel),
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 40.dp)
                )

                // 密码输入框
                CustomTextField(
                    value = password,
                    onValueChange = {
                        if (it.length <= 64) {
                            password = it
                        }
                    },
                    hint = stringResource(R.string.print_pwd),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardType = KeyboardType.Password,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )

                // 登录按钮
                Button(
                    onClick = { viewModel.login(username, password) },
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
                    TextButton(onClick = { toRegisterActivity() }) {
                        Text(
                            text = stringResource(R.string.tel_register),
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    TextButton(onClick = { toForgetPwdActivity() }) {
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
    ) {
        TextField(
            value = value,
            onValueChange = { onValueChange(it) },
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
