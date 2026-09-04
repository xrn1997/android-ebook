package com.ebook.me.view

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ebook.common.domain.AndroidUserSessionManager
import com.ebook.common.event.KeyCode
import com.ebook.common.ui.CommonCard
import com.ebook.common.ui.CommonListDivider
import com.ebook.common.ui.CommonListItem
import com.ebook.common.ui.InfoChip
import com.ebook.common.ui.SectionLabel
import com.ebook.me.R
import com.ebook.me.mvvm.viewmodel.SettingViewModel
import com.ebook.me.mvvm.viewmodel.UpdateState
import com.ebook.me.util.normalizeVersionTag
import com.therouter.TheRouter
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseMvvmActivity
import com.xrn1997.common.util.ToastUtil
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 设置页：通用（缓存管理）+ 关于（版本检查更新/关于我们）+ 账号（退出登录）。
 *
 * 缓存管理与关于内容可公开访问，无需登录；退出登录受 [AndroidUserSessionManager.isLoggedIn] 条件守卫，
 * 未登录时自动隐藏整个账号区块。
 *
 * 交互设计（箭头语义：带箭头=点进去看，不带=当场执行的动作）：
 * - 「清除缓存」带箭头跳缓存管理页（本页不直接清理）
 * - 「版本」为动作型条目（点击触发真实更新检查，无箭头）：主动检查即时弹结果
 *   （检查中/已是最新/发现新版本可下载/检查失败）；进页时另有 7 天限频的静默刷新，
 *   有新版时版本号旁展示「新版」角标（不弹窗）
 * - 「关于我们」带箭头进关于页（用户协议/隐私政策/开源许可）
 * - 「退出登录」为动作型条目（点击弹确认，无箭头）
 * - 深色模式跟随系统（MyApplicationTheme），阅读背景主题在阅读器内设置，
 *   此页不做重复入口，避免两处状态源
 */
@AndroidEntryPoint
@Route(path = KeyCode.Me.SETTING_PATH)
class SettingActivity : BaseMvvmActivity<SettingViewModel>() {
    override val viewModel: SettingViewModel by viewModels()

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        toolbarTitle.value = getString(R.string.setting_title)
    }

    override fun onResume() {
        super.onResume()
        // 缓存管理页返回后：入口行大小要与实际清理结果同步
        viewModel.refreshCacheSize()
        // 角标按「当前装机版本 vs 上次检查到的 tag」现场派生：用户可能装着新版本就回到本页，
        // 不重算会让「新版」一直挂着（VM 比页面活得久，构造时算一次不够）
        viewModel.refreshUpdateBadge()
    }

    @Composable
    override fun PageContent() {
        val cacheSize by viewModel.cacheSize.collectAsState()
        val isLoggedIn by viewModel.isLoggedIn.collectAsState()
        val updateState by viewModel.updateState.collectAsState()
        val hasUpdateAvailable by viewModel.hasUpdateAvailable.collectAsState()

        SettingScreen(
            cacheSize = cacheSize,
            // 版本号经 VM 转发：与「是否有新版」的比较基准同源（ReleaseStateStore）
            appVersion = viewModel.appVersionName,
            isLoggedIn = isLoggedIn,
            hasUpdateAvailable = hasUpdateAvailable,
            updateState = updateState,
            onCheckUpdate = {
                viewModel.checkUpdate()
            },
            onDismissUpdateDialog = {
                viewModel.consumeUpdateDialog()
            },
            onOpenCacheManage = {
                TheRouter.build(KeyCode.Me.CACHE_PATH).navigation()
            },
            onOpenAbout = {
                TheRouter.build(KeyCode.Me.ABOUT_PATH).navigation()
            },
            onLogout = {
                // 必须 await：finish() 后本作用域被取消，登出请求会没发出去。
                // 提示与关闭放在 await 之后，保证「已退出」出现时本地会话确实清干净
                lifecycleScope.launch {
                    viewModel.logout()
                    ToastUtil.showShort(
                        this@SettingActivity,
                        getString(R.string.setting_logout_success)
                    )
                    finish()
                }
            }
        )
    }
}

/**
 * 设置页内容：分组卡片 + 弹窗（检查更新/退出登录确认）。
 */
@Composable
fun SettingScreen(
    cacheSize: String,
    appVersion: String,
    isLoggedIn: Boolean,
    hasUpdateAvailable: Boolean,
    updateState: UpdateState,
    onCheckUpdate: () -> Unit,
    onDismissUpdateDialog: () -> Unit,
    onOpenCacheManage: () -> Unit,
    onOpenAbout: () -> Unit,
    onLogout: () -> Unit
) {
    var showLogoutDialog by remember { mutableStateOf(false) }
    // 缓存大小空串 = 计算中，占位文案经资源解析（VM 不持有用户可见文本）
    val cacheSizeDisplay = cacheSize.ifEmpty { stringResource(R.string.common_pending) }
    val context = LocalContext.current
    // 「下载」跳系统浏览器/下载器失败时的提示（供按钮 onClick 这种非 @Composable 上下文取用）；
    // 刻意不复用 setting_check_update_error：那是「检查」失败的文案，检查此时已成功
    val updateOpenFailedText = stringResource(R.string.setting_check_update_open_failed)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            SectionLabel(text = stringResource(R.string.setting_section_general))
            CommonCard(modifier = Modifier.fillMaxWidth()) {
                CommonListItem(
                    icon = Icons.Outlined.CleaningServices,
                    title = stringResource(R.string.setting_clear_cache),
                    trailingText = cacheSizeDisplay,
                    iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = onOpenCacheManage
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            SectionLabel(text = stringResource(R.string.setting_section_about))
            CommonCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    // 版本：动作型条目（点击触发检查更新），无箭头；有新版时版本号旁给「新版」角标
                    // CommonListItem 的 trailingContent 与 trailingText 互斥，故把版本号与角标一起放进 trailingContent
                    CommonListItem(
                        icon = Icons.Outlined.Info,
                        title = stringResource(R.string.setting_version),
                        trailingContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = appVersion,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (hasUpdateAvailable) {
                                    InfoChip(
                                        text = stringResource(R.string.setting_new_version_badge),
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                        },
                        showArrow = false,
                        iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        iconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        onClick = onCheckUpdate
                    )
                    CommonListDivider()
                    // 关于我们：导航型条目（进关于页），带箭头
                    CommonListItem(
                        icon = Icons.AutoMirrored.Outlined.HelpOutline,
                        title = stringResource(R.string.setting_about_us),
                        iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        iconContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        onClick = onOpenAbout
                    )
                }
            }

            // 未登录时隐藏账号区块（设置页无登录拦截，此处为唯一守卫）
            if (isLoggedIn) {
                Spacer(modifier = Modifier.height(20.dp))

                SectionLabel(text = stringResource(R.string.setting_section_account))
                CommonCard(modifier = Modifier.fillMaxWidth()) {
                    CommonListItem(
                        icon = Icons.AutoMirrored.Outlined.Logout,
                        title = stringResource(R.string.setting_logout),
                        showArrow = false,
                        iconContainerColor = MaterialTheme.colorScheme.errorContainer,
                        iconContentColor = MaterialTheme.colorScheme.onErrorContainer,
                        onClick = { showLogoutDialog = true }
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // 检查更新弹窗：按 updateState 语义分支渲染（不含 Idle——Idle 不展示弹窗）
    when (updateState) {
        UpdateState.Idle -> Unit
        UpdateState.Checking -> AlertDialog(
            onDismissRequest = onDismissUpdateDialog,
            title = { Text(stringResource(R.string.setting_check_update_title)) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                    Text(stringResource(R.string.setting_check_update_checking))
                }
            },
            confirmButton = {}
        )
        UpdateState.UpToDate -> AlertDialog(
            onDismissRequest = onDismissUpdateDialog,
            title = { Text(stringResource(R.string.setting_check_update_title)) },
            text = { Text(stringResource(R.string.setting_check_update_message, appVersion)) },
            confirmButton = {
                TextButton(onClick = onDismissUpdateDialog) {
                    Text(stringResource(R.string.common_confirm))
                }
            }
        )
        is UpdateState.HasUpdate -> {
            val result = updateState.result
            // tag 形如 "V1.2.0"，资源里已自带 v 前缀，故先归一化再插值（否则渲染成 "vV1.2.0"）
            val remoteVersion = normalizeVersionTag(result.remoteTag)
            val downloadUrl = result.apkDownloadUrl
            AlertDialog(
                onDismissRequest = onDismissUpdateDialog,
                title = { Text(stringResource(R.string.setting_check_update_found_title, remoteVersion)) },
                text = {
                    Column {
                        // 发布说明是远端文本，直接展示（不做 Markdown 渲染），无说明时兜底
                        Text(
                            result.body.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.setting_check_update_no_body)
                        )
                        if (downloadUrl == null) {
                            // 该次发布没带 APK 附件：不摆一个点了只会关窗的假下载按钮
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.setting_check_update_no_apk),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                confirmButton = {
                    if (downloadUrl != null) {
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW).apply {
                                        data = android.net.Uri.parse(downloadUrl)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            }.onFailure {
                                ToastUtil.showShort(context, updateOpenFailedText)
                            }
                            onDismissUpdateDialog()
                        }) {
                            Text(stringResource(R.string.setting_check_update_download))
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismissUpdateDialog) {
                        // 无可下载附件时只剩一个关闭动作，用「确定」比「取消」贴切
                        Text(
                            stringResource(
                                if (downloadUrl != null) R.string.common_cancel
                                else R.string.common_confirm
                            )
                        )
                    }
                }
            )
        }
        UpdateState.CheckError -> AlertDialog(
            onDismissRequest = onDismissUpdateDialog,
            title = { Text(stringResource(R.string.setting_check_update_title)) },
            text = { Text(stringResource(R.string.setting_check_update_error)) },
            confirmButton = {
                TextButton(onClick = onDismissUpdateDialog) {
                    Text(stringResource(R.string.common_confirm))
                }
            }
        )
    }

    // 退出登录二次确认：防止误触（清 token 不可逆，需重新登录）
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text(stringResource(R.string.setting_logout)) },
            text = { Text(stringResource(R.string.setting_logout_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    onLogout()
                }) {
                    Text(stringResource(R.string.common_logout), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}
