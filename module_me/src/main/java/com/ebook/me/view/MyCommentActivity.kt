package com.ebook.me.view

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Comment
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.ebook.common.domain.BookComment
import com.ebook.common.domain.CommentTime
import com.ebook.common.event.KeyCode
import com.ebook.common.event.RouteArgs
import com.ebook.common.ui.CommonItemCard
import com.ebook.common.ui.EmptyState
import com.ebook.common.ui.InfoChip
import com.ebook.common.ui.preview.AppPreview
import com.ebook.common.ui.preview.sampleComments
import com.ebook.me.R
import com.ebook.me.mvvm.viewmodel.CommentViewModel
import com.therouter.TheRouter.build
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseMvvmActivity
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

    // 两态互斥：空数据（共享 EmptyState，线性图标 + 主文案 + 引导语）→ 列表
    if (comments.isEmpty()) {
        // 外层 Box 补两件共享组件不负责的事：撑满列表区（沿用它原来 fillMaxSize 的槽位），
        // 以及自绘背景——旧的占位组件自带 background 覆盖层，EmptyState 只是内容块、不自带背景
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            EmptyState(
                icon = Icons.AutoMirrored.Outlined.Comment,
                title = stringResource(R.string.my_comment_empty_title),
                hint = stringResource(R.string.my_comment_empty_hint),
            )
        }
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
                    text = CommentTime.displayText(comment.addTime),
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

/**
 * 预览：我的评论页列表态，三条条目把 [CommentItem] 的四处可选字段一次排完。
 *
 * 这四档都是「少填一个字段不报错、只少画一块」的形态，编译与单元测试都不看：
 * - 第一条字段齐全（书名 + 章节 chip + 内容 + 时间），是本页的常规长相；
 * - 第二条 `chapterName = null`：`takeIf { it.isNotEmpty() }?.let` 那段判空让整块 chip
 *   连同它上面那 8dp 间距一起省掉——只看第一条会以为行间距离是固定的；
 * - 第三条超长书名与超长内容（`maxLines = 3` 的省略号），`addTime` 给一个解不出的串：
 *   [CommentTime.displayText] 的口径是「不可解析给空串、让 UI 留白而不是显示脏值」，
 *   这一档专门看那片留白是否只是没有日期、而不是整行塌掉。
 */
@PreviewLightDark
@Composable
private fun MyCommentScreenPreview() {
    AppPreview {
        MyCommentScreen(
            comments = previewComments(),
            onCommentClick = {},
            onDelete = {},
        )
    }
}

/**
 * 预览：空态（还没发表过评论）。
 *
 * 本页两态互斥（`if (comments.isEmpty())`），列表态的预览永远看不到这一档：
 * 那里走的是共享 [EmptyState]（线性图标 + 主文案 + 引导语），并且由外层 Box 补
 * 「撑满 + 自绘背景」两件事——`EmptyState` 只是内容块、自带背景没有，
 * 少了那层 `background` 深色下就是一片透明。
 */
@Preview(showBackground = true)
@Composable
private fun MyCommentScreenEmptyPreview() {
    AppPreview {
        MyCommentScreen(
            comments = emptyList(),
            onCommentClick = {},
            onDelete = {},
        )
    }
}

/**
 * 预览样例：整份取自门面的 [sampleComments]——本人/他人交替出场、末条超长正文 + 解不出的
 * 时间串，都是门面刻意铺好的档，本页只覆盖它表达不出的两个字段。
 *
 * 两处覆盖都对应 [CommentItem] 上一条「少填一个字段不报错、只少画一块」的形态：
 * 中间那条抹掉 `chapterName`（不画 chip，连它上面那 8dp 间距也一起省掉），末条换一个够长的
 * 书名（`maxLines = 1` 的省略号，门面生成的标题都短，这一档它给不出）。
 */
private fun previewComments(): List<BookComment> =
    sampleComments(3).mapIndexed { index, comment ->
        when (index) {
            1 -> comment.copy(chapterName = null)
            2 -> comment.copy(bookName = "一部书名长得会被省略掉后半段的示例作品")
            else -> comment
        }
    }
