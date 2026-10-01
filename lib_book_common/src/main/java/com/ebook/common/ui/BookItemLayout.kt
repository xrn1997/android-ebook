package com.ebook.common.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 「三行书条目」这一族版式的共用词汇：列高、行距、封面比例、动作槽宽、中间行的行数算式，
 * 以及这一族共用的 [BookItemFrame] 外壳与 [BookItemMetaRow] 底行。
 * 找书条目（搜索 / 分类选书）与书架条目用的是**同一档**，所以两边卡片同高、同分格。
 *
 * 等高的硬约束是「同一个列表内所有卡片等高」。预留行数取的是**两行**而不是三行：
 * 实测这两页的源大多不配 `intro`，中间那一格实际只有一句「第 N 章 …」，
 * 按三行预留就在每张卡里塞进 40~56dp 谁也没用的空白（等高于是变成了"等高且空"）。
 * 两行覆盖得住配了 `intro` 的源的首屏观感，长简介的全文在详情页看。
 */
object BookItemLayout {
    /**
     * 正文列定高：书名 20 + 行距 4 + 中间行两行 32 + 底行 16 = 72，再留 4dp 给
     * 字形自然行高与非线性字体缩放（见 [lineFitSlop] 那条理由）→ **76dp**，
     * 配上条目卡上下各 10dp 内边距是 96dp 卡高。
     */
    val columnHeight = 76.dp

    /** 书名 → 中间行的固定行距。它也参与行数算式，改动要同步传进 [bookItemLineCount] */
    val middleSpacing = 4.dp

    /** 条目右上角动作图标（如加书架）的槽宽：书名那一行为它让出这么多，其余两行通宽 */
    val actionSlot = 24.dp

    /**
     * 由正文列高推出封面宽，使封面恰好 3:4。
     *
     * 理由是 [BookCover] 用 `ContentScale.Crop` 按框裁切，比例对上就一边都不裁——不少站点
     * 把书名与作者印在封面底边，裁掉的正是那一条。列高与封面等高也让卡片没有多余空白。
     */
    fun coverWidth(columnHeight: Dp): Dp = columnHeight * 0.75f
}

/**
 * 中间区还能塞下几行文字：`行数 = ⌊(列高 − 书名行 − 底行 − 固定行距 − 余量) ÷ 本行行高⌋`。
 *
 * 为什么必须有这一步：Compose 的文本只有 `maxLines`（"最多几行"），**没有**"给这块高度、
 * 装得下几行就显示几行"的开关。不算是做不到等高的——写死 3 行时，系统字号放大后三行的自然高
 * 超过中间区，多出来的部分会画到框外压住底行（或 `clipToBounds` 切半字）。
 * 算出来则反过来：字号越大行数越少，卡片高度恒定。返回 0 表示这一格连一行都放不下，
 * 调用方应当整格不渲染。
 *
 * 为什么要减 [lineFitSlop] 而不是贴着边界除：算式里的行高是主题的 `lineHeight`，而文字真正占的
 * 行高还会被两件事抬高——CJK 字形的自然行高可高于所设的 `lineHeight`、Android 14+ 的字体缩放是
 * **非线性**的。115% 那一档贴着算会得到 2.97 行，0.03 行的余量扛不住这种偏差，一抬就多排一行、
 * 直接溢出到下面。宁可少排一行（空白集中到一处），也不要多排一行压住底行。
 *
 * @param spacing 书名与中间行之间那段**固定**行距（不传即视为 0），它也要从可用高度里扣掉——
 *   不扣就会多算一行、把底行顶出去，等高当场失效
 */
fun bookItemLineCount(
    columnHeight: Dp,
    nameLine: Dp,
    bottomLine: Dp,
    lineHeight: Dp,
    spacing: Dp = 0.dp,
): Int {
    if (lineHeight <= 0.dp) return 0
    val room = columnHeight - nameLine - bottomLine - spacing - lineFitSlop
    if (room <= 0.dp) return 0
    return (room / lineHeight).toInt().coerceAtLeast(0)
}

/** 除行数时预留的余量，用来吸收「主题设的行高」与「文字实际占的行高」之间的差 */
private val lineFitSlop = 1.dp

/**
 * [bookItemLineCount] 的装配版：把三个 `TextStyle` 的 `lineHeight`（Sp）换算成 Dp 再算。
 *
 * 换算必须走 `TextUnit.toDp()`：Android 上它经 `FontScaleConverter`，Android 14+ 是**非线性**
 * 字体缩放，自己乘 `fontScale` 会算多或算少一行；写死行高字面值（20/16/16）则会在主题或
 * token 变动后与真实行高脱钩——两种错都不崩，只是行数不对，要么切半行要么白留一格。
 *
 * @param columnHeight 该列表自己的正文列高（见 [BookItemLayout] 的说明）
 */
@Composable
fun bookItemLineCount(
    columnHeight: Dp,
    nameStyle: TextStyle,
    bottomStyle: TextStyle,
    bodyStyle: TextStyle,
    spacing: Dp = 0.dp,
): Int {
    val density = LocalDensity.current
    return bookItemLineCount(
        columnHeight = columnHeight,
        nameLine = with(density) { nameStyle.lineHeight.toDp() },
        bottomLine = with(density) { bottomStyle.lineHeight.toDp() },
        lineHeight = with(density) { bodyStyle.lineHeight.toDp() },
        spacing = spacing,
    )
}

/**
 * 「三行书条目」的外壳：封面（3:4，与正文列等高）+ 定高正文列 + 书名。
 *
 * 找书条目（搜索 / 分类选书）与书架条目原先是两份各自维护的同构实现——尺寸都取 [BookItemLayout]、
 * 内边距与分格逐字相同，只有「右上角有没有动作图标」和「可不可点 / 长按」两处不同。改动其中一份
 * 另一份不会跟着动，而这类漂移不报错、只让两个列表的卡片高度对不上，故收敛到这一处。
 *
 * 三条口径：
 * - 卡片内边距 10dp、正文列定高 [BookItemLayout.columnHeight]、封面按 3:4 从列高推出，
 *   于是任何一处的卡片高度恒为列高 + 20dp，两个列表同屏也严格等高。
 * - 顶对齐是刻意的：封面顶边与书名顶边是同一条基线，谁都不该因为比封面矮就被挤到中间。
 * - [action] 是**覆盖层**：只有书名那一行为它让出 [BookItemLayout.actionSlot] 宽（不让就叠字），
 *   下面的行通宽——贴右的字段（书源等）因此能顶到卡片内边距。
 *
 * @param coverUrl 封面地址（空串由 [BookCover] 落兜底图）
 * @param coverDescription 封面无障碍描述
 * @param title 书名（恒单行 + 省略号）
 * @param modifier 外层修饰
 * @param enabled 整卡是否响应点击；false 时仍照常渲染（如书架页的"解析中"占位行）
 * @param onClick 点击；不给即整卡不挂点击面
 * @param onLongClick 长按；给了就走 `combinedClickable`
 * @param shadowElevation 阴影由调用方定（列表密排的书架页传 0dp）
 * @param action 右上角动作槽（如"加书架"）；**在 [BoxScope] 里调用**，对齐与尺寸由它自己
 *   （`align(Alignment.TopEnd)` + [BookItemLayout.actionSlot]）。null 时那一块整个不渲染、书名也不让宽
 * @param body 书名之下那一坨（`ColumnScope`，所以各变体能用 `weight` 排空白）
 */
@Composable
fun BookItemFrame(
    coverUrl: String,
    coverDescription: String?,
    title: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    shadowElevation: Dp = 1.dp,
    action: (@Composable BoxScope.() -> Unit)? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    CommonItemCard(
        modifier = modifier,
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        shadowElevation = shadowElevation,
        contentPadding = PaddingValues(10.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            BookCover(
                url = coverUrl,
                contentDescription = coverDescription,
                modifier = Modifier.size(
                    width = BookItemLayout.coverWidth(BookItemLayout.columnHeight),
                    height = BookItemLayout.columnHeight
                ),
                shape = RoundedCornerShape(6.dp)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(BookItemLayout.columnHeight)
                    .padding(start = 12.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // 行尾让开动作槽：图标浮在右上角，不让宽就会叠在字上
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (action != null) {
                                    Modifier.padding(end = BookItemLayout.actionSlot)
                                } else {
                                    Modifier
                                }
                            )
                    )
                    body()
                }
                action?.invoke(this)
            }
        }
    }
}

/**
 * 条目底行：左（作者等）靠左、右（书源 / 共 N 章）贴右，两列各自省略。
 *
 * 谁也不挤谁——右侧长文本截在自己的 120dp 里，不会像旧实现那样把同行字段压成 0 宽整片消失
 * （Row 给无权重子项的是"剩余宽度"上限，见 `RowColumnMeasurePolicy` 的 `mainAxisMax = remaining`）。
 * 左列缺席时仍占位（空 `Text` + `weight`），右列才不会被拽到中间；右列为空串则不渲染（不留空槽）。
 */
@Composable
fun BookItemMetaRow(left: String, right: String) {
    val typography = MaterialTheme.typography
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = left,
            style = typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (right.isNotEmpty()) {
            Text(
                text = right,
                style = typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .widthIn(max = 120.dp)
            )
        }
    }
}
