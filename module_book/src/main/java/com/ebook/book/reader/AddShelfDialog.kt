package com.ebook.book.reader

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ebook.book.R

/**
 * 加入书架确认弹窗（替代原 CheckAddShelfPop，居中展示）。
 *
 * 主/次动作用不同按钮形态区分（加入书架 = 填充主按钮，退出 = 文本按钮），
 * 原实现两个 TextButton 并排，误触"退出"直接结束阅读的概率更高。
 */
@Composable
fun AddShelfDialog(
    bookName: String,
    onExit: () -> Unit,
    onAddShelf: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.BookmarkBorder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        text = { Text(stringResource(R.string.tv_pop_checkaddshelf, bookName)) },
        confirmButton = {
            // 与全仓其余对话框主动作同为 TextButton：M3 里对话框的按钮就是文字按钮，
            // 同一种"对话框里的主动作"不该在这里是胶囊实心 Button、在别处是 TextButton
            TextButton(onClick = onAddShelf) {
                Text(stringResource(R.string.add_to_shelf))
            }
        },
        dismissButton = {
            TextButton(onClick = onExit) {
                Text(stringResource(R.string.exit))
            }
        }
    )
}
