package com.ebook.me.view

import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.ebook.common.event.KeyCode
import com.ebook.common.ui.preview.AppPreview
import com.ebook.me.R
import com.ebook.me.mvvm.viewmodel.ModifyViewModel
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseMvvmActivity
import dagger.hilt.android.AndroidEntryPoint

/** 昵称长度上限：与常见阅读 App 一致，防止过长破坏列表布局 */
private const val NICKNAME_MAX_LENGTH = 12

/**
 * 修改昵称页：表单式布局（当前昵称 + 新昵称输入 + 确认按钮）。
 *
 * 交互规则：
 * - 展示当前昵称作参照（来自 [ModifyViewModel.profileState]，修改成功返回后刷新）
 * - 输入校验：非空白、长度 ≤ [NICKNAME_MAX_LENGTH]，错误态在输入框下方提示
 * - 确认按钮仅在输入合法时可用；键盘 ImeAction.Done 直接提交
 * - 输入内容经 rememberSaveable 保留（旋转/进程重建不丢失）
 */
@AndroidEntryPoint
@Route(path = KeyCode.Me.MODIFY_NICKNAME_PATH, params = ["needLogin", "true"])
class ModifyNicknameActivity : BaseMvvmActivity<ModifyViewModel>() {
    override val viewModel: ModifyViewModel by viewModels()

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        toolbarTitle.value = getString(R.string.modify_nickname_title)
    }

    @Composable
    override fun PageContent() {
        val profileState by viewModel.profileState.collectAsState()
        ModifyNicknameScreen(
            currentNickname = profileState.nickname,
            onSubmit = { nickname -> viewModel.modifyNickname(nickname) }
        )
    }
}

/**
 * 修改昵称页内容。
 */
@Composable
fun ModifyNicknameScreen(
    currentNickname: String,
    onSubmit: (String) -> Unit
) {
    var nickname by rememberSaveable { mutableStateOf("") }

    // 校验：非纯空白且长度合规（trim 防止仅空格提交）
    val trimmed = nickname.trim()
    val isValid = trimmed.isNotEmpty() && trimmed.length <= NICKNAME_MAX_LENGTH

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // 当前昵称参照
            Text(
                text = stringResource(
                    R.string.modify_nickname_current,
                    currentNickname.ifEmpty { stringResource(R.string.common_not_set) }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = nickname,
                // 输入即截断到上限（硬限制），下方计数实时反馈
                onValueChange = { nickname = it.take(NICKNAME_MAX_LENGTH) },
                label = { Text(stringResource(R.string.new_nickname)) },
                isError = nickname.isNotEmpty() && trimmed.isEmpty(),
                supportingText = {
                    if (nickname.isNotEmpty() && trimmed.isEmpty()) {
                        Text(stringResource(R.string.modify_nickname_blank_error))
                    } else {
                        Text(stringResource(R.string.modify_nickname_counter, trimmed.length, NICKNAME_MAX_LENGTH))
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { if (isValid) onSubmit(trimmed) }
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { onSubmit(trimmed) },
                enabled = isValid,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.confirm_modify))
            }
        }
    }
}

/**
 * 预览：修改昵称页的两种「当前昵称」。
 *
 * 参照行是 `currentNickname.ifEmpty { common_not_set }`——首启/资料没填过的用户进来看到的是
 * 「当前昵称：未设置」。这一档不拍下来，只预览有昵称的那张图会一直以为那行有值，
 * 而 `profileState.nickname` 为空是登录链路里很常见的一态（服务端只存了邮箱、昵称留空）。
 *
 * 两档都是**输入框为空**的初始态：`nickname` 是 `rememberSaveable` 的本地状态，外部传不进来，
 * 于是这两张图同时把「按钮此时必须禁用」这一条一起看着（`isValid` 要求非空白）。
 */
@Preview(showBackground = true)
@Composable
private fun ModifyNicknameScreenPreview(
    @PreviewParameter(CurrentNicknameProvider::class) currentNickname: String,
) {
    AppPreview {
        ModifyNicknameScreen(
            currentNickname = currentNickname,
            onSubmit = {},
        )
    }
}

/** 两档参照值：有昵称 / 空串（走「未设置」占位） */
private class CurrentNicknameProvider : PreviewParameterProvider<String> {
    override val values: Sequence<String>
        get() = sequenceOf("临渊客", "")

    override fun getDisplayName(index: Int): String = if (index == 0) "已有昵称" else "未设置"
}
