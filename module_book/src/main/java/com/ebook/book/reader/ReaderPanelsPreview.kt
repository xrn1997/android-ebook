package com.ebook.book.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.ebook.book.R
import com.ebook.common.ui.preview.AppPreview

/**
 * 预览：亮度面板的四种长相——**跟随系统** / 手动 128 档 / 手动 0 档 / 手动 255 档。
 *
 * 这四档全是「不崩、不报错，只是说不对一句话或点不动一下」的形态：
 * - 「自动」与百分比各走各的分支：写死其中一条不会红，只会让跟随系统时屏幕上还挂着一个
 *   可调数字（用户就会去拖一根实际不起作用的滑条），或让手动态只说「自动」。
 *   注：数值文本直接打的是 0~255 那个整数带 `%` 后缀（`reader_brightness_percent_format`，
 *   原实现的口径，本次没有改动），所以 255 那一张上看到的是「255%」而不是「100%」。
 * - 滑条的禁用态由淡出表达（`enabled = !followSystem`）：传反了就是「跟随系统开着、
 *   滑条却还能拖」，拖完又被系统亮度覆盖，看上去像滑条坏了。
 * - 最后两张是端点：本仓滑条是自绘的（触点→数值按固定基准映射，见 [ReaderSlider]），
 *   旋钮在 0 与 255 处是否贴到轨道两端只有拍出来量得准——画歪一档的症状是「拖不到底」，
 *   数值与屏幕亮度本身仍然对得上，所以功能上看不出毛病。
 *
 * 深浅两档各一组：数值行的主色/次要色（`primary` 与 `onSurfaceVariant`）随调板换，
 * 硬编码在深色档会糊成一片。
 */
@PreviewLightDark
@Composable
private fun LightPanelContentPreview() {
    AppPreview {
        Column {
            // 进面板的默认档：滑条不可动，右侧说"自动"
            LightPanelContent(
                brightness = 204,
                followSystem = true,
                onBrightnessChange = {},
                onSystemBrightnessRestore = {},
                onFollowSystemChange = {},
            )
            LightPanelContent(
                brightness = 128,
                followSystem = false,
                onBrightnessChange = {},
                onSystemBrightnessRestore = {},
                onFollowSystemChange = {},
            )
            LightPanelContent(
                brightness = 0,
                followSystem = false,
                onBrightnessChange = {},
                onSystemBrightnessRestore = {},
                onFollowSystemChange = {},
            )
            LightPanelContent(
                brightness = 255,
                followSystem = false,
                onBrightnessChange = {},
                onSystemBrightnessRestore = {},
                onFollowSystemChange = {},
            )
        }
    }
}

/**
 * 预览：设置面板的单选行三档——**已选中** / 未选中 / 文案长到要换行。
 *
 * 「已选中行不可再点」是这一行的全部语义（`clickable(enabled = !selected, ...)` 与尾部
 * [RadioButton] 共用 `onSelect`）：写反了不报错，只会变成「选中的那档还能点、没选的那档点不动」，
 * 而翻页方式这种互斥二选一一旦点歪，界面与实际翻页行为就分叉了。
 * 最后一档看的是长标签把尾部单选钮挤不挤出可视区（单选钮是唯一的"当前选了哪档"的凭据，
 * 被挤出去就等于整行没有结论），以及说明行换行后与 36dp 图标块的垂直关系。
 *
 * 底色是否真的取自当前调板由 `ReaderTurnModeRowRenderTest` 在像素层锁住；这两张只看排版。
 */
@PreviewLightDark
@Composable
private fun PanelChoiceRowPreview() {
    AppPreview {
        Column {
            // 与 MoreSettingPanel 里那两行同色同文（primary / secondary 两档容器色）
            PanelChoiceRow(
                icon = Icons.Outlined.SwapHoriz,
                label = stringResource(R.string.turn_mode_page),
                description = stringResource(R.string.turn_mode_page_desc),
                selected = true,
                onSelect = {},
                iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            PanelChoiceRow(
                icon = Icons.Outlined.SwapVert,
                label = stringResource(R.string.turn_mode_scroll),
                description = stringResource(R.string.turn_mode_scroll_desc),
                selected = false,
                onSelect = {},
                iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                iconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            PanelChoiceRow(
                icon = Icons.AutoMirrored.Outlined.VolumeUp,
                label = "一条足够长的标签用来看它会不会把右侧的单选钮挤走",
                description = "说明文字同样给到换行的长度：两行文案与 36dp 图标块、尾部单选钮同行时，" +
                    "行高与垂直居中只能在这张图上量得准",
                selected = false,
                onSelect = {},
            )
        }
    }
}
