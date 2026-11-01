package com.ebook.me.view

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ebook.api.config.ServerAddress
import com.ebook.common.event.KeyCode
import com.ebook.common.ui.CommonUiTokens
import com.ebook.me.R
import com.ebook.me.mvvm.viewmodel.ServerAddressSaveOutcome
import com.ebook.me.mvvm.viewmodel.SettingViewModel
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseMvvmActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * 服务器地址页（设置 → 服务器地址）：运行期覆盖 ebook-server 基址（ADR-0052）。
 *
 * 形态对齐书源管理等设置二级页：带箭头的行「点进去」编辑。地址、明文风险与局域网说明
 * 都是本页要看的内容，不收弹层；设置页的行上**不显示地址**——服务器地址不是可以随便
 * 亮在锁屏/录屏里的东西，完整值得进本页看。
 *
 * 保存/清除配置即换址：写盘成功后清会话并弹不可取消的重启提示（[ServerAddressRestartDialog]），
 * 可见性由 [SettingViewModel.serverAddressState] 的 `pendingRestart` 驱动——转屏/离页绕不过。
 * 写盘在途与待重启期间吞掉返回（工具栏箭头与系统返回都走 OnBackPressedDispatcher）：
 * 不给「地址已换、会话未清」留出口，也不给「先返回、稍后重启」留窗口（ADR-0052 决策 3）。
 *
 * 复用 [SettingViewModel] 而非另立 VM：地址状态与保存/清除配置的四档处置本来就住在那里
 * （`SettingViewModelTest` 已锁），本页只是它的第二个宿主；另立 VM 只会把同一份逻辑搬家。
 */
@AndroidEntryPoint
@Route(path = KeyCode.Me.SERVER_ADDRESS_PATH)
class ServerAddressActivity : BaseMvvmActivity<SettingViewModel>() {
    override val viewModel: SettingViewModel by viewModels()

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        toolbarTitle.value = getString(R.string.setting_server_address)
    }

    @Composable
    override fun PageContent() {
        val state by viewModel.serverAddressState.collectAsState()
        val isBusy by viewModel.addressChangeInProgress.collectAsState()

        ServerAddressScreen(
            currentDisplay = state.display,
            isConfigured = state.isConfigured,
            pendingRestart = state.pendingRestart,
            isBusy = isBusy,
            onSave = viewModel::saveServerAddress,
            onReset = viewModel::resetServerAddress,
            onRestartApp = { restartApp(this) },
        )
    }
}

/**
 * 服务器地址页内容：提示 + 地址输入 + 明文警告 + 保存/清除配置。
 *
 * 无状态根：只吃状态与回调（与 [SettingScreen] 同形态，便于渲染测试锁契约）。
 * 合法性由 ViewModel 判定后经 [ServerAddressSaveOutcome] 回传，行内错误就地显示；
 * 成功不在此处收尾——重启提示由 [pendingRestart] 状态驱动，页面不需要「关窗」动作。
 * [currentDisplay] 为 null = 未配置（应用不带默认地址，ADR-0053）：输入框从空起、
 * 只有用户自己填的值会出现在页面上。
 */
@Composable
fun ServerAddressScreen(
    currentDisplay: String?,
    isConfigured: Boolean,
    pendingRestart: Boolean,
    isBusy: Boolean,
    onSave: (String, (ServerAddressSaveOutcome) -> Unit) -> Unit,
    onReset: ((ServerAddressSaveOutcome) -> Unit) -> Unit,
    onRestartApp: () -> Unit,
) {
    // rememberSaveable：转屏后输入与错误态不被打回预填值
    var input by rememberSaveable { mutableStateOf(currentDisplay.orEmpty()) }
    var inputError by rememberSaveable { mutableStateOf(false) }
    var saveError by rememberSaveable { mutableStateOf(false) }
    var showResetConfirm by rememberSaveable { mutableStateOf(false) }
    // 明文警告按当前输入实时派生：输入合法且为非回环 http 才提示
    val cleartextWarning = remember(input) {
        ServerAddress.parse(input)?.isCleartextToRemoteHost == true
    }

    // 保存/清除配置的四档处置：两档错误进输入框行内，其余交给状态
    // （Unchanged 静默——地址没变没什么可说；Saved/InProgress 由按钮禁用与重启提示表达）
    val handleOutcome: (ServerAddressSaveOutcome) -> Unit = { outcome ->
        when (outcome) {
            ServerAddressSaveOutcome.Invalid -> {
                inputError = true
                saveError = false
            }
            ServerAddressSaveOutcome.Failed -> {
                inputError = false
                saveError = true
            }
            ServerAddressSaveOutcome.InProgress,
            ServerAddressSaveOutcome.Unchanged,
            ServerAddressSaveOutcome.Saved -> Unit
        }
    }

    // 写盘在途与待重启期间不允许离开：收尾一旦开始就不给「地址已换、会话未清」留出口，
    // 不可取消的重启提示也不该被「先返回再慢慢看」绕过
    BackHandler(enabled = isBusy || pendingRestart) { }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CommonUiTokens.pagePadding, vertical = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.setting_server_address_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    // 用户开始改输入即撤回上一次的行内错误：错误说的是「刚才那串不合法」，
                    // 不随输入清掉会让合法的新值也一直顶着红框
                    inputError = false
                    saveError = false
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = inputError || saveError,
                enabled = !isBusy,
                placeholder = { Text(stringResource(R.string.setting_server_address_placeholder)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                label = { Text(stringResource(R.string.setting_server_address)) },
                supportingText = {
                    Text(
                        when {
                            saveError -> stringResource(R.string.setting_server_address_save_failed)
                            inputError -> stringResource(R.string.setting_server_address_invalid)
                            isBusy -> stringResource(R.string.setting_server_address_saving)
                            else -> stringResource(R.string.setting_server_address_lan_note)
                        }
                    )
                },
            )
            if (cleartextWarning && !inputError && !saveError && !isBusy) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.setting_server_address_cleartext_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = { onSave(input, handleOutcome) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isBusy,
            ) {
                Text(stringResource(R.string.setting_server_address_save))
            }
            if (isConfigured) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showResetConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isBusy,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                ) {
                    Text(stringResource(R.string.setting_server_address_reset))
                }
            }
        }
    }

    if (showResetConfirm) {
        ServerAddressResetConfirmDialog(
            isBusy = isBusy,
            onConfirm = {
                showResetConfirm = false
                onReset(handleOutcome)
            },
            onDismiss = { showResetConfirm = false },
        )
    }

    // 不可取消：旧地址下重新登录会把旧服务器的凭证带到新地址，没有「稍后」可言
    if (pendingRestart) {
        ServerAddressRestartDialog(onRestart = onRestartApp)
    }
}
