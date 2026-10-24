package com.ebook.book.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ebook.book.R
import com.ebook.book.view.ReadBookControl
import com.ebook.common.ui.CommonCard
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.SectionLabel

/**
 * 更多设置面板（替代原 MoreSettingPop）：翻页方式选择 + 按键翻页 / 点击翻页开关。
 *
 * 翻页方式（互斥二选一）单独一张卡，与下面的两个开关分开：「选哪一档」与
 * 「某个输入能不能翻页」是两类设置，单选钮与开关并排会让人以为单选也是一种开关。
 *
 * 开关行收进 [CommonCard] 分组并补齐说明文案（对齐共享设计语言，ADR-0006）；
 * 行间分割线按本面板的图标列缩进（[ReaderChromeTokens.switchDividerIndent]），
 * 不用 CommonListDivider 的 64dp——本卡开关行的内边距基准与 CommonListItem 不同。
 *
 * @param onTurnModeChanged 翻页方式即时回调，传的是翻页方式索引（0 = 左右翻页、1 = 上下滚屏，
 *   口径同 `ReadBookControl.turnModeIndex`）。开关与选中态由 `ReadBookControl.settings` 驱动，
 *   唯独换模式仍需回调宿主：新容器要等重组后才成形，落点换算要等新视口回报（见 ReadBookScreen
 *   的 `pendingTurnModeSwitch`），这段时序面板自己做不了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreSettingPanel(
    onDismiss: () -> Unit,
    onTurnModeChanged: (Int) -> Unit,
) {
    // 开关与模式直接取可观察快照：写入即推进，面板不再维护本地镜像；
    // 翻页方式仍需回调宿主（换模式要快照旧行数并同步导航判据，见 ReadBookScreen）。
    val settings by ReadBookControl.settings.collectAsStateWithLifecycle()
    val canKeyTurn = settings.canKeyTurn
    val canClickTurn = settings.canClickTurn
    val turnModeIndex = settings.turnModeIndex
    // dragHandle = null：同亮度面板，标题已表明用途、关闭途径齐全，去掉手柄横杠
    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = null) {
        Column(
            modifier = Modifier
                .padding(horizontal = CommonUiTokens.pagePadding)
                .padding(top = ReaderChromeTokens.sheetTopPadding)
        ) {
            SheetHeader(stringResource(R.string.setting))
            Spacer(modifier = Modifier.height(CommonUiTokens.sectionSpacing))
            SectionLabel(stringResource(R.string.reader_section_turn))
            CommonCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    PanelChoiceRow(
                        icon = Icons.Outlined.SwapHoriz,
                        label = stringResource(R.string.turn_mode_page),
                        description = stringResource(R.string.turn_mode_page_desc),
                        selected = turnModeIndex == 0,
                        onSelect = {
                            ReadBookControl.updateTurnModeIndex(0)
                            onTurnModeChanged(0)
                        },
                        iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = ReaderChromeTokens.switchDividerIndent),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    PanelChoiceRow(
                        icon = Icons.Outlined.SwapVert,
                        label = stringResource(R.string.turn_mode_scroll),
                        description = stringResource(R.string.turn_mode_scroll_desc),
                        selected = turnModeIndex == 1,
                        onSelect = {
                            ReadBookControl.updateTurnModeIndex(1)
                            onTurnModeChanged(1)
                        },
                        iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        iconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Spacer(modifier = Modifier.height(CommonUiTokens.sectionSpacing))
            CommonCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    PanelSwitchRow(
                        icon = Icons.AutoMirrored.Outlined.VolumeUp,
                        label = stringResource(R.string.volume_key_turn),
                        description = stringResource(R.string.volume_key_turn_desc),
                        checked = canKeyTurn,
                        onCheckedChange = {
                            ReadBookControl.setCanKeyTurn(it)
                        },
                        iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        iconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = ReaderChromeTokens.switchDividerIndent),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    PanelSwitchRow(
                        icon = Icons.Outlined.TouchApp,
                        label = stringResource(R.string.click_turn),
                        description = stringResource(R.string.click_turn_desc),
                        checked = canClickTurn,
                        onCheckedChange = {
                            ReadBookControl.setCanClickTurn(it)
                        },
                        iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        iconContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            Spacer(modifier = Modifier.height(ReaderChromeTokens.sheetBottomPadding))
        }
    }
}
