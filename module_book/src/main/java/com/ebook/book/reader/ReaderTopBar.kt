package com.ebook.book.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ModeComment
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ebook.book.R
import com.ebook.common.ui.CommonUiTokens

/**
 * 阅读器顶栏（对齐原 ll_menu_top：返回 + 章节标题 + 更多入口）。
 *
 * 视觉：`surfaceContainer` 底色 + 3dp 下沿投影，让菜单像"浮在正文之上的一层"，
 * 替代原先与正文同色、边界靠硬编码分割线维持的扁平观感。
 *
 * 标题居中并把书名作为副标题常驻：阅读器顶栏的标题是"当前章节"，读者更关心
 * "我在读哪本书的哪一章"，两级信息同屏可省去返回详情页确认的成本。
 * 标题单行省略（原 AutofitTextView 的自缩放在 Compose 无等价物，以省略号替代，
 * 章节名场景可接受）；"更多"仅非本地书显示下载入口时可见。
 *
 * 下载入口只有一个：下发任务统一带强制刷新标记，勾中已缓存章节即等价"刷新缓存"，
 * 故不再单列"强制刷新缓存"菜单项（该能力本就与下载共用同一条流水线）。
 *
 * 「换源」（ADR-0016 决策 8，P3-d）与下载/刷新同在非本地书分支：换源是把这本书的归属从 A 源
 * 搬到 B 源，本地书没有归属源这个概念（仓库层以参数错误拒绝），入口对本地书出现即等于一个必败按钮。
 *
 * edge-to-edge 避让：阅读页 enableFitsSystemWindows=false，顶栏需自行避让状态栏，
 * 否则返回键/标题会画到状态栏下面被遮挡。statusBarsPadding 写在底色内层：
 * 背景延伸到状态栏后面（视觉连续），内容下移避让。
 */
@Composable
fun ReaderTopBar(
    title: String,
    subtitle: String,
    isLocalBook: Boolean,
    onBack: () -> Unit,
    onDownload: () -> Unit,
    onRefresh: () -> Unit,
    onSwitchSource: () -> Unit,
    onComment: () -> Unit,
    modifier: Modifier = Modifier
) {
    var moreExpanded by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 避让修饰符位于固定高度（56dp Row）外层，整栏下移而非压缩内容，
                // 否则状态栏 insets 会从固定高度内扣除把内容压成零高（见踩坑记录）
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ReaderChromeTokens.barHeight)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(com.ebook.common.R.string.back),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                // 左右操作区同宽（48dp），中央列的剩余空间以屏幕中线对称 → 标题真正居中
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Box {
                    IconButton(onClick = { moreExpanded = true }) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.setting),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    // 更多菜单：本地书仅显示评论入口；网络书显示下载 + 刷新 + 评论
                    DropdownMenu(
                        expanded = moreExpanded,
                        onDismissRequest = { moreExpanded = false },
                        shape = RoundedCornerShape(CommonUiTokens.cardCornerSmall),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 3.dp
                    ) {
                        if (!isLocalBook) {
                            ReaderMenuItem(
                                icon = Icons.Outlined.Download,
                                text = stringResource(com.ebook.common.R.string.download),
                            ) {
                                moreExpanded = false
                                onDownload()
                            }
                            // 与同一菜单里的下载/换源/评论同为 Outlined：同一个菜单里混用实心与描边
                            // 会让"哪个是当前态"这类形状语义失效（返回箭头是例外，见文件头/顶栏注释）
                            ReaderMenuItem(
                                icon = Icons.Outlined.Refresh,
                                text = stringResource(R.string.refresh),
                            ) {
                                moreExpanded = false
                                onRefresh()
                            }
                            // 换源只对网络书有意义：本地书的正文在自己导入的文件里、tag 恒为 loc_book，
                            // 仓库层会以「本地书没有可换的书源」直接拒绝，所以入口跟着下载/刷新一起收进
                            // 非本地书分支，不给用户留一个点了必定失败的按钮
                            ReaderMenuItem(
                                icon = Icons.Outlined.SwapHoriz,
                                text = stringResource(R.string.switch_source),
                            ) {
                                moreExpanded = false
                                onSwitchSource()
                            }
                        }
                        ReaderMenuItem(
                            icon = Icons.Outlined.ModeComment,
                            text = stringResource(R.string.comment),
                        ) {
                            moreExpanded = false
                            onComment()
                        }
                    }
                }
            }
        }
    }
}

/**
 * 顶栏"更多"菜单项：前置图标 + 文本。
 *
 * 纯文字菜单项在两项时视觉重心偏右，加图标后与全 App 的图标化条目语言一致。
 */
@Composable
private fun ReaderMenuItem(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        onClick = onClick
    )
}
