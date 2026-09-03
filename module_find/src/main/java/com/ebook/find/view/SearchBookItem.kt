package com.ebook.find.view

import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ebook.common.ui.BookCover
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.InfoChip
import com.ebook.db.entity.SearchBookEntity
import com.ebook.find.R
import java.text.DecimalFormat

/**
 * 搜索结果/选书列表条目（ADR-0006 共享设计语言重设计）。
 *
 * 12dp 圆角条目卡（[CommonUiTokens.cardCornerSmall] + surfaceContainer 语义色 + 轻阴影），
 * 封面统一走 [BookCover]（小圆角变体），状态/分类/字数标签收敛为 [InfoChip]，
 * 字号全部改走 Material typography（不再硬编码 sp）。
 *
 * @param searchBook 书籍数据
 * @param onItemClick 点击条目（打开书籍详情）
 * @param onAddShelf 点击"加入书架"
 */
@Composable
fun SearchBookItem(
    searchBook: SearchBookEntity,
    onItemClick: () -> Unit,
    onAddShelf: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onItemClick),
        shape = RoundedCornerShape(CommonUiTokens.cardCornerSmall),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 1.dp
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            // 封面：共享 BookCover（条目内小封面用小圆角变体）
            BookCover(
                url = searchBook.coverUrl,
                contentDescription = searchBook.name,
                modifier = Modifier.size(width = 60.dp, height = 90.dp),
                shape = RoundedCornerShape(6.dp)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                // 书名
                Text(
                    text = searchBook.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                // 作者 + 来源
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = searchBook.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(90.dp)
                    )
                    if (searchBook.origin.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.source_format, searchBook.origin),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 5.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                // 状态/分类/字数标签：共享 InfoChip 默认形态（小圆角弱化标签）
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (searchBook.state.isNotEmpty()) {
                        InfoChip(text = searchBook.state)
                    }
                    if (searchBook.kind.isNotEmpty()) {
                        InfoChip(text = searchBook.kind)
                    }
                    if (searchBook.words > 0) {
                        InfoChip(text = formatWords(searchBook.words))
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                // 最新章节（无章节时降级为简介）
                Text(
                    text = if (searchBook.lastChapter.isNotEmpty()) searchBook.lastChapter else searchBook.desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 加入书架按钮：主操作统一用 primary（配色规则见 SearchActivity HistoryPanel KDoc）
            Button(
                onClick = onAddShelf,
                enabled = !searchBook.add,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.CenterVertically)
            ) {
                Text(
                    text = if (searchBook.add) {
                        stringResource(R.string.added)
                    } else {
                        stringResource(R.string.add)
                    },
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

/** 字数格式化器：顶层复用（Compose 组合只在主线程，无并发问题），
 * 避免列表条目每次组合都新建 DecimalFormat（构造成本较高的对象） */
private val WORD_FORMAT = DecimalFormat("#.#")

/** 字数格式化：万以上保留一位小数（如 12.3 万字），与旧实现一致。
 *  必须在主线程调用（WORD_FORMAT 非线程安全）。 */
private fun formatWords(words: Long): String {
    check(Looper.myLooper() == Looper.getMainLooper()) {
        "formatWords must be called on the main thread"
    }
    return if (words > 10000) {
        WORD_FORMAT.format((words * 1.0f / 10000f).toDouble()) + "万字"
    } else {
        words.toString() + "字"
    }
}
