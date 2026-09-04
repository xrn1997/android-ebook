package com.ebook.find.page

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ebook.common.event.FROM_SEARCH
import com.ebook.common.event.KeyCode
import com.ebook.common.ui.BookCover
import com.ebook.common.ui.CommonCard
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.InfoChip
import com.ebook.common.ui.SectionLabel
import com.ebook.db.entity.LibraryKindBookListEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.find.R
import com.ebook.find.entity.BookType
import com.ebook.find.mvvm.viewmodel.LibraryViewModel
import com.therouter.TheRouter
import com.xrn1997.common.mvvm.IBaseRefreshView
import com.xrn1997.common.mvvm.util.MvvmBinder
import com.xrn1997.common.ui.RefreshableList

/**
 * 书城页（Compose）：替代原 MainFindFragment（ViewBinding + RefreshView 壳）。
 *
 * - 刷新容器：lib_common 的 [RefreshableList]（Material3 PullToRefreshBox）
 * - 刷新信号：ViewModel 的 internal Channel 只能经 [MvvmBinder] 消费，
 *   经 [IBaseRefreshView] 映射到本地 isRefreshing 状态
 * - 工具栏：原 BaseMvvmRefreshFragment 的 toolbarView → [TopAppBar]（colorSurface 语义色）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookstorePage(viewModel: LibraryViewModel = hiltViewModel()) {
    val kindBooks by viewModel.list.collectAsState()
    val bookTypes = viewModel.bookTypeList
    var isRefreshing by remember { mutableStateOf(false) }
    // 刷新信号绑定（@Composable 版）：绑定生命周期归组合控制，进出 Tab 自动绑/解绑——
    // 旧写法把 binder 包进 DisposableEffect 无法取消其内部挂 lifecycleScope 的协程，会残留孤儿 collector
    val refreshView = remember {
        object : IBaseRefreshView {
            override fun finishRefresh() {
                isRefreshing = false
            }
        }
    }
    MvvmBinder.bindRefresh(view = refreshView, viewModel = viewModel)

    // 首次进入自动刷新（对齐原 Fragment 的 triggerRefresh()）
    LaunchedEffect(Unit) {
        isRefreshing = true
        viewModel.refreshData()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 原 toolbarView：书城标题 + colorSurface 背景
        TopAppBar(
            title = { Text(stringResource(R.string.bookstore_title)) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
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
            LibraryContent(listState, kindBooks, bookTypes)
        }
    }
}

/**
 * 书城内容（ADR-0006 共享设计语言重设计）：
 * 「书籍类型」分组标题 + 胶囊流式标签卡 + 搜索胶囊 + 分类书籍区块。
 *
 * 旧实现的实底色块/重阴影/硬编码字号全部替换为共享组件（[SectionLabel]/
 * [CommonCard]/[InfoChip]/[BookCover]）+ Material typography + 语义色。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryContent(
    listState: LazyListState,
    kindBooks: List<LibraryKindBookListEntity>,
    bookTypes: List<BookType>
) {
    val context = LocalContext.current
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = CommonUiTokens.pagePadding,
            end = CommonUiTokens.pagePadding,
            bottom = CommonUiTokens.pagePadding
        ),
        verticalArrangement = Arrangement.spacedBy(CommonUiTokens.sectionSpacing)
    ) {
        // "书籍类型"分组标题（共享 SectionLabel）
        item {
            SectionLabel(
                text = stringResource(R.string.book_type),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        // 书类型胶囊：流式排布（替代旧 4 列网格，长分类名不再被 80dp 卡截断）
        item {
            CommonCard(modifier = Modifier.fillMaxWidth()) {
                FlowRow(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    bookTypes.forEach { bookType ->
                        BookTypeChip(bookType) {
                            // 跳分类选书页（url/title 经 TheRouter withString 落到 intent extras，
                            // ChoiceBookActivity 直接读 extras：url 经 SavedStateHandle、title 在 onCreate 读取，不用 @Autowired）
                            TheRouter.build(KeyCode.Find.CHOICE_PATH)
                                .withString("url", bookType.url)
                                .withString("title", bookType.bookType)
                                .navigation(context)
                        }
                    }
                }
            }
        }
        // 搜索胶囊（与搜索页输入框同一形态：全胶囊 50 圆角 + surfaceVariant 弱化底）
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(50)
                    )
                    .clickable { TheRouter.build(KeyCode.Find.SEARCH_PATH).navigation(context) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.search_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // 分类书籍列表（对齐原 lkbv_kindbooklist）
        items(kindBooks, key = { it.kindName }) { kind ->
            KindBookSection(kind)
        }
    }
}

/**
 * 书类型胶囊：共享 [InfoChip] 的胶囊形态（50 圆角 + primaryContainer 语义色 + labelLarge），
 * 替代旧「实底 primary 卡 + 重阴影」的 80dp 网格卡。
 *
 * primaryContainer 是 module_find 可交互胶囊的统一底色（搜索页历史词条同色，
 * 配色规则见 SearchActivity HistoryPanel KDoc）。
 */
@Composable
private fun BookTypeChip(bookType: BookType, onClick: () -> Unit) {
    InfoChip(
        text = bookType.bookType,
        shape = RoundedCornerShape(50),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        textStyle = MaterialTheme.typography.labelLarge,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        onClick = onClick
    )
}

/**
 * 分类书籍区块：分类名（标题样式）+ 更多（主色文本按钮）+ 横向书籍列表。
 */
@Composable
private fun KindBookSection(kind: LibraryKindBookListEntity) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = kind.kindName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (kind.kindUrl.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.more),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            TheRouter.build(KeyCode.Find.CHOICE_PATH)
                                .withString("url", kind.kindUrl)
                                .withString("title", kind.kindName)
                                .navigation(context)
                        }
                        .padding(4.dp)
                )
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(kind.books, key = { it.noteUrl }) { searchBook ->
                LibraryBookCard(searchBook)
            }
        }
    }
}

/**
 * 横向书籍卡片：封面（共享 [BookCover]，外包 Card 提供轻阴影）+ 书名 + 作者。
 */
@Composable
private fun LibraryBookCard(searchBook: SearchBookEntity) {
    Column(
        modifier = Modifier
            .width(101.dp)
            .clickable {
                TheRouter.build(KeyCode.Book.DETAIL_PATH)
                    .withInt("from", FROM_SEARCH)
                    .withObject("data", searchBook)
                    .navigation()
            }
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(123.dp),
            shape = RoundedCornerShape(CommonUiTokens.coverCorner),
            elevation = CardDefaults.cardElevation(1.dp)
        ) {
            BookCover(
                url = searchBook.coverUrl,
                contentDescription = searchBook.name,
                modifier = Modifier.fillMaxSize()
            )
        }
        Text(
            text = searchBook.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        )
        Text(
            text = searchBook.author,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
