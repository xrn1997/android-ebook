package com.ebook.common.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 跨模块共享的 Compose UI 组件库（ADR-0006）。
 *
 * module_me 重设计沉淀的视觉语言（轻卡片 + 语义色 + Material typography）
 * 上提到此处，供书城/书架/我的等业务模块统一复用，保证全 App 视觉一致：
 * 圆角卡片（[CommonCard]）+ 条目卡容器（[CommonItemCard]）+ 彩色图标列表项
 * （[CommonListItem]）+ 缩进分割线（[CommonListDivider]）+ 分组标题（[SectionLabel]）
 * + 信息标签（[InfoChip]）。
 *
 * **图标约束**：本文件只允许使用 material-icons-core 核心集图标
 * （如 [Icons.AutoMirrored.Filled.KeyboardArrowRight]），不得引入 iconsExtended——
 * 基础库体积由全部业务模块分担，业务页需要扩展图标时由各自模块声明依赖。
 */

/**
 * 跨模块共享设计常量：圆角/间距的唯一事实来源。
 *
 * 业务模块的分组卡片、列表条目、标签、封面、页面边距应引用这里的常量，
 * 禁止再写同语义的魔法值，避免设计语言在各模块间漂移。
 */
object CommonUiTokens {
    /** 分组容器卡圆角（[CommonCard]） */
    val cardCorner = 16.dp

    /** 列表条目卡圆角（搜索结果、评论等条目卡片） */
    val cardCornerSmall = 12.dp

    /** 信息小标签圆角（[InfoChip] 默认；胶囊场景调用方传 `RoundedCornerShape(50)`） */
    val chipCorner = 4.dp

    /** 书籍封面圆角（[BookCover] 默认） */
    val coverCorner = 10.dp

    /** 页面水平边距 */
    val pagePadding = 16.dp

    /** 卡片区块之间的垂直间距 */
    val sectionSpacing = 12.dp

    /** 列表条目卡之间的垂直间距 */
    val listSpacing = 8.dp

    /** 缩进分割线起始缩进（36dp 图标容器 + 12dp 间隙 + 16dp 内边距） */
    val dividerIndent = 64.dp
}

/**
 * 通用分组卡片容器：16dp 圆角 + surfaceContainer 语义色 + 轻阴影。
 *
 * 各模块的分组列表（菜单/设置项/分类区块）统一用它包裹，
 * 与条目卡（12dp 圆角）形成「容器-条目」两级卡片层次。
 */
@Composable
fun CommonCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(CommonUiTokens.cardCorner),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 1.dp
    ) {
        content()
    }
}

/**
 * 列表条目卡容器：12dp 圆角（[CommonUiTokens.cardCornerSmall]）+ surfaceContainer 语义色，
 * 与分组容器 [CommonCard]（16dp）构成「容器-条目」两级卡片层次（ADR-0006）。
 *
 * 只负责「壳」：形状、语义色、点击面（ripple 随圆角裁剪）、内边距。
 * 内容形态由各页决定（封面行、评论列、图标行），故内容用 slot 传入。
 *
 * @param onClick 点击回调；与 [onLongClick] 都为 null 时不挂点击面（纯展示条目）
 * @param onLongClick 长按回调（如书架删除、评论删除确认）；提供时点击走
 *   [androidx.compose.foundation.combinedClickable] 以保住长按手势
 * @param enabled 点击是否可用（不可用时仍渲染，只是不响应）
 * @param shadowElevation 阴影高度；列表密集排布时可传 0.dp 让层级更平
 * @param contentPadding 条目内边距，默认 12dp；非默认值需在调用处说明原因
 */
@Composable
fun CommonItemCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    content: @Composable () -> Unit
) {
    // clickable 挂在 Surface 的 modifier 上：ripple 按圆角裁剪，命中区即整卡
    val interactionModifier = when {
        onLongClick != null -> Modifier.combinedClickable(
            enabled = enabled,
            onClick = onClick ?: {},
            onLongClick = onLongClick
        )

        onClick != null -> Modifier.clickable(enabled = enabled, onClick = onClick)
        else -> Modifier
    }

    Surface(
        modifier = modifier.fillMaxWidth().then(interactionModifier),
        shape = RoundedCornerShape(CommonUiTokens.cardCornerSmall),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = shadowElevation
    ) {
        Box(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }
}

/**
 * 菜单/设置项：36dp 圆角彩色图标容器 + 标题（可带尾部值文本）+ 右侧箭头。
 *
 * @param icon Material 图标（核心集，见文件头约束）
 * @param title 标题
 * @param iconContainerColor 图标容器语义色（primary/secondary/tertiaryContainer 区分入口）
 * @param iconContentColor 图标前景语义色
 * @param trailingText 标题右侧的值文本（如缓存大小、版本号），为空时不占位
 * @param trailingContent 标题右侧的自定义内容（如头像缩略图），优先于 [trailingText]
 * @param showArrow 是否显示右侧箭头（纯展示项如版本号不显示）
 * @param onClick 点击回调
 */
@Composable
fun CommonListItem(
    icon: ImageVector,
    title: String,
    iconContainerColor: Color,
    iconContentColor: Color,
    modifier: Modifier = Modifier,
    trailingText: String? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    showArrow: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = RoundedCornerShape(10.dp),
            color = iconContainerColor
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconContentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        if (trailingContent != null) {
            trailingContent()
            Spacer(modifier = Modifier.width(4.dp))
        } else {
            trailingText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
        }
        if (showArrow) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 列表分割线：从文字起始处缩进（避开图标列），比通栏分割线更轻量。
 */
@Composable
fun CommonListDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = CommonUiTokens.dividerIndent),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/**
 * 分组小标题：卡片上方左对齐的弱化标签（如「通用」「关于」「书籍类型」）。
 *
 * @param modifier 外层修饰，调用方可追加边距；组件自身保留默认左缩进与上下间距
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.padding(start = 12.dp, top = 4.dp, bottom = 8.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 信息小标签 / 胶囊：弱化底色 + 圆角 + 单行文本，可点可选。
 *
 * 统一三类重复实现：评论条目的章节小标签（默认 4dp 圆角 + surfaceVariant）、
 * 书籍条目的状态/分类/字数标签、书型与搜索历史的胶囊标签
 * （[shape] 传 `RoundedCornerShape(50)`、[textStyle] 传 labelLarge）。
 *
 * @param text 标签文本
 * @param modifier 外层修饰（点击命中区即 Surface 本身，外部量测如
 *   `onGloballyPositioned` 挂在 [modifier] 上可得到含内边距的整体坐标）
 * @param shape 圆角形状，默认小圆角标签；胶囊传 `RoundedCornerShape(50)`
 * @param containerColor 背景语义色，默认 surfaceVariant（弱化）
 * @param contentColor 文本语义色，默认 onSurfaceVariant
 * @param textStyle 排版，默认 labelSmall；胶囊场景传 labelLarge
 * @param contentPadding 文本内边距，默认适配常规标签；胶囊可加大
 * @param maxLines 最大行数，默认单行省略；可能折行的长文本（如历史词条）
 *   传 [Int.MAX_VALUE]
 * @param onClick 点击回调；非空时整体可点并标注按钮语义，无需调用方再包 clickable
 */
@Composable
fun InfoChip(
    text: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(CommonUiTokens.chipCorner),
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    textStyle: TextStyle = MaterialTheme.typography.labelSmall,
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    maxLines: Int = 1,
    onClick: (() -> Unit)? = null
) {
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    Surface(
        modifier = modifier.then(clickableModifier),
        shape = shape,
        color = containerColor,
        contentColor = contentColor
    ) {
        Text(
            text = text,
            style = textStyle,
            color = contentColor,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(contentPadding)
        )
    }
}
