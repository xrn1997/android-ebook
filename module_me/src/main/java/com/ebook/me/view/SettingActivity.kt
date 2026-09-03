package com.ebook.me.view

import android.content.pm.PackageManager
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ebook.common.event.KeyCode
import com.ebook.common.ui.CommonCard
import com.ebook.common.ui.CommonListDivider
import com.ebook.common.ui.CommonListItem
import com.ebook.common.ui.SectionLabel
import com.ebook.me.R
import com.ebook.me.mvvm.viewmodel.SettingViewModel
import com.therouter.TheRouter
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseMvvmActivity
import com.xrn1997.common.util.ToastUtil
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * 设置页：通用（缓存管理）+ 关于（版本检查更新/关于我们）+ 账号（退出登录）。
 *
 * 缓存管理与关于内容可公开访问，无需登录；退出登录受 [isLoggedIn] 条件守卫，
 * 未登录时自动隐藏整个账号区块。
 *
 * 交互设计（箭头语义：带箭头=点进去看，不带=当场执行的动作）：
 * - 「清除缓存」带箭头跳缓存管理页（本页不直接清理）
 * - 「版本」为动作型条目（点击弹检查更新，无箭头）——尚未接入服务端更新检查能力，
 *   暂以「已是最新版本」占位，接入更新服务后替换为真实请求
 * - 「关于我们」带箭头进关于页（用户协议/隐私政策/开源许可）
 * - 「退出登录」为动作型条目（点击弹确认，无箭头）
 * - 深色模式跟随系统（MyApplicationTheme），阅读背景主题在阅读器内设置，
 *   此页不做重复入口，避免两处状态源
 */
@AndroidEntryPoint
@Route(path = KeyCode.Me.SETTING_PATH)
class SettingActivity : BaseMvvmActivity<SettingViewModel>() {
    protected override val viewModel: SettingViewModel by viewModels()

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        toolbarTitle.value = getString(R.string.setting_title)
    }

    override fun onResume() {
        super.onResume()
        // 从缓存管理页返回后刷新入口行大小文案（清理结果同步）
        viewModel.refreshCacheSize()
    }

    @Composable
    override fun PageContent() {
        val cacheSize by viewModel.cacheSize.collectAsState()
        val isLoggedIn by viewModel.isLoggedIn.collectAsState()
        val context = LocalContext.current
        // 版本号为纯静态展示，直接从 PackageManager 读取（无状态，不进 ViewModel）
        val appVersion = remember {
            try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
            } catch (e: PackageManager.NameNotFoundException) {
                ""
            }
        }

        SettingScreen(
            cacheSize = cacheSize,
            appVersion = appVersion,
            isLoggedIn = isLoggedIn,
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
    onOpenCacheManage: () -> Unit,
    onOpenAbout: () -> Unit,
    onLogout: () -> Unit
) {
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    // 缓存大小空串 = 计算中，占位文案经资源解析（VM 不持有用户可见文本）
    val cacheSizeDisplay = cacheSize.ifEmpty { stringResource(R.string.common_pending) }

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
                    // 版本：动作型条目（点击弹检查更新），无箭头
                    CommonListItem(
                        icon = Icons.Outlined.Info,
                        title = stringResource(R.string.setting_version),
                        trailingText = appVersion,
                        showArrow = false,
                        iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        iconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        onClick = { showUpdateDialog = true }
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

    // 检查更新：尚未接入服务端更新检查能力，固定「已是最新版本」占位提示
    if (showUpdateDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text(stringResource(R.string.setting_check_update_title)) },
            text = { Text(stringResource(R.string.setting_check_update_message, appVersion)) },
            confirmButton = {
                TextButton(onClick = { showUpdateDialog = false }) {
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
