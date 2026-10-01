package com.ebook.me.view

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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.ebook.common.event.KeyCode
import com.ebook.common.ui.CommonCard
import com.ebook.common.ui.CommonListDivider
import com.ebook.common.ui.CommonListItem
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.SectionLabel
import com.ebook.common.ui.preview.AppPreview
import com.ebook.me.R
import com.therouter.TheRouter
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseActivity
import androidx.core.graphics.createBitmap

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
            val bitmap = createBitmap(size, size)
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
                // 页面左右留白取统一令牌（唯一事实源），不在页内写同语义的 16dp 字面值
                .padding(horizontal = CommonUiTokens.pagePadding)
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

            // App 信息卡与内容入口卡之间的区块间距取统一令牌：与设置页/书城共用同一密度
            Spacer(modifier = Modifier.height(CommonUiTokens.sectionSpacing))

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

/**
 * 预览：关于页常规形态（App 图标 + 名称 + 版本 + 三个内容入口）。
 *
 * 图标给一张 88px 的**空位图**而不是真 launcher 图：launcher 图标是 AdaptiveIconDrawable（XML），
 * 栅格化发生在 Activity 侧的 `rasterizeIcon`，预览环境既没有 PackageManager 也没有可解那份 XML 的主题。
 * 这里要核对的是「图标槽位存在时」整张卡是否稳定——名称/版本/slogan 三行的间距与卡片高度都随
 * 图标在不在而变，那一档只在装机态看得到，编译更看不出。深浅两档一起出（[PreviewLightDark] 翻
 * `uiMode`，与运行时「跟随系统」同一个判定）。
 */
@PreviewLightDark
@Composable
private fun AboutScreenPreview() {
    AppPreview {
        AboutScreen(
            appName = "小说",
            appIcon = createBitmap(88, 88).asImageBitmap(),
            versionName = "1.4.0",
            onOpenDoc = {},
            onOpenLicenses = {},
        )
    }
}

/**
 * 预览：App 信息读不出来时的退化形态（无图标 + 名称与版本都是空串）。
 *
 * 这一档不是假想：`PageContent` 里那句 `getOrDefault(Triple("", 0, ""))` 就是它的生产入口
 * （`getPackageInfo` 抛异常时名称、版本一起为空、图标 resId 为 0 → `rasterizeIcon` 直接返回 null）。
 * 空串不会崩、也不会报错，只会画出一张「上面一片空白 + 版本行只剩前缀 v」的卡——
 * 不拍下来就没人知道它长什么样，改布局时也容易把兜底当成不会走到的分支。
 */
@Preview(showBackground = true, widthDp = 360)
@Composable
private fun AboutScreenWithoutIconPreview() {
    AppPreview {
        AboutScreen(
            appName = "",
            appIcon = null,
            versionName = "",
            onOpenDoc = {},
            onOpenLicenses = {},
        )
    }
}
