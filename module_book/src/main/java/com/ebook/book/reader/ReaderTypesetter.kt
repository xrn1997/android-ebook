package com.ebook.book.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.ebook.book.ReadBookActivity
/**
 * 正文排版的事实源：分页样式（字号/行高/对齐）+ 测量器 + 密度。
 *
 * 存在理由是「**分页与渲染必须同源**」这条契约：
 * 一页切出多少行、正文区放得下多少行、页面实际画出多少行，三件事都必须由同一个
 * 排版引擎按同一份样式回答。只要有两套判定，被切进来却放不下的行就会静默消失
 * （历史缺陷见 [readerBodyTextStyle] 的说明）。
 *
 * `TextMeasurer`/`Density` 只能在组合期取得，故由 [rememberReaderTypesetter] 装配，
 * 再经 `ReadBookActivity.rePaginate` 落定为「当前样式」，切行与渲染都读它。
 */
internal class ReaderTypesetter(
    private val measurer: TextMeasurer,
    private val density: Density,
    val style: TextStyle,
) {

    /**
     * 按渲染引擎自身的断行结果，返回每一「渲染行」在原文中的**起始偏移**。
     *
     * 调用成本注意：本方法整章测量 O(章长)（非增量）。[ReadBookActivity.loadPage] 每页都调用
     * 它重排整章、不缓存，同章 N 页即 N 次整章重排；数千字章节在 Default 线程可接受，但超长章
     * （几十页）会放大成可见的 N× CPU 开销。若将来有性能诉求，应在调用侧按（章节，字号）粒度
     * 缓存这份偏移，而非反复调用本方法。
     *
     * 只回偏移、不回子串：Compose 对 `\r\n`（本书源段落分隔符）的断行会把 `\n` 吞进
     * 行间隔而不归入任何一行（`getLineEnd(i, includeNewline=true)` 只到 `\r`），
     * 若把各行子串拼回原文会丢光 `\n`、只剩孤立的 `\r`；而孤立的 `\r` 在渲染端**不**被
     * 当作段落换行，段落会被并成一团重新折行——页数与折行同时错乱。
     * 改用偏移在原文上取连续子串（[ReadBookActivity.loadPage]），段落分隔符原样保留，
     * 渲染端按同一宽度重排即得与分页完全一致的折行。
     *
     * 列表长度即渲染行总数，供分页切片；相邻行偏移之间的字符（含被吞的换行）天然属于
     * 前一行到后一行之间的原文，页与页首尾相接、不丢字。
     */
    fun lineStartOffsets(text: String, widthPx: Int): List<Int> {
        if (text.isEmpty() || widthPx <= 0) return emptyList()
        val layout = measure(text, widthPx)
        return List(layout.lineCount) { layout.getLineStart(it) }
    }

    /**
     * 正文区高度放得下多少渲染行：逐行读实测底部位置，取不越界的最大行数。
     *
     * 不再用 `(高度 - 段距) / (字高 + 段距)` 估算——该公式拿平台字体度量算字高，
     * 与 Compose 实际行高（含整数进位）每行差约 0.7px，25 行累计近 20px，
     * 叠上「分行行数 != 渲染行数」就会把整行挤出可见区。这里直接问渲染引擎本身。
     */
    fun fitRenderLineCount(widthPx: Int, heightPx: Int): Int {
        if (widthPx <= 0 || heightPx <= 0) return 0
        val layout = measure(probeText, widthPx)
        var fit = 0
        for (i in 0 until layout.lineCount) {
            if (layout.getLineBottom(i) > heightPx) break
            fit = i + 1
        }
        return fit
    }

    private fun measure(text: String, widthPx: Int) = measurer.measure(
        text = AnnotatedString(text),
        style = style,
        constraints = Constraints(maxWidth = widthPx),
        density = density,
    )

    private companion object {
        /** 测算行高的探针：60 行同字高的行（覆盖任何屏幕高度），行间用硬换行分隔 */
        const val PROBE_LINES = 60

        /** 探针字符取常用汉字，保证度量与真实正文同字体同字宽 */
        val probeText: String = buildString {
            for (i in 0 until PROBE_LINES) {
                if (i > 0) append('\n')
                append('一')
            }
        }
    }
}

/**
 * 组合期装配 [ReaderTypesetter]。
 *
 * `rememberTextMeasurer()` 默认不带缓存（cacheSize=0）：翻页时上一页/下一页会并发加载，
 * 测量在 Dispatchers.Default 上跑，无缓存才不存在跨线程改内部状态的问题。
 *
 * @param textSizeSp 正文字号（ReadBookControl）
 * @param lineHeight 正文行高（= 单行字高 + 段距，与 [ReaderPageCard] 渲染同一入参）
 */
@Composable
internal fun rememberReaderTypesetter(textSizeSp: Float, lineHeight: TextUnit): ReaderTypesetter {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density, textSizeSp, lineHeight) {
        ReaderTypesetter(measurer, density, readerBodyTextStyle(textSizeSp, lineHeight))
    }
}

/**
 * 阅读正文的唯一样式：渲染（[ReaderPageCard]）与分页测量（[ReaderTypesetter]）共用。
 *
 * 为什么必须共用（历史缺陷）：分行曾用平台 StaticLayout、渲染用 Compose Text，两条
 * 排版管线对「一行装得下几个字」的判定不一致——实测同一页切成 25 行、Compose 重排成
 * 26~27 行，多出的行落在正文区之外被裁掉；翻页却仍按 25 行推进，于是被裁掉的那一行
 * 上一页看不见、下一页也没有，读者看到的就是「上下页内容接不上、正文少了一段」。
 *
 * `lineHeightStyle` 显式固定：不写就会随主题的隐式排版样式解析，测量端与渲染端可能
 * 取到不同的首/末行 leading 裁剪方式，行数换算再次漂移。Trim.None + Center 与修复前
 * 的实际渲染几何一致（每行等高、不裁剪），视觉不变。
 */
internal fun readerBodyTextStyle(textSizeSp: Float, lineHeight: TextUnit): TextStyle = TextStyle(
    fontSize = textSizeSp.sp,
    lineHeight = lineHeight,
    textAlign = TextAlign.Start,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None
    )
)
