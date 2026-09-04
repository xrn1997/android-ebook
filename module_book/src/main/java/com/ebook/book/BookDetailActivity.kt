package com.ebook.book

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ebook.book.manager.BitIntentDataManager
import com.ebook.book.mvvm.viewmodel.BookDetailViewModel
import com.ebook.book.mvvm.viewmodel.BookReadViewModel.Companion.OPEN_FROM_APP
import com.ebook.common.event.FROM_BOOKSHELF
import com.ebook.common.event.KeyCode
import com.ebook.common.ui.BookCover
import com.ebook.common.ui.CommonCard
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.InfoChip
import com.ebook.common.ui.SectionLabel
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.SearchBookEntity
import com.therouter.TheRouter
import com.therouter.router.Autowired
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseMvvmActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
@Route(path = KeyCode.Book.DETAIL_PATH)
class BookDetailActivity : BaseMvvmActivity<BookDetailViewModel>() {
    override val viewModel: BookDetailViewModel by viewModels()
    @Autowired(name = "from")
    var openFrom = FROM_BOOKSHELF

    @Autowired(name = "data")
    var searchBook: SearchBookEntity? = null

    @Autowired(name = "data_key")
    var dataKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        TheRouter.inject(this)
        super.onCreate(savedInstanceState)
    }

    override fun initData() {
        if (openFrom == FROM_BOOKSHELF) {
            dataKey?.let {
                // 书架入口：本地实体数据完整，直接展示，不重拉网络（对齐原实现：
                // 原代码仅 FROM_SEARCH 且未持有时才发请求；无条件重拉会在失败时
                // 把本地好书置空，导致「开始阅读」断链）
                (BitIntentDataManager.getData(it) as? BookShelfEntity)?.let { shelf ->
                    viewModel.initFromBookShelf(shelf)
                }
                BitIntentDataManager.cleanData(it)
            }
        } else {
            searchBook?.let {
                viewModel.initFromSearch(it)
                // 搜索入口才需要网络拉取详情/章节列表（对齐原实现调用条件）
                viewModel.getBookShelfInfo()
            }
        }
        // 基类 Toolbar 显示书名标题
        toolbarTitle.value = viewModel.mBookShelf?.bookInfo?.name ?: viewModel.searchBook?.name ?: ""
    }

    @Composable
    override fun PageContent() {
        BookDetailScreen(
            viewModel = viewModel,
            onReadClick = {
                // 空守卫：详情未就绪/拉取失败时章节数据缺失，进阅读器必死链（空白页）；
                // 按钮侧已同步 disabled，此处再兜底一层防竞态点击
                val shelf = viewModel.mBookShelf ?: return@BookDetailScreen
                val intent = Intent(this, ReadBookActivity::class.java)
                intent.putExtra("from", OPEN_FROM_APP)
                intent.putExtra("data_key", BitIntentDataManager.putData(shelf.copy()))
                startActivity(intent)
                finish()
            },
            onShelfClick = {
                if (viewModel.inBookShelf) {
                    viewModel.removeFromBookShelf()
                } else {
                    viewModel.addToBookShelf()
                }
            }
        )
    }
}

/**
 * 书籍详情内容（ADR-0006 共享设计语言重设计）：
 * [BookCover] 封面 + 右侧信息列（书名/作者/来源 [InfoChip]/章节信息）的头部，
 * 简介用 [SectionLabel] + [CommonCard]，字号全部走 Material typography。
 *
 * 状态经 [BookDetailViewModel.detailState]（StateFlow）驱动：网络拉取完成后自动重组；
 * 加载中/失败（可点重试）对齐原 tvLoading 行为；章节行与阅读按钮文案对齐原实现：
 * 在书架显示「观看至/继续阅读」，不在书架显示「最新章节/开始阅读」。
 */
@Composable
fun BookDetailScreen(
    viewModel: BookDetailViewModel,
    onReadClick: () -> Unit,
    onShelfClick: () -> Unit
) {
    val state by viewModel.detailState.collectAsState()
    val bookShelf = state.bookShelf
    val searchBook = viewModel.searchBook
    val inBookShelf = state.inBookShelf

    val coverUrl = if (bookShelf != null) {
        bookShelf.bookInfo?.coverUrl ?: ""
    } else {
        searchBook?.coverUrl ?: ""
    }

    val name = if (bookShelf != null) {
        bookShelf.bookInfo?.name ?: ""
    } else {
        searchBook?.name ?: ""
    }

    val author = if (bookShelf != null) {
        bookShelf.bookInfo?.author ?: ""
    } else {
        searchBook?.author ?: ""
    }

    val origin = if (bookShelf != null) {
        bookShelf.bookInfo?.origin ?: ""
    } else {
        searchBook?.origin ?: ""
    }

    val intro = if (bookShelf != null) {
        bookShelf.bookInfo?.introduce ?: ""
    } else {
        searchBook?.desc ?: ""
    }

    // 章节信息行（对齐原 tvChapter）：已在书架→「观看至:当前章」；不在书架→「最新章节:末章」
    // （详情未就绪时先用搜索列表携带的 lastChapter 兜底）
    val chapters = bookShelf?.chapterList
    val chapterInfo: String? = when {
        bookShelf != null && inBookShelf ->
            chapters?.getOrNull(bookShelf.durChapter)?.durChapterName
                ?.let { stringResource(com.ebook.common.R.string.tv_read_durprogress, it) }
                ?: stringResource(R.string.no_chapter)
        bookShelf != null ->
            chapters?.lastOrNull()?.durChapterName
                ?.let { stringResource(com.ebook.common.R.string.tv_searchbook_lastest, it) }
                ?: stringResource(R.string.no_chapter)
        else -> searchBook?.lastChapter?.takeIf { it.isNotEmpty() }
            ?.let { stringResource(com.ebook.common.R.string.tv_searchbook_lastest, it) }
    }

    if (bookShelf == null && searchBook == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CommonUiTokens.pagePadding)
    ) {
        Spacer(modifier = Modifier.height(CommonUiTokens.pagePadding))

        // 头部：封面 + 信息列（书名/作者/来源标签）
        Row(modifier = Modifier.fillMaxWidth()) {
            BookCover(
                url = coverUrl,
                contentDescription = name,
                modifier = Modifier.size(width = 100.dp, height = 145.dp)
            )
            Spacer(modifier = Modifier.width(CommonUiTokens.pagePadding))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (author.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (origin.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    InfoChip(text = stringResource(R.string.source_label, origin))
                }
                if (chapterInfo != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = chapterInfo,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(CommonUiTokens.sectionSpacing))

        // 简介（分组标题 + 共享卡片容器）
        if (intro.isNotEmpty()) {
            SectionLabel(text = stringResource(R.string.book_intro))
            CommonCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = intro,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 详情拉取状态（对齐原 tvLoading）：加载中显示转圈；失败可点击重试重新拉取
        when {
            state.loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            state.loadError -> Text(
                text = stringResource(R.string.load_failed_retry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .clickable { viewModel.getBookShelfInfo() }
                    .padding(vertical = 4.dp)
            )
        }

        // 操作按钮：书架切换（次要）+ 开始阅读（主要）
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onShelfClick,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    stringResource(
                        if (inBookShelf) R.string.remove_from_bookshelf else R.string.add_to_shelf
                    )
                )
            }
            Spacer(modifier = Modifier.width(CommonUiTokens.pagePadding))
            Button(
                onClick = onReadClick,
                // 详情未就绪（加载中/失败）时章节数据缺失，禁止跳阅读器造成死链；
                // 文案对齐原实现：在书架→继续阅读，不在书架→开始阅读
                enabled = bookShelf != null,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    stringResource(
                        if (inBookShelf) R.string.continue_read else R.string.read
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(CommonUiTokens.pagePadding))
    }
}
