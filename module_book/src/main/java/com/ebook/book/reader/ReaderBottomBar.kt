package com.ebook.book.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ebook.book.R
import kotlin.math.roundToInt

/**
 * 阅读器底栏（对齐原 ll_menu_bottom）：
 * 章节进度文本 / 上一章 / 章节滑条 / 下一章 + 目录/亮度/字体/设置四入口。
 *
 * 相对原快照的实现改进：
 * - 「上一章/下一章」由裸文字点击区改为圆形图标按钮——原文字热区仅 30dp 上下，
 *   低于 48dp 最小可点击尺寸；图标 + 禁用态淡出让"能不能点"一眼可辨
 * - 新增章节进度文本行：拖动滑条时数字实时跟手，补齐"拖到哪一章"的反馈
 * - 章节滑条改用自绘 [ReaderSlider]：Material3 新版滑条的竖条手柄在这条紧凑行里过重，
 *   与两端按钮、文字抢视线（详见该组件 KDoc）
 * - 四入口改胶囊底色高亮，并用 [activePanel] 标记当前已打开的面板，
 *   避免"面板被下拉把手遮住后看不出是哪个入口开的"
 *
 * 滑条语义对齐原 MHorProgressBar：拖动实时跟手，抬手取整跳章；
 * 首末章禁用对应上一章/下一章按钮。
 *
 * edge-to-edge 避让：底栏贴屏底，需自行避让手势条/导航栏（与顶栏同理，
 * padding 写在 background 内层：背景延伸到导航栏后面，内容上移避让）。
 */
@Composable
internal fun ReaderBottomBar(
    chapterAll: Int,
    sliderValue: Float,
    activePanel: ReaderPanel,
    onSliderChange: (Float) -> Unit,
    onSliderFinished: () -> Unit,
    prevEnabled: Boolean,
    nextEnabled: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onCatalog: () -> Unit,
    onLight: () -> Unit,
    onFont: () -> Unit,
    onSetting: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 滑条值即 1-based 章序号；无章节时给出明确文案，避免"第 1 章 · 共 0 章"的矛盾表述
    val progressText = if (chapterAll <= 0) {
        stringResource(R.string.no_chapter)
    } else {
        stringResource(
            R.string.reader_chapter_progress_format,
            sliderValue.roundToInt().coerceIn(1, chapterAll),
            chapterAll
        )
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            // surfaceContainer：与正文背景、顶栏同调，形成上下对称的"菜单层"
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .navigationBarsPadding()
    ) {
        Text(
            text = progressText,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChapterStepButton(
                icon = Icons.Outlined.ChevronLeft,
                description = stringResource(R.string.last_chapter),
                enabled = prevEnabled,
                onClick = onPrevChapter
            )
            ReaderSlider(
                value = sliderValue,
                valueRange = 1f..chapterAll.coerceAtLeast(1).toFloat(),
                onValueChange = onSliderChange,
                onValueChangeFinished = onSliderFinished,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
                contentDescription = stringResource(R.string.reader_chapter_slider)
            )
            ChapterStepButton(
                icon = Icons.Outlined.ChevronRight,
                description = stringResource(R.string.next_chapter),
                enabled = nextEnabled,
                onClick = onNextChapter
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(top = 2.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            ReaderBottomEntry(
                Icons.AutoMirrored.Outlined.ListAlt,
                stringResource(R.string.catalogue),
                activePanel == ReaderPanel.CHAPTER,
                onCatalog,
                Modifier.weight(1f)
            )
            ReaderBottomEntry(
                Icons.Outlined.BrightnessMedium,
                stringResource(R.string.luminance),
                activePanel == ReaderPanel.LIGHT,
                onLight,
                Modifier.weight(1f)
            )
            ReaderBottomEntry(
                Icons.Outlined.TextFields,
                stringResource(R.string.font),
                activePanel == ReaderPanel.FONT,
                onFont,
                Modifier.weight(1f)
            )
            ReaderBottomEntry(
                Icons.Outlined.Settings,
                stringResource(R.string.setting),
                activePanel == ReaderPanel.SETTING,
                onSetting,
                Modifier.weight(1f)
            )
        }
    }
}

/**
 * 章节快进/快退按钮（上一章 / 下一章）。
 *
 * 44dp IconButton 保证触摸目标达标，内部再画 30dp 圆形色块承载图标：
 * 色块给出"这是个按钮"的可点性暗示，禁用时整块淡出，比原先"文字变灰"更明确。
 */
@Composable
private fun ChapterStepButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    // 禁用态用 M3 的内容禁用透明度（0.38），与同一批面板里的其它禁用态（如字体/亮度块的
    // 滑块）取同一个值——同为"禁用灰"却有 0.32/0.38 两种是没有理由的分叉
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp)) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(
                    if (enabled) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                modifier = Modifier.size(20.dp),
                tint = contentColor
            )
        }
    }
}

/**
 * 底栏功能入口（矢量图标 + 文案，原 ll_catalog 等四联排结构）。
 *
 * 图标统一 Material 矢量 + 语义着色（替代原位图，符合全项目禁位图图标约定）。
 * 选中态用 `secondaryContainer` 胶囊底 + `onSecondaryContainer` 前景，
 * 与 M3 NavigationBar 的选中语义一致；未选中保持透明底，避免四个入口同时"亮"。
 *
 * 等宽分配由调用侧在 RowScope 内传 [Modifier]（weight 为作用域修饰符，
 * 不能在本函数内部直接使用）。
 */
@Composable
private fun ReaderBottomEntry(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contentColor = if (active) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .padding(3.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(22.dp),
            tint = contentColor
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor
        )
    }
}
