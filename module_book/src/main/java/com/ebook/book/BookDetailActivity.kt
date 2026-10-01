package com.ebook.book

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.ebook.book.manager.BitIntentDataManager
import com.ebook.book.mvvm.viewmodel.BookDetailViewModel
import com.ebook.book.mvvm.viewmodel.BookReadViewModel.Companion.OPEN_FROM_APP
import com.ebook.common.event.FROM_BOOKSHELF
import com.ebook.common.event.KeyCode
import com.ebook.common.event.RouteArgs
import com.ebook.common.ui.BookCover
import com.ebook.common.ui.CommonCard
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.EmptyState
import com.ebook.common.ui.InfoChip
import com.ebook.common.ui.SectionLabel
import com.ebook.common.ui.preview.AppPreview
import com.ebook.common.ui.preview.SAMPLE_COVER_URL
import com.ebook.common.ui.preview.SAMPLE_SOURCE_URL
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
            state = rememberBookDetailRenderState(viewModel),
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
            },
            // 失败态的「重试」：重新拉一次详情。动作留在壳层，根组件只发意图
            onRetry = { viewModel.getBookShelfInfo() },
            // 修键面板入口。基类 ToolbarLayout 没有 actions 插槽，为一颗按钮自绘整条顶栏
            // 要连带把返回箭头、insets、主题配色全接管一遍（极易漏），所以这个动作放页面里。
            onEditMetaClick = { noteUrl ->
                val bundle = Bundle().apply { putString(RouteArgs.NOTE_URL, noteUrl) }
                TheRouter.build(KeyCode.Book.EDIT_BOOK_META_PATH).with(bundle).navigation(this)
            },
        )
    }
}

/**
 * 详情屏的**渲染态**，即无状态根 [BookDetailScreen] 的唯一输入。
 *
 * 为什么要单独设这一层：这页的每个字段都有两个来源——已在书架时取书架行的 `book_info`，
 * 未加书架时取搜索/书城带过来的条目。这层"谁优先"的判断过去写在 UI 里，而它错一格不崩、
 * 不报错，只是把另一份数据显示出来。把它收成一份可命名的状态，预览与真机就能拿同一组值对照，
 * 根组件也才可能没有 ViewModel（`@Preview` 只能标无参 `@Composable`、设计期不经 Hilt）。
 */
data class BookDetailRenderState(
    /** 两路来源都还没到手：整页画转圈占位 */
    val pending: Boolean = true,
    val coverUrl: String = "",
    val name: String = "",
    val author: String = "",
    val origin: String = "",
    val intro: String = "",
    /** 章节信息行；null 表示这一格整块不渲染 */
    val chapterInfo: String? = null,
    val loading: Boolean = false,
    val loadError: Boolean = false,
    val tocDiverged: Boolean = false,
    val inBookShelf: Boolean = false,
    /** 章节数据是否就绪：未就绪时阅读按钮禁用（进阅读器必死链） */
    val canRead: Boolean = false,
    /** 修键入口的钥匙；非「已在书架」时为 null，入口整块不出现 */
    val noteUrl: String? = null,
)

/**
 * 由 ViewModel 装配 [BookDetailRenderState]。
 *
 * **它属于壳层**（与 [PageContent] 同层）：状态流的收集、`searchBook` 这个非 Flow 字段的读取，
 * 以及需要 `stringResource` 的文案派生都只发生在这里，根组件因此只吃一份不可变状态。
 */
@Composable
private fun rememberBookDetailRenderState(viewModel: BookDetailViewModel): BookDetailRenderState {
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

    return BookDetailRenderState(
        pending = bookShelf == null && searchBook == null,
        coverUrl = coverUrl,
        name = name,
        author = author,
        origin = origin,
        intro = intro,
        chapterInfo = chapterInfo,
        loading = state.loading,
        loadError = state.loadError,
        tocDiverged = state.tocDiverged,
        inBookShelf = inBookShelf,
        canRead = bookShelf != null,
        noteUrl = bookShelf?.noteUrl,
    )
}

/**
 * 书籍详情内容（ADR-0006 共享设计语言重设计）：
 * [BookCover] 封面 + 右侧信息列（书名/作者/来源 [InfoChip]/章节信息）的头部，
 * 简介用 [SectionLabel] + [CommonCard]，字号全部走 Material typography。
 *
 * **无状态根**：只吃 [BookDetailRenderState] 与回调，收集与派生留在
 * [rememberBookDetailRenderState]（壳层），所以本页可以直接预览。
 *
 * 加载中/失败（可点重试）对齐原 tvLoading 行为；章节行与阅读按钮文案对齐原实现：
 * 在书架显示「观看至/继续阅读」，不在书架显示「最新章节/开始阅读」。
 */
@Composable
fun BookDetailScreen(
    state: BookDetailRenderState,
    onReadClick: () -> Unit,
    onShelfClick: () -> Unit,
    onRetry: () -> Unit,
    onEditMetaClick: (noteUrl: String) -> Unit,
) {
    val coverUrl = state.coverUrl
    val name = state.name
    val author = state.author
    val origin = state.origin
    val intro = state.intro
    val chapterInfo = state.chapterInfo
    val inBookShelf = state.inBookShelf

    if (state.pending) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    // 修键入口只对「已在书架」的条目存在：键行（book_group）随书才有，
    // 搜索来源还没有 noteUrl 行可修。派生成可空函数，供下面的分支判存在性。
    val editMeta: (() -> Unit)? = state.noteUrl?.let { url -> { onEditMetaClick(url) } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CommonUiTokens.pagePadding)
    ) {
        Spacer(modifier = Modifier.height(CommonUiTokens.pagePadding))

        // 头部：封面 + 信息列（书名/作者/来源标签）
        Row(modifier = Modifier.fillMaxWidth()) {
            // 3:4（`100 * 4 / 3`），与全仓其余封面档位一致（书架/搜索 57×76、下载 48×64）。
            // BookCover 用 ContentScale.Crop，比例不对就从封面上下各啃掉一截——原 100×145
            // （≈0.69）会把印在底边的书名/作者裁掉，与书城横卡那种"刻意不等比"不同，
            // 这里没有要保住首屏高度的理由，也就没有理由不是 3:4
            BookCover(
                url = coverUrl,
                contentDescription = name,
                modifier = Modifier.size(width = 100.dp, height = 100.dp * 4 / 3)
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

        // 详情拉取状态（对齐原 tvLoading）：加载中是行内转圈；失败走共享 EmptyState
        // （线性图标 + 主文案 + 「重试」动作），仍留在章节信息这一行内、不撑满整页
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
            state.loadError -> EmptyState(
                icon = Icons.Outlined.CloudOff,
                title = stringResource(R.string.load_failed),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                hint = stringResource(R.string.load_failed_hint),
                actionText = stringResource(R.string.retry),
                onAction = onRetry,
            )
        }

        // 目录分叉提示：本地目录一行未动，页面数据完好、能读能下载，只是查不到新章。
        // 故与上方的 loadError 不是一回事 —— 不做成可点重试（分叉不是暂时性故障，
        // 重试无意义），也不置 loadError（那会让一本正常显示的书凭空变成加载失败态）。
        if (state.tocDiverged) {
            Text(
                text = stringResource(R.string.toc_diverged_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 12.dp)
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
                enabled = state.canRead,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    stringResource(
                        if (inBookShelf) R.string.continue_read else R.string.read
                    )
                )
            }
        }

        // 修键面板入口（spec §9.3）。放页面里而不是顶栏 actions：基类 Toolbar 没有 actions
        // 插槽，为一颗按钮自绘整条顶栏要连带接管返回箭头与 insets，漏一项就是静默缺陷。
        if (editMeta != null) {
            TextButton(
                onClick = editMeta,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.edit_book_meta_title))
            }
        }

        Spacer(modifier = Modifier.height(CommonUiTokens.pagePadding))
    }
}

/**
 * 预览：详情屏的七种渲染态并排出图。
 *
 * 这一页的分支多而每一条都不报错：按钮文案随「在不在书架」换、章节信息行有三种说法、
 * 分叉提示与失败态长得像但不是同一件事（分叉不做成可点重试）、`pending` 时整页只有转圈。
 * 派生逻辑搬到 [rememberBookDetailRenderState] 之后，这里能直接喂状态，于是这些形态
 * 第一次变成"打开文件就看得见"的东西。
 */
@Preview(showBackground = true)
@Composable
private fun BookDetailScreenPreview(
    @PreviewParameter(BookDetailRenderStateProvider::class) state: BookDetailRenderState,
) {
    AppPreview {
        BookDetailScreen(
            state = state,
            onReadClick = {},
            onShelfClick = {},
            onRetry = {},
            onEditMetaClick = {},
        )
    }
}

/**
 * 七档渲染态的样例出口。
 *
 * 这里直接写展示字符串而不是从 [PreviewSamples] 的实体推：根组件吃的已经是**派生完**的
 * [BookDetailRenderState]，实体到文案的那一步归 [rememberBookDetailRenderState]，
 * 它自己有 `ShelfItemRowsTest` 那类用例守着。预览要拍的是"每种状态画成什么样"。
 */
private class BookDetailRenderStateProvider : PreviewParameterProvider<BookDetailRenderState> {

    private val onShelf = BookDetailRenderState(
        pending = false,
        coverUrl = SAMPLE_COVER_URL,
        name = "山海拾遗",
        author = "临渊客",
        origin = "样例原生源",
        intro = "山与海之间遗落的旧事，一册写完便再寻不到第二份。",
        chapterInfo = "观看至: 第 128 章 北望",
        inBookShelf = true,
        canRead = true,
        noteUrl = "$SAMPLE_SOURCE_URL/book/1",
    )

    private val notOnShelf = BookDetailRenderState(
        pending = false,
        coverUrl = SAMPLE_COVER_URL,
        name = "长安小吏",
        author = "沈观",
        origin = "样例原生源",
        intro = "一个小吏在长安的三十年，什么都没做成，但都看见了。",
        chapterInfo = "最新章节: 第 96 章 归途",
        inBookShelf = false,
        canRead = false,
    )

    private val withoutOptionalFields = onShelf.copy(
        author = "",
        origin = "",
        intro = "",
        chapterInfo = null,
    )

    private val fetching = onShelf.copy(loading = true)

    private val failed = onShelf.copy(loading = false, loadError = true, canRead = false)

    private val diverged = onShelf.copy(tocDiverged = true)

    private val pending = BookDetailRenderState()

    override val values: Sequence<BookDetailRenderState>
        get() = sequenceOf(
            pending,
            onShelf,
            notOnShelf,
            withoutOptionalFields,
            fetching,
            failed,
            diverged,
        )

    override fun getDisplayName(index: Int): String = SCREENSHOT_NAMES[index]

    private companion object {
        /** 与 [values] 同序：面板上的名字直接用中文，省得对着图猜档位 */
        val SCREENSHOT_NAMES = listOf(
            "等待中", "已在书架", "未加书架", "无作者/来源/简介", "拉取中", "加载失败", "目录分叉",
        )
    }
}
