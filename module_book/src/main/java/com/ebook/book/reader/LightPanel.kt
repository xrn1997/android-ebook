package com.ebook.book.reader

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrightnessHigh
import androidx.compose.material.icons.outlined.BrightnessLow
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ebook.book.R
import com.ebook.common.ui.CommonCard
import com.ebook.common.ui.CommonUiTokens
import kotlin.math.roundToInt

/**
 * 亮度面板的**壳层**（替代原 WindowLightPop）：把需要 Activity 的三件事留在这里——
 * 读 SP 初值、写窗口亮度、关闭时持久化；长相交给无状态根 [LightPanelContent]。
 *
 * `light` / `followSys` 两个本地状态仍归这一层：它们是面板的单一事实源（滑条、数值文本、
 * 持久化共用同一个 `light`，对齐原 WindowLightPop），根组件只看它们的当前值。
 * 把状态一起挪进根组件，关闭时就得从组件外面把值捞回来——那才是第二个事实源。
 *
 * 调用点（[com.ebook.book.reader.ReadBookActivity] 的面板分支）签名保持不变：
 * `LightPanel(activity = activity, onDismiss = ...)`。
 *
 * 面板的长相与联动判据见 [LightPanelContent]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LightPanel(activity: Activity, onDismiss: () -> Unit) {
    // 单一事实源：滑条、开关与持久化共用一份 state；口径（SP 键、系统兜底、窗口换算）归 ReaderBrightness
    var state by remember { mutableStateOf(ReaderBrightness.load(activity)) }

    ModalBottomSheet(
        onDismissRequest = {
            // 对齐原 dismiss()：保存亮度配置
            ReaderBrightness.save(activity, state)
            onDismiss()
        },
        // 面板自带标题且下滑/点遮罩/返回键均可关闭，默认拖动手柄横杠冗余，去掉
        dragHandle = null
    ) {
        LightPanelContent(
            brightness = state.light,
            followSystem = state.followSystem,
            // 滑条拖动与「取消跟随系统」共用这条出口：先落内存值、再立刻应用到窗口，
            // 不留"关闭后屏幕亮度不变、必须拖一下滑条才生效"的错位
            onBrightnessChange = { value ->
                state = state.copy(light = value)
                ReaderBrightness.applyToWindow(activity, value)
            },
            // 勾选「跟随系统」：窗口亮度交还系统（BRIGHTNESS_OVERRIDE_NONE），滑条随即进禁用态
            onSystemBrightnessRestore = { ReaderBrightness.followSystem(activity) },
            onFollowSystemChange = { follow -> state = state.copy(followSystem = follow) },
        )
    }
}

/**
 * 亮度面板的**无状态根**（docs/architecture.md「无状态根与预览」）：只吃当前值与三个出口，
 * 于是这块 chrome 第一次可以预览、也能被 JVM 渲染用例组合出来。
 *
 * 长相与判据（逐条对齐原 WindowLightPop）：
 * - 滑条实时上报亮度（仅"不跟随系统"时可达），两端低/高亮度图标锚定方向语义；
 *  滑条包在 [CommonCard] 内，与其他模块设置页的卡片分组语言一致（ADR-0006）
 * - 数值文本实时显示当前亮度百分比（跟随系统时显示"自动"），
 *   原面板只有滑条，"调到哪一档"只能靠肉眼观察屏幕变化
 * - 「跟随系统」开关从卡片外的裸行收进同一张卡（与开关行统一为图标行语言），
 *   开启后恢复 BRIGHTNESS_OVERRIDE_NONE；关闭立即应用当前手动亮度，
 *   避免"关闭后屏幕亮度不变、必须拖一下滑条才生效"的错位；
 *   Switch 本体显式接线（onCheckedChange 传回调），与整行点击共用面板内的 `setFollowSys` 入口，
 *   保证开关状态与窗口实际亮度同步变化（对齐原 Checkbox 修复逻辑）
 * - 关闭面板时持久化到 SP（键与原实现一致，升级无感）由壳层 [LightPanel] 负责，
 *   重新进入阅读器由 [ReaderBrightness.restore] 恢复手动亮度（窗口亮度不跨生命周期）
 *
 * @param brightness 当前手动亮度（0~255，与落库的 `light` 同单位；上屏按百分比换算）
 * @param followSystem 是否跟随系统亮度：为真时滑条禁用、数值行改说"自动"
 * @param onSystemBrightnessRestore 把窗口亮度交还系统（写 BRIGHTNESS_OVERRIDE_NONE）
 */
@Composable
fun LightPanelContent(
    brightness: Int,
    followSystem: Boolean,
    onBrightnessChange: (Int) -> Unit,
    onSystemBrightnessRestore: () -> Unit,
    onFollowSystemChange: (Boolean) -> Unit,
) {
    // 切换"跟随系统"的唯一入口：勾选恢复系统亮度；取消勾选立即应用手动亮度，
    // 保证复选框状态与窗口实际亮度同步变化（不依赖滑条拖动）
    fun setFollowSys(follow: Boolean) {
        onFollowSystemChange(follow)
        if (follow) {
            onSystemBrightnessRestore()
        } else {
            onBrightnessChange(brightness)
        }
    }

    Column(
        modifier = Modifier
            .padding(horizontal = CommonUiTokens.pagePadding)
            .padding(top = ReaderChromeTokens.sheetTopPadding)
    ) {
        SheetHeader(stringResource(R.string.luminance))
        Spacer(modifier = Modifier.height(CommonUiTokens.sectionSpacing))
        CommonCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp)
            ) {
                // 数值行：手动态显示百分比（主色，强调"这是可调值"），跟随系统态显示"自动"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.reader_current_brightness),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = if (followSystem) {
                            stringResource(R.string.brightness_auto)
                        } else {
                            stringResource(R.string.reader_brightness_percent_format, brightness)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (followSystem) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                // 亮度滑条：两端图标锚定低/高方向语义，禁用态（跟随系统）由滑条淡出表达
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.BrightnessLow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ReaderSlider(
                        value = brightness.toFloat(),
                        valueRange = 0f..255f,
                        enabled = !followSystem,
                        onValueChange = { value ->
                            onBrightnessChange(value.roundToInt())
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        contentDescription = stringResource(R.string.luminance)
                    )
                    Icon(
                        imageVector = Icons.Outlined.BrightnessHigh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                // 跟随系统亮度：整行可点，Switch 本体与行点击共用同一切换入口（见类注释）
                PanelSwitchRow(
                    icon = Icons.Outlined.Tune,
                    label = stringResource(R.string.follow_system_brightness),
                    description = stringResource(R.string.follow_system_brightness_desc),
                    checked = followSystem,
                    onCheckedChange = { setFollowSys(it) }
                )
            }
        }
        Spacer(modifier = Modifier.height(ReaderChromeTokens.sheetBottomPadding))
    }
}
