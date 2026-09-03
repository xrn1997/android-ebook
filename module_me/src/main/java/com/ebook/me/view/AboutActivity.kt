package com.ebook.me.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.DisplayMetrics
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ebook.common.event.KeyCode
import com.ebook.common.ui.CommonCard
import com.ebook.common.ui.CommonListDivider
import com.ebook.common.ui.CommonListItem
import com.ebook.common.ui.SectionLabel
import com.ebook.me.R
import com.therouter.TheRouter
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseActivity

/**
 * 关于页：App 信息卡（图标/名称/版本/slogan）+ 内容入口（用户协议/隐私政策/开源许可）+ 版权。
 *
 * 设置页「关于我们」跳转到此。协议与政策为本地静态文本（项目无后端），
 * 版本动态读取 PackageManager——独立运行与集成模式各自显示宿主的实际信息。
 */
@Route(path = KeyCode.Me.ABOUT_PATH)
class AboutActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        toolbarTitle.value = getString(R.string.about_title)
    }

    @Composable
    override fun PageContent() {
        val context = LocalContext.current
        // App 信息三件套纯静态读取（名称/图标/版本），无状态不进 ViewModel
        val appInfo = remember {
            runCatching {
                val pm = context.packageManager
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                Triple(
                    pm.getApplicationLabel(context.applicationInfo).toString(),
                    context.applicationInfo.icon,
                    info.versionName ?: "",
                )
            }.getOrDefault(Triple("", 0, ""))
        }
        // launcher 图标是 AdaptiveIconDrawable（XML），painterResource 不支持，
        // 先栅格化为 Bitmap 再交给 Compose Image
        val iconBitmap = remember(appInfo.second) {
            rasterizeIcon(context, appInfo.second)
        }

        AboutScreen(
            appName = appInfo.first,
            appIcon = iconBitmap,
            versionName = appInfo.third,
            onOpenDoc = { docType ->
                TheRouter.build(KeyCode.Me.DOC_PATH)
                    .withInt(DocActivity.EXTRA_DOC_TYPE, docType)
                    .navigation()
            },
            onOpenLicenses = {
                TheRouter.build(KeyCode.Me.LICENSES_PATH).navigation()
            }
        )
    }

    /**
     * 将任意 Drawable（含 AdaptiveIconDrawable）栅格化为 [ImageBitmap]。
     *
     * 固定 256px（大于 88dp 展示尺寸的任何屏幕密度），读取失败返回 null（图标缺省不渲染）。
     */
    private fun rasterizeIcon(context: android.content.Context, iconRes: Int): ImageBitmap? {
        if (iconRes == 0) return null
        return runCatching {
            val drawable = context.resources.getDrawableForDensity(
                iconRes,
                DisplayMetrics.DENSITY_XXXHIGH,
                context.theme,
            ) ?: return null
            val size = 256
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            bitmap.asImageBitmap()
        }.getOrNull()
    }
}

/**
 * 关于页内容：App 信息卡 + 内容入口卡 + 底部版权。
 */
@Composable
private fun AboutScreen(
    appName: String,
    appIcon: ImageBitmap?,
    versionName: String,
    onOpenDoc: (Int) -> Unit,
    onOpenLicenses: () -> Unit
) {
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
            Spacer(modifier = Modifier.height(16.dp))

            // App 信息卡：图标 + 名称 + 版本 + slogan（版本与检查更新入口在设置页，此处纯展示）
            CommonCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (appIcon != null) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            shadowElevation = 4.dp
                        ) {
                            Image(
                                bitmap = appIcon,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(88.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = appName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.about_version_prefix, versionName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.about_slogan),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            SectionLabel(text = stringResource(R.string.about_section_content))
            CommonCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    CommonListItem(
                        icon = Icons.Outlined.Description,
                        title = stringResource(R.string.about_user_agreement),
                        iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        onClick = { onOpenDoc(DocActivity.DOC_TYPE_USER_AGREEMENT) }
                    )
                    CommonListDivider()
                    CommonListItem(
                        icon = Icons.Outlined.Security,
                        title = stringResource(R.string.about_privacy_policy),
                        iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        iconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        onClick = { onOpenDoc(DocActivity.DOC_TYPE_PRIVACY_POLICY) }
                    )
                    CommonListDivider()
                    CommonListItem(
                        icon = Icons.Outlined.Code,
                        title = stringResource(R.string.about_open_source_license),
                        iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        iconContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        onClick = onOpenLicenses
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f, fill = false))

            // 底部版权：贴底弱化展示
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.about_copyright),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
