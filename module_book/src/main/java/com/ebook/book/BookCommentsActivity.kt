package com.ebook.book

import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ebook.book.mvvm.viewmodel.BookCommentsViewModel
import com.ebook.book.mvvm.viewmodel.isOwnComment
import com.ebook.common.domain.BookComment
import com.ebook.common.event.KeyCode
import com.ebook.common.event.RouteArgs
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.CommonItemCard
import com.ebook.common.util.DateUtil
import com.therouter.router.Route
import com.xrn1997.common.mvvm.IBaseRefreshView
import com.xrn1997.common.mvvm.compose.BaseMvvmActivity
import com.xrn1997.common.mvvm.util.MvvmBinder
import com.xrn1997.common.ui.RefreshableList
import dagger.hilt.android.AndroidEntryPoint

/**
 * 章节评论区（Compose 版，替代原 ViewBinding + RefreshView 壳实现）。
 *
 * 布局：[RefreshableList] 下拉刷新评论列表 + 底部输入栏（对齐原
 * activity_book_comments.xml 的 12:1 权重结构——列表占主体、输入栏固定底部）。
 *
 * 刷新接线：ViewModel 的 [IBaseRefreshView] 刷新信号经 [MvvmBinder] 映射到本地
 * isRefreshing 状态（BaseRefreshViewModel 回调不直接驱动 View）。与书架页
 * [com.ebook.book.page.BookShelfPage] 的 refreshVersion StateFlow 模式分叉，原因：
 * 评论页是独立 Activity 生命周期（非 NavHost 内页面），无 NavBackStackEntry
 * 孤儿 collector 问题，MvvmBinder 在 `updateStopRefresh()` 单消费场景下语义足够，
 * 无需引入版本号 StateFlow（见 BookListViewModel 的 Channel 单消费者竞态说明）。
 *
 * 交互保持与原实现一致：
 * - 仅本人评论可长按删除（用户名与 SP 中登录用户名比对），删除走 Compose
 *   [AlertDialog] 确认（替代原 DeleteDialog BottomSheetFragment）
 * - 发送成功后收起软键盘（原 [com.ebook.book.mvvm.viewmodel.BookCommentsViewModel.mVoidSingleLiveEvent]
 *   语义不变，消费端从 hideSoftInput(View) 改为 SoftwareKeyboardController）
 */
@AndroidEntryPoint
@Route(path = KeyCode.Book.COMMENT_PATH, params = ["needLogin", "true"])
class BookCommentsActivity : BaseMvvmActivity<BookCommentsViewModel>() {
    override val viewModel: BookCommentsViewModel by viewModels()

    override fun initData() {
        // 路由携带的章节信息组装为评论载体（commentKey 是 M2 查询/新增评论的主键）
        val bundle = this.intent.extras
        if (bundle != null && !bundle.isEmpty) {
            val rawKey = bundle.getString(RouteArgs.COMMENT_KEY)
            // M2：阅读器传入逗号分隔的多个章键（跨源合并），我的评论页仍传单键——
            // 统一按逗号拆分，单键场景拆出来就是单元素列表
            val keys = rawKey?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
            val comment = BookComment(
                id = 0, userId = 0, username = "", avatar = "",
                commentKey = keys.firstOrNull(),
                chapterUrl = bundle.getString(RouteArgs.CHAPTER_URL),
                chapterName = bundle.getString(RouteArgs.CHAPTER_NAME),
                bookName = bundle.getString(RouteArgs.BOOK_NAME),
                content = null, addTime = ""
            )
            viewModel.comment = comment
            viewModel.commentKeys = keys
        }
    }

    @Composable
    override fun PageContent() {
        BookCommentsScreen(viewModel = viewModel)
    }
}

/**
 * 评论区内容：刷新列表 + 底部输入栏。
 *
 * 参数化 ViewModel 而非在 Composable 内部 hiltViewModel()：ViewModel 由
 * Activity 持有（路由参数在 [BookCommentsActivity.initData] 写入），
 * 页面与 Activity 必须共用同一实例。
 */
@Composable
fun BookCommentsScreen(viewModel: BookCommentsViewModel) {
    val comments by viewModel.list.collectAsState()
    // 本人判定用的会话 userId：经 VM 从 UserSessionManager 取，不在页面里直读 SP
    val currentUserId by viewModel.currentUserId.collectAsState(initial = null)
    var isRefreshing by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 刷新信号绑定：ViewModel.updateStopRefresh() → isRefreshing = false
    DisposableEffect(lifecycleOwner, viewModel) {
        MvvmBinder.bindRefresh(
            lifecycleOwner,
            object : IBaseRefreshView {
                override fun finishRefresh() {
                    isRefreshing = false
                }

                override fun finishLoadMore(success: Boolean) {
                }

                override fun triggerRefresh() {
                }
            },
            viewModel
        )
        onDispose { }
    }

    // 首次进入自动刷新（对齐原 initData() 的 refreshLayout?.triggerRefresh()）
    LaunchedEffect(Unit) {
        isRefreshing = true
        viewModel.refreshData()
    }

    // 发送成功事件：收起软键盘（对齐原 initView() 中的 mVoidSingleLiveEvent 收集）
    LaunchedEffect(Unit) {
        viewModel.mVoidSingleLiveEvent.collect {
            inputText = ""
            keyboardController?.hide()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        RefreshableList(
            isRefreshing = isRefreshing,
            isLoadingMore = false,
            onRefresh = {
                isRefreshing = true
                viewModel.refreshData()
            },
            onLoadMore = { viewModel.loadMore() },
            enableLoadMore = false,
            modifier = Modifier.weight(1f)
        ) { listState ->
            CommentList(
                listState = listState,
                comments = comments,
                currentUserId = currentUserId,
                onDelete = { comment -> viewModel.deleteComment(comment.id) }
            )
        }
        CommentInputBar(
            text = inputText,
            onTextChange = { inputText = it },
            onSend = { viewModel.addComment(inputText) }
        )
    }
}

/**
 * 评论列表：长按本人评论弹删除确认。
 *
 * 本人判定走 [isOwnComment]（按 userId，判定逻辑在 VM 侧便于单测），
 * 不再比对展示名——展示名填的是「昵称优先」的值，与登录名不同源。
 * 页面边距/条目间距走 [CommonUiTokens]（ADR-0006 共享设计语言）。
 */
@Composable
private fun CommentList(
    listState: LazyListState,
    comments: List<BookComment>,
    currentUserId: Long?,
    onDelete: (BookComment) -> Unit
) {
    var pendingDelete by remember { mutableStateOf<BookComment?>(null) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = CommonUiTokens.pagePadding,
            end = CommonUiTokens.pagePadding,
            top = CommonUiTokens.listSpacing,
            bottom = CommonUiTokens.listSpacing
        ),
        verticalArrangement = Arrangement.spacedBy(CommonUiTokens.listSpacing)
    ) {
        items(comments, key = { it.id }) { comment ->
            CommentItem(comment) {
                // 仅本人评论可删除：按 userId 判定（展示名可重复，不能当所有权凭据）
                if (isOwnComment(comment.userId, currentUserId)) {
                    pendingDelete = comment
                }
            }
        }
    }
    pendingDelete?.let { comment ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            text = { Text(stringResource(R.string.tv_pop_delete_comment)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(comment)
                    pendingDelete = null
                }) {
                    Text(
                        stringResource(com.ebook.common.R.string.delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(com.ebook.common.R.string.cancel))
                }
            }
        )
    }
}

/**
 * 底部输入栏：多行输入框 + 发送按钮，边距对齐 [CommonUiTokens.pagePadding]。
 *
 * 注意：此处**不能**叠加 `imePadding()`——基类 [com.xrn1997.common.mvvm.compose.BaseActivity]
 * 的 M3 Scaffold 在键盘弹出时已通过内部 insets 动画把内容区底部抬到键盘之上，
 * 再叠加 `imePadding()` 会二次避让，导致输入框悬浮在键盘上方约一个键盘高度（空隙）。
 * 输入栏随键盘抬起完全由 Scaffold 承担（对齐官方 Material 3 边衬区指南：
 * Scaffold 内避免再叠加边衬区修饰符）。
 */
@Composable
private fun CommentInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = CommonUiTokens.pagePadding,
                end = CommonUiTokens.pagePadding,
                bottom = CommonUiTokens.listSpacing
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .weight(2f)
                .padding(end = CommonUiTokens.listSpacing),
            placeholder = { Text(stringResource(R.string.say_something)) },
            minLines = 1,
            maxLines = 3,
            textStyle = MaterialTheme.typography.bodyMedium
        )
        Button(
            onClick = onSend,
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
        ) {
            Text(stringResource(R.string.send))
        }
    }
}

/**
 * 评论条目（ADR-0006 共享设计语言重设计，替代原 adpater_book_comments_item.xml）：
 * 12dp 圆角条目卡（surfaceContainer 语义底），头像 + 用户名头部、正文、时间右对齐，
 * 字号全部走 Material typography，条目间距由列表 spacedBy 承担（不再手绘分割线）。
 */
@Composable
fun CommentItem(comment: BookComment, onLongClick: () -> Unit) {
    // 条目无点击跳转，只保留长按删除；列表密集排布故不叠阴影
    CommonItemCard(onLongClick = onLongClick, shadowElevation = 0.dp) {
        Column {
            // 头部：头像 + 用户名
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = comment.avatar,
                    contentDescription = null,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    placeholder = painterResource(R.drawable.image_default)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = comment.username,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            // 评论内容（左缩进对齐用户名起始位，延续原布局观感）
            Text(
                text = comment.content ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 40.dp)
            )
            // 时间（右对齐）
            Text(
                text = DateUtil.formatDate(comment.addTime, DateUtil.FormatType.yyyyMMddHHmm),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 5.dp)
            )
        }
    }
}
