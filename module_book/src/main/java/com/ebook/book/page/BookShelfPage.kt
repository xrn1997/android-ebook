package com.ebook.book.page

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.ebook.common.importer.ParsingBook
import com.ebook.common.ui.BookCover
import com.ebook.common.ui.BookItemLayout
import com.ebook.common.ui.CommonItemCard
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.bookItemLineCount
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
 *   有任务时以角标显示队列剩余数（原 80dp 小弹窗已下线）。角标由 [DownloadQueueAction]
 *   自行排布、贴在图标右上角，不是 M3 BadgedBox 的悬浮形态（理由见其 KDoc）
 * - 书架变化事件收集已移入 ViewModel（BookListViewModel）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookShelfPage(
    viewModel: BookListViewModel = hiltViewModel(),
    downloadViewModel: DownloadManageViewModel = hiltViewModel(),
) {
    val books by viewModel.list.collectAsState()
    // 正在解析中的导入：书架行要等落库才有，占位行由进程级导入协调器供数（spec §6）
    val parsingBooks by viewModel.parsingBooks.collectAsState()
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

    // 书架首次加载：拉一次数据
    LaunchedEffect(Unit) {
        isRefreshing = true
        viewModel.refreshData()
    }

    // Scaffold 在本页只承担两件事，且都不产生内容偏移：
    // 1) 页面底色——独立运行宿主（src/main/test/debug/MainActivity）直接组合本页、外面没有
    //    别的 Surface，底色得由本页自己给出；
    // 2) 把内容区 insets 归零——状态栏避让**下沉到顶栏**（TopAppBar 自带 windowInsets，
    //    与书城页同款形态；宿主 MainActivity 已关掉基类偏移、总 Scaffold 也置零）。
    //    这里若改用默认的 ScaffoldDefaults.contentWindowInsets，innerPadding 会再叠一层
    //    状态栏高度，与顶栏自带的避让重复。
    // 因此 innerPadding 恒为 0：不再套 .padding(innerPadding) 这层无操作包装，
    // 只保留 insets 归零这一处显式声明（删掉它才是真的改了视觉行为）。
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
    ) {
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
                    DownloadQueueAction(remaining = downloadRemaining) {
                        TheRouter.build(KeyCode.Book.DOWNLOAD_PATH).navigation(context)
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
                    parsing = parsingBooks,
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
        } // Column
    } // Scaffold
}

/**
 * 书架顶栏的下载管理入口：下载图标 + 队列剩余数角标。
 *
 * 抽成独立可组合函数只为可测——角标的缺陷是「布局上存在、画出来被祖先 clip 切掉」，
 * 只有真渲染数像素才能判红，故必须能被单独组合进一个顶栏里跑。
 *
 * **角标为什么不用 `BadgedBox`**：1.4.0 起 `IconButton` 的容器自带 `.clip(shape)`，而
 * `BadgedBox` 是把角标悬浮到锚点右上角之外（横向 `badgeX = 锚点宽 - 12dp` 再向右生长），
 * 超出的部分就地被切——实测剩余数到 2 位右端就已经是齐边切口，3 位以上只剩两位数字。
 * 反过来把 `BadgedBox` 整体挪到按钮外面也不通：下载图标是最右侧的 action，角标会长到窗口
 * 右边界外被裁。故自己排布：图标与角标互为兄弟，角标贴在 Box 的右上角并**参与布局**——数字再长
 * 也只是这一格变宽（把图标往左推），不会越过任何 clip 边界。代价是角标压在图标右上角。
 */
@Composable
internal fun DownloadQueueAction(remaining: Int, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = stringResource(R.string.download)
            )
        }
        if (remaining > 0) {
            Badge(modifier = Modifier.align(Alignment.TopEnd)) {
                Text(remaining.toString())
            }
        }
    }
}

/**
 * 书架列表：页面边距/条目间距走 [CommonUiTokens]（ADR-0006 共享设计语言）。
 *
 * [parsing] 是正在解析中的导入，排在书目之前——书架行要等导入落库才有，
 * 用户"点完导入回到书架"看到的第一个反馈就是这些"解析中"行（spec §6）。
 */
@Composable
fun BookShelfList(
    listState: LazyListState,
    books: List<BookShelfEntity>,
    parsing: List<ParsingBook> = emptyList(),
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
        items(parsing, key = { it.id }) { book ->
            ParsingShelfItem(title = book.title)
        }
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
 * 书架条目能凑出几行有效信息（书名恒一行）：读至一行 + 元信息（作者 / 共 N 章）一行。
 *
 * 与找书条目同一套思路：**形态由条数决定，布局内部不留分支**。这条判据单独成函数以便钉住
 * （见 ShelfItemRowsTest）——它错一格就是一张顶对齐、底下空一整片的卡。
 */
internal fun shelfInfoRows(readToChapter: String, author: String, chapterCount: Int): Int =
    1 + (if (readToChapter.isNotEmpty()) 1 else 0) +
        (if (author.isNotEmpty() || chapterCount > 0) 1 else 0)

/**
 * 书架条目的外壳：定高正文列 + 封面 + 书名。与 [com.ebook.find.view.SearchBookItem] 的
 * `BookItemFrame` 同一档尺寸（[BookItemLayout]：列高 76dp、封面 57×76 即 3:4、卡内边距 10dp
 * → 卡片恒 96dp），所以翻到的书和书架上的书扫下来一样高；改档两处跟着一起动。
 *
 * 顶对齐是刻意的：封面顶边与书名顶边是同一条基线，谁都不该因为比书名高就把它挤到中间。
 *
 * @param body 书名之下那一坨（`ColumnScope`，所以两个变体能用 weight 排空白）
 */
@Composable
private fun ShelfItemFrame(
    coverUrl: String,
    coverDescription: String?,
    title: String,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    body: @Composable ColumnScope.() -> Unit,
) {
    val typography = MaterialTheme.typography
    CommonItemCard(
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        shadowElevation = if (enabled) 1.dp else 0.dp,
        contentPadding = PaddingValues(10.dp),
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
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(BookItemLayout.columnHeight)
                    .padding(start = 12.dp)
            ) {
                Text(
                    text = title,
                    style = typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                body()
            }
        }
    }
}

/**
 * 三行形态：书名 → 中间行（紧跟，恒定 [BookItemLayout.middleSpacing]）→ 元信息贴底。
 * 空白只有一处，落在中间行与元信息之间；三行齐全时不把行距摊开。
 */
@Composable
private fun ShelfItemFull(
    coverUrl: String,
    coverDescription: String?,
    title: String,
    bottomLeft: String,
    bottomRight: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    middle: @Composable () -> Unit,
) {
    ShelfItemFrame(
        coverUrl = coverUrl,
        coverDescription = coverDescription,
        title = title,
        enabled = true,
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = BookItemLayout.middleSpacing)
        ) { middle() }
        Spacer(modifier = Modifier.weight(1f))
        ShelfItemMetaRow(bottomLeft, bottomRight)
    }
}

/**
 * 两行形态：书名 + 一行信息，那一行在剩下的地方上下等分、垂直居中。
 * 只有两行内容时若仍顶对齐，卡片底下会空出一整片。布局里没有条件分支。
 */
@Composable
private fun ShelfItemCompact(
    coverUrl: String,
    coverDescription: String?,
    title: String,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    middle: @Composable () -> Unit,
) {
    ShelfItemFrame(
        coverUrl = coverUrl,
        coverDescription = coverDescription,
        title = title,
        enabled = enabled,
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        Spacer(modifier = Modifier.weight(1f))
        Box(modifier = Modifier.fillMaxWidth()) { middle() }
        Spacer(modifier = Modifier.weight(1f))
    }
}

/**
 * 元信息行：左（作者）+ 右（共 N 章）。右列空串时不渲染，但左列仍带 weight 占满整行宽，
 * 免得只有一项时它被挤到一半位置。
 */
@Composable
private fun ShelfItemMetaRow(bottomLeft: String, bottomRight: String) {
    val typography = MaterialTheme.typography
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = bottomLeft,
            style = typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (bottomRight.isNotEmpty()) {
            Text(
                text = bottomRight,
                style = typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
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

/**
 * "解析中"占位行：走 [ShelfItemCompact]（只有书名 + 一行状态），小转圈 + 解析中文案居中。
 * 不可点击——章文件与索引行都还没落库，此刻点进去只会看到空白书。
 */
@Composable
private fun ParsingShelfItem(title: String) {
    ShelfItemCompact(
        coverUrl = "",
        coverDescription = null,
        title = title,
        enabled = false,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.shelf_parsing, title),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 书架条目：**书名 → 读至：当前章名 → 作者（左）· 共 N 章（右）**，
 * 与搜索结果 / 分类选书条目同一档尺寸、同一套"按条数选形态"的做法
 * （见 [ShelfItemFrame]、[shelfInfoRows]）。
 *
 * 中间行放"读至"而不是简介：书架上的书是用户已经决定读的，"更到哪了、读到哪了"才是
 * 这一屏要回答的问题；章节名往往很长，所以它的行数和找书条目的简介一样由中间区高度算出来
 * （[bookItemLineCount]）——系统字号越大显示的行越少，卡片始终等高。
 * 没读到任何章节（章列表为空）时那一格不渲染，两行内容就走 [ShelfItemCompact]。
 *
 * 点击进阅读器，长按进详情页（修键面板的入口在详情页正文底部）。
 */
@Composable
fun BookShelfItem(
    bookShelf: BookShelfEntity,
    onItemClick: () -> Unit,
    onItemLongClick: () -> Unit,
) {
    val typography = MaterialTheme.typography
    // 读 bookShelf.chapterList（书架查询时由 getAllBooksWithDetails() 回填；本地书由
    // LocalBookImporter 回填），不用 bookInfo.chapterList——它是 @Ignore 不入库、书架流不填充，
    // 会导致"读至："后为空。与 ReadBookActivity.kt 取章节列表的约定一致。
    val chapters = bookShelf.chapterList
    val readToChapter = chapters.getOrNull(bookShelf.durChapter)?.durChapterName.orEmpty()
    val author = bookShelf.bookInfo?.author ?: ""
    val maxLines = bookItemLineCount(
        columnHeight = BookItemLayout.columnHeight,
        nameStyle = typography.titleSmall,
        bottomStyle = typography.labelSmall,
        bodyStyle = typography.bodySmall,
        spacing = BookItemLayout.middleSpacing,
    )
    val middle: @Composable () -> Unit = {
        if (maxLines > 0 && readToChapter.isNotEmpty()) {
            Text(
                text = stringResource(R.string.read_to) + readToChapter,
                style = typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    val coverUrl = bookShelf.bookInfo?.coverUrl ?: ""
    val coverDescription = stringResource(R.string.cover)
    val title = bookShelf.bookInfo?.name ?: ""
    val bottomRight = if (chapters.isNotEmpty()) {
        stringResource(R.string.chapter_count_format, chapters.size)
    } else {
        ""
    }
    if (shelfInfoRows(readToChapter, author, chapters.size) >= 3) {
        ShelfItemFull(
            coverUrl = coverUrl,
            coverDescription = coverDescription,
            title = title,
            bottomLeft = author,
            bottomRight = bottomRight,
            onClick = onItemClick,
            onLongClick = onItemLongClick,
            middle = middle,
        )
    } else {
        ShelfItemCompact(
            coverUrl = coverUrl,
            coverDescription = coverDescription,
            title = title,
            onClick = onItemClick,
            onLongClick = onItemLongClick,
            middle = middle,
        )
    }
}
