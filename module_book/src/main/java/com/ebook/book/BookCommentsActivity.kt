package com.ebook.book

import androidx.activity.viewModels
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.ebook.book.mvvm.viewmodel.BookCommentsViewModel
import com.ebook.book.mvvm.viewmodel.isOwnComment
import com.ebook.common.domain.BookComment
import com.ebook.common.domain.CommentTime
import com.ebook.common.event.KeyCode
import com.ebook.common.event.RouteArgs
import com.ebook.common.ui.Avatar
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.CommonItemCard
import com.ebook.common.ui.preview.AppPreview
import com.ebook.common.ui.preview.SAMPLE_CURRENT_USER_ID
import com.ebook.common.ui.preview.sampleComments
import com.therouter.router.Route
import com.xrn1997.common.mvvm.IBaseRefreshView
import com.xrn1997.common.mvvm.compose.BaseMvvmActivity
import com.xrn1997.common.mvvm.util.MvvmBinder
import com.xrn1997.common.ui.LoadMoreFooter
import com.xrn1997.common.ui.RefreshableList
import com.xrn1997.common.ui.loadMoreStateOf
import dagger.hilt.android.AndroidEntryPoint

/**
 * 章节评论区（Compose 版，替代原 ViewBinding + RefreshView 壳实现）。
 *
 * 布局：[RefreshableList] 下拉刷新评论列表 + 底部输入栏（对齐原
 * activity_book_comments.xml 的 12:1 权重结构——列表占主体、输入栏固定底部）。
 *
 * 刷新接线：ViewModel 的 [IBaseRefreshView] 刷新/加载更多信号经 [MvvmBinder] 映射到本地
 * isRefreshing/isLoadingMore/hasMore 状态（BaseRefreshViewModel 回调不直接驱动 View）。
 * 与书架页 [com.ebook.book.page.BookShelfPage] 的 refreshVersion StateFlow 模式分叉，原因：
 * 评论页是独立 Activity 生命周期（非 NavHost 内页面），无 NavBackStackEntry
 * 孤儿 collector 问题，MvvmBinder 在单消费场景下语义足够，
 * 无需引入版本号 StateFlow（见 BookListViewModel 的 Channel 单消费者竞态说明）。
 *
 * 加载更多接线：`enableLoadMore` 挂 hasMore 状态（触底自动触发由 [RefreshableList]
 * 承担），`loadMoreFailed` 在失败后抑制自动重试，由下一次刷新成功解除。
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
            // 写入键：优先取发送方显式传来的主键（spec §9.2「写评论只用 is_primary 那行」）。
            // 不能拿 keys.firstOrNull()——并集查询没有 ORDER BY，修键后主键是后插入的那行。
            // 未传该键的入口只剩「我的评论」页（单键跳来，读写的就是同一个桶），回落首元素即可。
            val writeKey = bundle.getString(RouteArgs.PRIMARY_COMMENT_KEY) ?: keys.firstOrNull()
            val comment = BookComment(
                id = 0, userId = 0, username = "", avatar = "",
                commentKey = writeKey,
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
 * 评论区内容：刷新列表 + 底部输入栏（**无状态根**，AGENTS.md「屏幕的无状态根」）。
 *
 * 只吃不可变状态与回调，于是本页第一次可以直接预览、也可以被 Robolectric 渲染用例组合出来
 * （`BookScreensRenderTest`）。刷新中的四个布尔量在这里是入参而不是本地状态：它们的**写入点**
 * 在 [MvvmBinder] 的回调里（见 [BookCommentsScreen]），把状态收在壳层才能既保住那条链路、
 * 又让这一层与 ViewModel 无关。
 *
 * 参数顺序按「数据 → 刷新状态机 → 输入栏」分三组，与 [BookCommentsScreen] 里的实参一一对应。
 *
 * @param comments 当前已取到的评论（VM 已按时间倒序排好）
 * @param currentUserId 会话用户 id；null 或 <=0 表示未登录，此时任何条目都不给删除入口
 * @param loadMoreFailed 上一轮加载更多失败：为真时抑制触底自动重试，直到下一次刷新成功解除
 * @param onDelete 确认删除某条评论（确认框由 [CommentList] 持有，删除动作本身不在这里发生）
 */
@Composable
fun BookCommentsContent(
    comments: List<BookComment>,
    currentUserId: Long?,
    isRefreshing: Boolean,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    loadMoreFailed: Boolean,
    inputText: String,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onDelete: (BookComment) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        RefreshableList(
            isRefreshing = isRefreshing,
            isLoadingMore = isLoadingMore,
            onRefresh = onRefresh,
            onLoadMore = onLoadMore,
            enableLoadMore = hasMore,
            loadMoreFailed = loadMoreFailed,
            modifier = Modifier.weight(1f)
        ) { listState ->
            CommentList(
                listState = listState,
                comments = comments,
                currentUserId = currentUserId,
                onDelete = onDelete
            )
        }
        CommentInputBar(
            text = inputText,
            onTextChange = onTextChange,
            onSend = onSend
        )
    }
}

/**
 * 评论区页面壳：收集状态流、持有刷新标记与输入文本，把 [BookCommentsContent] 接起来。
 *
 * 参数化 ViewModel 而非在 Composable 内部 hiltViewModel()：ViewModel 由
 * Activity 持有（路由参数在 [BookCommentsActivity.initData] 写入），
 * 页面与 Activity 必须共用同一实例。
 *
 * **所有副作用都留在这一层**（一次性信号绑定、首帧自动刷新、发送成功后清输入并收键盘）：
 * 它们要的是 ViewModel 与生命周期，而根组件只要值与回调。搬动这些效果会改变触发时机，
 * 预览里也复现不出来——所以一侧不动、另一侧才可预览。
 */
@Composable
fun BookCommentsScreen(viewModel: BookCommentsViewModel) {
    val comments by viewModel.list.collectAsState()
    // 本人判定用的会话 userId：经 VM 从 UserSessionManager 取，不在页面里直读 SP
    val currentUserId by viewModel.currentUserId.collectAsState(initial = null)
    var isRefreshing by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var loadMoreFailed by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var inputText by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 刷新/加载更多信号绑定：ViewModel 的一次性信号 → 本地 Compose 状态
    DisposableEffect(lifecycleOwner, viewModel) {
        MvvmBinder.bindRefresh(
            lifecycleOwner,
            object : IBaseRefreshView {
                override fun finishRefresh() {
                    isRefreshing = false
                    // 刷新会重取首页并补发 hasMoreData；这里同时解除上一轮加载失败的自动触发抑制
                    loadMoreFailed = false
                }

                override fun finishLoadMore(success: Boolean) {
                    isLoadingMore = false
                    loadMoreFailed = !success
                }

                override fun setHasMoreData(value: Boolean) {
                    hasMore = value
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

    BookCommentsContent(
        comments = comments,
        currentUserId = currentUserId,
        isRefreshing = isRefreshing,
        isLoadingMore = isLoadingMore,
        hasMore = hasMore,
        loadMoreFailed = loadMoreFailed,
        inputText = inputText,
        onRefresh = {
            isRefreshing = true
            viewModel.refreshData()
        },
        onLoadMore = {
            isLoadingMore = true
            viewModel.loadMore()
        },
        onTextChange = { inputText = it },
        // 发送读的是壳层的 inputText（与根组件收到的同一个值）：清空由上面的
        // mVoidSingleLiveEvent 收集负责，这里不在发送成功前抢先置空
        onSend = { viewModel.addComment(inputText) },
        onDelete = { comment -> viewModel.deleteComment(comment.id) },
    )
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
            title = { Text(stringResource(R.string.delete_comment_title)) },
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
                // 空 URL / 加载中 / 取不到三态的兜底归共享组件（中性色块占位、默认头像收尾）；
                // 原先手写版只在 URL 为空时给默认图，失效链接会留下一个空白圆
                Avatar(
                    url = comment.avatar,
                    modifier = Modifier.size(30.dp),
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
                text = CommentTime.displayText(comment.addTime),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 5.dp)
            )
        }
    }
}

/**
 * 预览：评论区**一条评论都没有**那一档。
 *
 * 这是进页面的第一帧（刷新还没回）与「没人说过的章节」共用的长相。要看的不是空态文案——
 * 本页没有空态组件，列表区就是一片空白——而是**底部输入栏还在不在**：它是这页唯一的动作出口，
 * 被 `weight(1f)` 的列表挤掉就等于整页哑掉，而这件事只在这张图上看得出（列表空时
 * `RefreshableList` 不撑高度，两种写法都不报错）。
 */
@Preview(showBackground = true)
@Composable
private fun BookCommentsEmptyPreview() {
    AppPreview {
        BookCommentsContent(
            comments = emptyList(),
            currentUserId = SAMPLE_CURRENT_USER_ID,
            isRefreshing = false,
            isLoadingMore = false,
            hasMore = true,
            loadMoreFailed = false,
            inputText = "",
            onRefresh = {},
            onLoadMore = {},
            onTextChange = {},
            onSend = {},
            onDelete = {},
        )
    }
}

/**
 * 预览：评论列表三条——**本人 / 他人 / 超长正文 + 解不出的时间**一次排完。
 *
 * 前两条是同一处 `isOwnComment` 的两个结果：它决定长按时弹不弹删除框，图上看不出差别，
 * 所以这一档真正的用途是配着 `BookScreensRenderTest` 的长按用例看（那张图只证明「两种
 * userId 都进了同一张列表」）。后两档是纯观感：正文不限行数（卡片会自己长高，看它与头像
 * 基线是否仍对齐），`addTime` 给不合契约的串时 [CommentTime.displayText] 按口径返回空串——
 * 于是那一格该**留白**，而不是把「2026-09-30」原样画出来，也不是整行塌掉。
 */
@Preview(showBackground = true)
@Composable
private fun BookCommentsListPreview() {
    AppPreview {
        BookCommentsContent(
            comments = sampleComments(3),
            currentUserId = SAMPLE_CURRENT_USER_ID,
            isRefreshing = false,
            isLoadingMore = false,
            hasMore = true,
            loadMoreFailed = false,
            inputText = "",
            onRefresh = {},
            onLoadMore = {},
            onTextChange = {},
            onSend = {},
            onDelete = {},
        )
    }
}

/**
 * 预览：**上一轮加载更多失败**那一档（`loadMoreFailed = true`）。
 *
 * 这两张拍的是页面级的两个互斥结论：失败（还会被下一次刷新解除）与到底（不会再有下一页），
 * 它们由 [BookCommentsContent] 换成容器上的 `enableLoadMore` / `loadMoreFailed` 两个开关。
 * 拼一张就得出一个生产里不存在的状态（失败与到底同时成立）。
 *
 * **footer 那一行本身在这里多半看不见**：静态预览不滚动，而它挂在列表尾部。那句「加载失败 /
 * 没有更多」到底怎么说、给不给重试，看 [CommentLoadMoreFooterPreview]。
 */
@Preview(showBackground = true, widthDp = 360, heightDp = 300)
@Composable
private fun BookCommentsLoadMoreFailedPreview() {
    AppPreview {
        BookCommentsContent(
            comments = sampleComments(2),
            currentUserId = SAMPLE_CURRENT_USER_ID,
            isRefreshing = false,
            // 失败后 isLoadingMore 已被复位、抑制标志置真：这一档看的是「不再自动重试」的长相
            isLoadingMore = false,
            hasMore = true,
            loadMoreFailed = true,
            inputText = "",
            onRefresh = {},
            onLoadMore = {},
            onTextChange = {},
            onSend = {},
            onDelete = {},
        )
    }
}

/** 预览：已到末尾那一档（`hasMore = false`，触底不再发请求）。见 [BookCommentsLoadMoreFailedPreview] */
@Preview(showBackground = true, widthDp = 360, heightDp = 300)
@Composable
private fun BookCommentsNoMorePreview() {
    AppPreview {
        BookCommentsContent(
            comments = sampleComments(2),
            currentUserId = SAMPLE_CURRENT_USER_ID,
            isRefreshing = false,
            isLoadingMore = false,
            hasMore = false,
            loadMoreFailed = false,
            inputText = "",
            onRefresh = {},
            onLoadMore = {},
            onTextChange = {},
            onSend = {},
            onDelete = {},
        )
    }
}

/**
 * 预览：加载更多 footer 的四档（空闲 / 加载中 / 失败 / 到底）。
 *
 * 为什么不只靠上面两张全屏图：footer 只在列表滚到底时才挂进 LazyColumn，静态预览里没有滚动，
 * 于是「失败那一档到底长什么样、有没有给重试」在页面级预览里根本拍不到。这里直接按
 * `loadMoreStateOf` 的四个结果各拍一行，四档同屏才看得出「失败」与「到底」确实是两句话。
 */
@PreviewLightDark
@Composable
private fun CommentLoadMoreFooterPreview() {
    AppPreview {
        Column {
            // 空闲：列表比视口短、还没触发过加载更多
            LoadMoreFooter(state = loadMoreStateOf(false, false, true))
            LoadMoreFooter(state = loadMoreStateOf(true, false, true))
            LoadMoreFooter(state = loadMoreStateOf(false, true, true), onRetry = {})
            LoadMoreFooter(state = loadMoreStateOf(false, false, false))
        }
    }
}

/**
 * 预览：底部输入栏两档——**空**与**已有内容**。
 *
 * 空的那张看 placeholder（「说点什么吧！」）在不在：`OutlinedTextField` 有值时 label 浮起、
 * placeholder 消失，只拍有内容那张就看不见「用户还没打字时这页能不能发东西」。
 * 有内容那张看 `minLines = 1 / maxLines = 3` 的长相与两颗 2:1 权重（写反了按钮会把输入框挤没）。
 * 发送后清空由 VM 的 `mVoidSingleLiveEvent` 驱动（壳层），预览拍不到那个动作，只能拍到结果档。
 */
@PreviewLightDark
@Composable
private fun CommentInputBarPreview() {
    AppPreview {
        Column {
            CommentInputBar(text = "", onTextChange = {}, onSend = {})
            CommentInputBar(text = "这一章的伏笔埋在最后一句。", onTextChange = {}, onSend = {})
        }
    }
}
