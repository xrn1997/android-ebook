package com.ebook.me.view

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ebook.common.domain.BookComment
import com.ebook.common.event.KeyCode
import com.ebook.common.event.RouteArgs
import com.ebook.common.ui.CommonItemCard
import com.ebook.common.ui.InfoChip
import com.ebook.me.R
import com.ebook.me.mvvm.viewmodel.CommentViewModel
import com.therouter.TheRouter.build
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseMvvmActivity
import com.xrn1997.common.ui.NoDataView
import dagger.hilt.android.AndroidEntryPoint

/**
 * 我的评论页：我发表过的章节评论列表。
 *
 * 交互：
 * - 点击评论跳转对应章节评论区（module_book 的 COMMENT_PATH，参数 key 见 [RouteArgs]）
 * - 长按弹出删除确认（删除后自动刷新列表）
 */
@AndroidEntryPoint
@Route(path = KeyCode.Me.COMMENT_PATH, params = ["needLogin", "true"])
class MyCommentActivity : BaseMvvmActivity<CommentViewModel>() {
    override val viewModel: CommentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toolbarTitle.value = getString(R.string.my_comment_title)
    }

    override fun initData() {
        viewModel.refreshData()
    }

    @Composable
    override fun PageContent() {
        val comments by viewModel.list.collectAsState()
        MyCommentScreen(
            comments = comments,
            onCommentClick = { comment ->
                val bundle = Bundle().apply {
                    putString(RouteArgs.COMMENT_KEY, comment.commentKey)
                    putString(RouteArgs.CHAPTER_URL, comment.chapterUrl)
                    putString(RouteArgs.CHAPTER_NAME, comment.chapterName)
                    putString(RouteArgs.BOOK_NAME, comment.bookName)
                }
                build(KeyCode.Book.COMMENT_PATH)
                    .with(bundle)
                    .navigation(this@MyCommentActivity)
            },
            onDelete = { comment -> viewModel.deleteComment(comment.id) }
        )
    }
}

/**
 * 我的评论页内容：空态 / 列表两态互斥（加载遮罩由基类 Overlay 承担）。
 *
 * 纯状态 + 回调（不持有 ViewModel），便于预览与测试。
 */
@Composable
fun MyCommentScreen(
    comments: List<BookComment>,
    onCommentClick: (BookComment) -> Unit,
    onDelete: (BookComment) -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf<BookComment?>(null) }

    // 两态互斥：空数据（自绘带文案空态，X2 组件）→ 列表
    if (comments.isEmpty()) {
        // 空态：图标 + 主文案 + 操作提示（对齐原 EmptyCommentState 信息量，改用共享 NoDataView）
        NoDataView(
            visible = true,
            modifier = Modifier.fillMaxSize(),
            title = stringResource(R.string.my_comment_empty_title),
            hint = stringResource(R.string.my_comment_empty_hint),
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp, vertical = 8.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(comments, key = { it.id }) { comment ->
                CommentItem(
                    comment = comment,
                    onClick = { onCommentClick(comment) },
                    onLongClick = { showDeleteDialog = comment }
                )
            }
        }
    }

    showDeleteDialog?.let { comment ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text(stringResource(R.string.my_comment_delete_title)) },
            text = { Text(stringResource(R.string.my_comment_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(comment)
                    showDeleteDialog = null
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/**
 * 评论条目：书名 + 时间一行、章节 chip、内容摘要（最多 3 行）。
 */
@Composable
fun CommentItem(
    comment: BookComment,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    // 条目壳收口到共享 CommonItemCard（ADR-0006）：阴影与内边距都取 module_book 评论区条目的
    // 同一套取值（shadowElevation = 0.dp + 组件默认 12.dp 内边距，原手绘 Card 为 1.dp/16.dp），
    // 列表密排不叠阴影
    CommonItemCard(onClick = onClick, onLongClick = onLongClick, shadowElevation = 0.dp) {
        Column {
            // 书名 + 发表时间
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = comment.bookName ?: "",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = comment.addTime,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 章节 chip：共享组件 InfoChip 默认形态（小圆角 + surfaceVariant 弱化底，见 ADR-0006）
            comment.chapterName?.takeIf { it.isNotEmpty() }?.let { chapterName ->
                Spacer(modifier = Modifier.height(8.dp))
                InfoChip(text = chapterName)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 评论内容摘要
            Text(
                text = comment.content ?: "",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
