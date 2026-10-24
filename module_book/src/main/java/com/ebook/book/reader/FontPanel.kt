package com.ebook.book.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ebook.book.R
import com.ebook.book.view.ReadBookControl
import com.ebook.common.ui.CommonCard
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.SectionLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FontPanel(
    onDismiss: () -> Unit
) {
    // 档位直接取可观察快照：面板与正文共用同一份设置，不再各存一份本地镜像
    val settings by ReadBookControl.settings.collectAsStateWithLifecycle()
    val textKindIndex = settings.textKindIndex
    val textDrawableIndex = settings.textDrawableIndex
    val kindList = ReadBookControl.getTextKindList()
    val drawableList = ReadBookControl.getTextDrawableList()

    // dragHandle = null：同亮度面板，标题已表明用途、关闭途径齐全，去掉手柄横杠
    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = null) {
        Column(
            modifier = Modifier
                .padding(horizontal = CommonUiTokens.pagePadding)
                .padding(top = ReaderChromeTokens.sheetTopPadding)
        ) {
            SheetHeader(
                title = stringResource(R.string.font),
                // 恢复默认字号：已是默认档时禁用（避免"点了没反应"的错觉）
                trailing = {
                    TextButton(
                        onClick = {
                            ReadBookControl.updateTextKindIndex(ReadBookControl.DEFAULT_TEXT)
                        },
                        enabled = textKindIndex != ReadBookControl.DEFAULT_TEXT
                    ) {
                        Text(stringResource(R.string.font_default))
                    }
                }
            )
            Spacer(modifier = Modifier.height(CommonUiTokens.sectionSpacing))
            SectionLabel(stringResource(R.string.font_size))
            CommonCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    kindList.forEachIndexed { index, kind ->
                        FontSizeChip(
                            label = kind.textSize.toString(),
                            selected = index == textKindIndex,
                            modifier = Modifier.weight(1f),
                        ) {
                            ReadBookControl.updateTextKindIndex(index)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(CommonUiTokens.sectionSpacing))
            SectionLabel(stringResource(R.string.reader_theme))
            CommonCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    drawableList.forEachIndexed { index, item ->
                        ReaderThemeSwatch(
                            item = item,
                            selected = index == textDrawableIndex,
                            modifier = Modifier.weight(1f),
                        ) {
                            ReadBookControl.updateTextDrawableIndex(index)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(ReaderChromeTokens.sheetBottomPadding))
        }
    }
}

/**
 * 字号档位胶囊：等宽排布，选中走主色底 + onPrimary 字。
 *
 * 高度固定 34dp，让八个数字形成一条明确的"刻度尺"，比原 3 按钮 + 1 数值更易读全貌。
 */
@Composable
private fun FontSizeChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 阅读主题色卡：以主题的背景色画一块"纸片"，纸片中央用主题正文色写预览字。
 *
 * 预览字取主题自身配色（[ReadBookControl.TextDrawable]）——它画在纸上，属阅读背景主题层；
 * 描边则取当前调板的 outlineVariant：描边压在「纸片与面板的交界」上，可见性由面板决定，
 * 拿主题自身前景色去描会在深色面板上把夜间色卡整块融进背景（素白配浅色面板同理）。
 * 未选中 1dp 弱描边保证近白色主题仍有可辨边界，
 * 选中时 2dp 主色描边 + 主题名转主色，双重编码避免只靠颜色表达状态。
 */
@Composable
private fun ReaderThemeSwatch(
    item: ReadBookControl.TextDrawable,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val background = Color(item.textBackground)
    val foreground = Color(item.textColor)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(background)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.theme_preview_text),
                color = foreground,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(item.labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
