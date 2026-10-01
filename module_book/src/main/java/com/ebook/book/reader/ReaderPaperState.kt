package com.ebook.book.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ebook.book.R
import com.ebook.common.ui.CommonUiTokens

/**
 * 「纸面态」覆盖层：加载中（[ReaderPageUi.Loading]）与加载失败（[ReaderPageUi.Error]）两态的唯一画法。
 *
 * 翻页（[ReaderPageCard]）与滚屏（[ReaderScroll] 的 [ScrollBlock] / 排版未落定占位）两种承载方式
 * 逐字共用这份实现——两处此前是两份复制粘贴的代码，任何视觉微调都得改两遍，漏改一处就长成
 * 「两种翻页方式长得不一样」。
 *
 * **接收者必须是 [BoxScope]**：两态都要 `align(Alignment.Center)` 居中，而居中语义依赖父级是 Box。
 * 写成 BoxScope 扩展比传 `Modifier` 进来更准——后者会把居中语义散到调用点，多个调用点各写一次就会漂移。
 *
 * **配色一律由入参 [textColor] 按透明度派生，禁止改用 `MaterialTheme.colorScheme.*`**：这一层画在
 * 「纸面」上，属「阅读背景主题」层（四档纸张色，见 ADR-0012 的两层口径），不随深浅色外观切换；
 * 换成全局主题语义色会在护眼绿/米黄等纸张色上与底色打架。同理，正文层有自己的字号体系，
 * 这里的字号刻意不走 Material typography，保持 14sp / 16sp 的字面值。
 *
 * **重试按钮刻意自绘胶囊（不用 `TextButton`）**：底色 + 描边双重表达可点性——原实现是 4dp 直角的
 * 细边框，在护眼绿/米黄背景上几乎看不出是个按钮。要改就得两种翻页方式一起接受该视觉变化。
 *
 * @param ui 当前纸面态。[ReaderPageUi.Loaded] 在这里是 no-op：正文由调用方绘制（翻页态画在
 *   常驻骨架层、滚屏态画在块内），保留该分支只是为了让本函数的 `when` 对密封类型保持穷尽，
 *   调用方不必自己再分一次流。
 * @param textColor 正文色（纸张主题的文字色），本组件全部颜色由它派生
 * @param onRetry 失败态「重试」回调；加载态不会用到（传 `{}` 即可）
 */
@Composable
internal fun BoxScope.ReaderPaperState(
    ui: ReaderPageUi,
    textColor: Color,
    onRetry: () -> Unit,
) {
    when (ui) {
        is ReaderPageUi.Loading -> {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    color = textColor.copy(alpha = 0.35f),
                    strokeWidth = 2.5.dp,
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.loading),
                    color = textColor.copy(alpha = 0.55f),
                    fontSize = 14.sp,
                )
            }
        }

        is ReaderPageUi.Error -> {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Outlined.CloudOff,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = textColor.copy(alpha = 0.4f),
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.reader_load_failed),
                    color = textColor.copy(alpha = 0.8f),
                    fontSize = 16.sp,
                )
                Spacer(modifier = Modifier.height(22.dp))
                // 重试改为胶囊按钮：底色 + 描边双重表达可点性（原 4dp 直角细边框在
                // 护眼绿/米黄背景上几乎看不出是个按钮）
                Box(
                    modifier = Modifier
                        .clip(CommonUiTokens.pillShape)
                        .background(textColor.copy(alpha = 0.07f))
                        .border(
                            width = 1.dp,
                            color = textColor.copy(alpha = 0.3f),
                            shape = CommonUiTokens.pillShape,
                        )
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.retry),
                        color = textColor.copy(alpha = 0.85f),
                        fontSize = 14.sp,
                    )
                }
            }
        }

        // 正文已由调用方绘制（翻页态画在常驻骨架层、滚屏态画在块内），此处无额外内容
        is ReaderPageUi.Loaded -> Unit
    }
}
