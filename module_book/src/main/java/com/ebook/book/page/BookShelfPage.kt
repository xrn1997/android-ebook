package com.ebook.book.page

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ebook.book.ImportBookActivity
import com.ebook.book.ReadBookActivity
import com.ebook.book.mvvm.viewmodel.BookListViewModel
import com.ebook.book.mvvm.viewmodel.BookReadViewModel.Companion.OPEN_FROM_APP
import com.ebook.book.mvvm.viewmodel.DownloadManageViewModel
import com.ebook.book.manager.BitIntentDataManager
import com.ebook.common.event.FROM_BOOKSHELF
import com.ebook.common.event.KeyCode
import com.ebook.common.ui.BookCover
import com.ebook.common.ui.CommonItemCard
import com.ebook.common.ui.CommonUiTokens
import com.ebook.db.entity.BookShelfEntity
import com.therouter.TheRouter
import com.xrn1997.common.mvvm.IBaseRefreshView
import com.xrn1997.common.mvvm.util.MvvmBinder
import com.xrn1997.common.ui.RefreshableList
import com.ebook.book.R

/**
 * 书架页（Compose）：替代原 MainBookFragment（ViewBinding + RefreshView 壳）。
 *
 * - 顶栏：[TopAppBar] 文字标题 + 导入/下载 actions（对齐书城页形态，ADR-0006 共享设计语言）
 * - 刷新容器：lib_common 的 [RefreshableList]；刷新信号经 [MvvmBinder] 映射到本地状态
 * - 下载入口：下载图标跳转下载管理页（[com.ebook.book.DownloadManageActivity]），
 *   有任务时以角标显示队列剩余数（原 80dp 小弹窗已下线）
 * - 书架变化事件收集已移入 ViewModel（BookListViewModel）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookShelfPage(
    viewModel: BookListViewModel = hiltViewModel(),
    downloadViewModel: DownloadManageViewModel = hiltViewModel(),
) {
    val books by viewModel.list.collectAsState()
    val context = LocalContext.current
    var isRefreshing by remember { mutableStateOf(false) }
    // 队列剩余数（下载图标角标）：任务增删时由 Room Flow 自动重推，无任务时为 0（角标隐藏）
    val downloadRemaining by downloadViewModel.remainingCount.collectAsState()
    // 刷新信号绑定（@Composable 版）：绑定生命周期归组合控制，进出 Tab 自动绑/解绑，
    // 不再残留孤儿 collector（原 refreshVersion 自建模式已删除）。view 用 remember 稳定引用避免重组重绑
    val refreshView = remember {
        object : IBaseRefreshView {
            override fun finishRefresh() {
                isRefreshing = false
            }
        }
    }
    MvvmBinder.bindRefresh(view = refreshView, viewModel = viewModel)

    // 首次进入自动刷新（置转圈 → refreshData → stopRefresh 信号复位）
    LaunchedEffect(Unit) {
        isRefreshing = true
        viewModel.refreshData()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶栏（对齐书城页：TopAppBar 文字标题 + 导入/下载 actions）
        TopAppBar(
            title = { Text(stringResource(R.string.my_book_shelf)) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            actions = {
                // 导入本地书（点击反馈由 Material ripple 承担）：
                // 用 startActivity 而非 TheRouter——@Route 是为跨模块跳转准备的，
                // ImportBookActivity 只被本页使用、未挂路由，直启即可（右侧下载管理入口
                // 同样在本模块内，走路由是为与独立模式的调试宿主共用同一跳法）
                IconButton(onClick = {
                    context.startActivity(Intent(context, ImportBookActivity::class.java))
                }) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.add_local_book)
                    )
                }
                // 下载管理入口：跳转下载管理页；有任务时角标展示队列剩余数，
                // 让用户不点开也能知道“还有多少在下”（原小弹窗已下线）
                IconButton(onClick = {
                    TheRouter.build(KeyCode.Book.DOWNLOAD_PATH).navigation(context)
                }) {
                    BadgedBox(
                        badge = {
                            if (downloadRemaining > 0) {
                                Badge { Text(downloadRemaining.toString()) }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = stringResource(R.string.download)
                        )
                    }
                }
            }
        )

        RefreshableList(
            isRefreshing = isRefreshing,
            isLoadingMore = false,
            onRefresh = {
                isRefreshing = true
                viewModel.refreshData()
            },
            onLoadMore = { viewModel.loadMore() },
            enableLoadMore = false,
        ) { listState ->
            BookShelfList(
                listState = listState,
                books = books,
                onItemClick = { bookShelf ->
                    val intent = Intent(context, ReadBookActivity::class.java)
                    intent.putExtra("from", OPEN_FROM_APP)
                    intent.putExtra("data_key", BitIntentDataManager.putData(bookShelf.copy()))
                    context.startActivity(intent)
                },
                onItemLongClick = { bookShelf ->
                    val key = BitIntentDataManager.putData(bookShelf.copy())
                    TheRouter.build(KeyCode.Book.DETAIL_PATH)
                        .withInt("from", FROM_BOOKSHELF)
                        .withString("data_key", key)
                        .navigation()
                }
            )
        }
    }
}

/**
 * 书架列表：页面边距/条目间距走 [CommonUiTokens]（ADR-0006 共享设计语言）。
 */
@Composable
fun BookShelfList(
    listState: LazyListState,
    books: List<BookShelfEntity>,
    onItemClick: (BookShelfEntity) -> Unit,
    onItemLongClick: (BookShelfEntity) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = CommonUiTokens.pagePadding,
            end = CommonUiTokens.pagePadding,
            top = CommonUiTokens.listSpacing,
            bottom = CommonUiTokens.pagePadding
        ),
        verticalArrangement = Arrangement.spacedBy(CommonUiTokens.listSpacing)
    ) {
        items(books, key = { it.noteUrl }) { bookShelf ->
            BookShelfItem(
                bookShelf = bookShelf,
                onItemClick = { onItemClick(bookShelf) },
                onItemLongClick = { onItemLongClick(bookShelf) }
            )
        }
    }
}

/**
 * 书架条目（ADR-0006 共享设计语言重设计，替代原 adapter_book_list_item.xml 的
 * 重阴影卡 + 等宽字体样式）：12dp 圆角条目卡 + [BookCover] 封面 + Material typography。
 */
@Composable
fun BookShelfItem(
    bookShelf: BookShelfEntity,
    onItemClick: () -> Unit,
    onItemLongClick: () -> Unit
) {
    CommonItemCard(onClick = onItemClick, onLongClick = onItemLongClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 封面：共享 BookCover（条目内小封面用小圆角变体）
            BookCover(
                url = bookShelf.bookInfo?.coverUrl ?: "",
                contentDescription = stringResource(R.string.cover),
                modifier = Modifier.size(width = 72.dp, height = 105.dp),
                shape = RoundedCornerShape(6.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = bookShelf.bookInfo?.name ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = bookShelf.bookInfo?.author ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    // 读 bookShelf.chapterList（书架查询时由 getAllBooksWithDetails() 回填；本地书由
                    // BookImportManager 回填），不用 bookInfo.chapterList——它是 @Ignore 不入库、书架流不填充，
                    // 会导致"读至："后为空。与 ReadBookActivity.kt 取章节列表的约定一致。
                    text = stringResource(R.string.read_to) +
                            (bookShelf.chapterList.getOrNull(bookShelf.durChapter)?.durChapterName ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
