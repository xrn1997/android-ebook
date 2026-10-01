package com.ebook.book.page

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.outlined.MenuBook
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
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
import com.ebook.common.ui.BookItemFrame
import com.ebook.common.ui.BookItemLayout
import com.ebook.common.ui.BookItemMetaRow
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.EmptyState
import com.ebook.common.ui.bookItemLineCount
import com.ebook.common.ui.preview.AppPreview
import com.ebook.common.ui.preview.sampleShelfBook
import com.ebook.common.ui.preview.sampleShelfBooks
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
    onGoBookstore: (() -> Unit)? = null,
) {
    val books by viewModel.list.collectAsState()
    // 正在解析中的导入：书架行要等落库才有，占位行由进程级导入协调器供数（spec §6）
    val parsingBooks by viewModel.parsingBooks.collectAsState()
    val context = LocalContext.current
    var isRefreshing by remember { mutableStateOf(false) }
    // 首轮刷新是否已落定。空态只能等第一次查询回来才判：list 的初值恒为空列表，
    // 若首帧就按"空"渲染，有书用户冷启动会先看到一帧「书架还是空的 / 去书城挑一本」的误导文案
    // （与书城 LibrarySourceState.Unknown 同一条口径：还没有结论时不画结论）。落定后即恒定，
    // 之后再下拉刷新时空态保持在场、不来回闪。
    var initialLoadDone by remember { mutableStateOf(false) }
    // 队列剩余数（下载图标角标）：任务增删时由 Room Flow 自动重推，无任务时为 0（角标隐藏）
    val downloadRemaining by downloadViewModel.remainingCount.collectAsState()
    // 刷新信号绑定（@Composable 版）：绑定生命周期归组合控制，进出 Tab 自动绑/解绑，
    // 不再残留孤儿 collector（原 refreshVersion 自建模式已删除）。view 用 remember 稳定引用避免重组重绑
    val refreshView = remember {
        object : IBaseRefreshView {
            override fun finishRefresh() {
                isRefreshing = false
                // 刷新收尾（成功、失败都走 finally 里的 updateStopRefresh）即视为已落定：
                // 失败时也不能一直留空——那时确实没有可展示的数据，空态比无限留白更诚实
                initialLoadDone = true
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
                BookShelfBody(
                    listState = listState,
                    books = books,
                    parsingBooks = parsingBooks,
                    initialLoadDone = initialLoadDone,
                    onGoBookstore = onGoBookstore,
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
 * 书架列表区的**无状态根**：真空态 / 解析中占位 / 书目三种画法的分流，只吃不可变状态与回调。
 *
 * 抽出来只为可测——「首轮刷新落定前不画空态」是**首帧**行为，写错了既不崩也不报错，
 * 只在真机上闪一瞬间（见 [BookShelfPage] 里的 `initialLoadDone`），只有 Robolectric 真渲染
 * 才锁得住（用例见 `BookShelfBodyRenderTest`）。取 ViewModel、跳转、顶栏这些宿主职责
 * 仍留在 [BookShelfPage] 里。
 */
@Composable
internal fun BookShelfBody(
    listState: LazyListState,
    books: List<BookShelfEntity>,
    parsingBooks: List<ParsingBook>,
    initialLoadDone: Boolean,
    onGoBookstore: (() -> Unit)?,
    onItemClick: (BookShelfEntity) -> Unit,
    onItemLongClick: (BookShelfEntity) -> Unit,
) {
    // 书目与"解析中"占位都为空即是真空态。二者分开判：只剩占位行时该显示的是
    // 那几行"解析中"，不是空态文案（导入落库的那几秒正是用户唯一在看的反馈）。
    // 首轮落定前整片留空：那一刻的"空"是 list 初值，不是结论（见 initialLoadDone）。
    if (books.isEmpty() && parsingBooks.isEmpty()) {
        if (initialLoadDone) {
            ShelfEmptyState(listState = listState, onGoBookstore = onGoBookstore)
        }
    } else {
        BookShelfList(
            listState = listState,
            books = books,
            parsing = parsingBooks,
            onItemClick = onItemClick,
            onItemLongClick = onItemLongClick,
        )
    }
}

/**
 * 书架空态：首启、或删掉最后一本书之后，这一屏原本是一片纯空白（既没说"没有书"也没有去处）。
 *
 * 形态与文案走共享 [EmptyState]——与下载管理、我的评论、书城的空态同一套词汇，
 * 不再各页自己拼"图标多大、文案什么字号"。
 *
 * **动作槽由宿主注入**：书城是宿主（module_main）NavHost 的目的地，模块内没有这条通路，
 * 故 [onGoBookstore] 由 `IBookProvider` 的调用方传入；拿不到（模块独立运行的调试宿主）时
 * 动作整块不渲染，**不留一个点不动的按钮**。
 *
 * 仍然渲染成 [LazyColumn] 而不是换成一个纯 Box：下拉刷新的手势需要可滚动子节点接住，
 * 空态同样要能下拉（删书/导入在别的设备上也可能是远端变化）。
 * `fillParentMaxSize` 让这一项撑满一屏，内容在整片可视区里居中。
 *
 * **调用点只在首轮刷新落定后渲染它**（见 `initialLoadDone`）：首帧列表还是初值空表，
 * 那时画空态是对"有没有书"提前下结论。
 */
@Composable
private fun ShelfEmptyState(listState: LazyListState, onGoBookstore: (() -> Unit)?) {
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item {
            Box(
                modifier = Modifier
                    .fillParentMaxSize()
                    .padding(horizontal = CommonUiTokens.pagePadding),
                contentAlignment = Alignment.Center
            ) {
                EmptyState(
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    title = stringResource(R.string.shelf_empty_title),
                    hint = stringResource(R.string.shelf_empty_hint),
                    actionText = if (onGoBookstore != null) {
                        stringResource(R.string.shelf_empty_action)
                    } else {
                        null
                    },
                    onAction = onGoBookstore
                )
            }
        }
    }
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
    BookItemFrame(
        coverUrl = coverUrl,
        coverDescription = coverDescription,
        title = title,
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = BookItemLayout.middleSpacing)
        ) { middle() }
        Spacer(modifier = Modifier.weight(1f))
        BookItemMetaRow(bottomLeft, bottomRight)
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
    BookItemFrame(
        coverUrl = coverUrl,
        coverDescription = coverDescription,
        title = title,
        enabled = enabled,
        onClick = onClick,
        onLongClick = onLongClick,
        // 不可点的那一档（"解析中"占位行）不投影：还没落库的行看着像能点，只会诱使用户去点
        shadowElevation = if (enabled) 1.dp else 0.dp,
    ) {
        Spacer(modifier = Modifier.weight(1f))
        Box(modifier = Modifier.fillMaxWidth()) { middle() }
        Spacer(modifier = Modifier.weight(1f))
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
 * （见 [BookItemFrame]、[shelfInfoRows]）。
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

/**
 * 预览：书架列表。一张图里同时给出这几种容易各写各的形态：
 * 有简介 / 无简介（中格只剩「第 N 章 …」）、未开始读（`durChapter = 0`，底行的「读至」该说什么）、
 * 以及排在最前的**解析中占位行**（导入刚落库那几秒用户唯一看得见的反馈）。
 *
 * 等高是这一页的硬约束：`ShelfItemFull` 与 `ShelfItemCompact` 由 [shelfInfoRows] 分流，
 * 分流判据错一格就长成"一张顶对齐、底下空一整片"的卡——只有把两种卡放进同一屏才看得出来。
 */
@PreviewLightDark
@Composable
private fun BookShelfListPreview() {
    AppPreview {
        BookShelfList(
            listState = rememberLazyListState(),
            books = sampleShelfBooks(3) + sampleShelfBook(index = 4, durChapter = 0),
            parsing = listOf(ParsingBook(id = "parsing-1", title = "雾都手记")),
            onItemClick = {},
            onItemLongClick = {},
        )
    }
}

/**
 * 预览：空态·宿主给了去处。
 *
 * 「去书城」是宿主 NavHost 的动作，经 `IBookProvider.mainBookPage` 的 `onGoBookstore` 传进来；
 * 这一档是集成运行时的样子。
 */
@Preview(showBackground = true, widthDp = 360, heightDp = 360)
@Composable
private fun ShelfEmptyStateWithActionPreview() {
    AppPreview {
        ShelfEmptyState(listState = rememberLazyListState(), onGoBookstore = {})
    }
}

/**
 * 预览：空态·模块独立运行（拿不到 `onGoBookstore`）。
 *
 * 这一档要看的正是**动作整块不出现**：留一句「去书城找书」却点不动，比什么都不说更糟。
 * 与 [ShelfEmptyStateWithActionPreview] 成对，才看得出降级分支真的降了级。
 */
@Preview(showBackground = true, widthDp = 360, heightDp = 360)
@Composable
private fun ShelfEmptyStateWithoutActionPreview() {
    AppPreview {
        ShelfEmptyState(listState = rememberLazyListState(), onGoBookstore = null)
    }
}
