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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.SourceDefinition
import com.ebook.common.event.FROM_SEARCH
import com.ebook.common.event.KeyCode
import com.ebook.common.ui.BookCover
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.InfoChip
import com.ebook.db.entity.LibraryKindBookListEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.find.R
import com.ebook.find.entity.BookType
import com.ebook.find.mvvm.viewmodel.LibrarySourceState
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
 * - 书源（ADR-0016 P3-c）：顶部一颗切换胶囊 + 无可用书源时的引导态。
 *   页面**不主动触发首屏刷新**：书库与分类都由 `LibraryViewModel.currentSource` 驱动，
 *   旧写法那个 `LaunchedEffect(Unit) { refreshData() }` 与它并发会各拉一次同一个源
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookstorePage(viewModel: LibraryViewModel = hiltViewModel()) {
    val kindBooks by viewModel.list.collectAsState()
    val bookTypes by viewModel.bookTypeList.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val currentSource by viewModel.currentSource.collectAsState()
    val sourceState by viewModel.sourceState.collectAsState()
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

    Column(modifier = Modifier.fillMaxSize()) {
        // 原 toolbarView：书城标题 + colorSurface 背景
        TopAppBar(
            title = { Text(stringResource(R.string.bookstore_title)) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )
        // 书源切换器固定在工具栏下方、不随列表滚动：换源决定的是整页数据的基准，
        // 滚两屏就找不到它的话，用户想纠正也纠正不回来
        BookSourceSelector(
            currentSource = currentSource,
            sources = sources,
            onSelectSource = viewModel::switchSource,
            modifier = Modifier.padding(
                start = CommonUiTokens.pagePadding,
                end = CommonUiTokens.pagePadding,
                bottom = CommonUiTokens.listSpacing
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
            // Ready 之外都不渲染书库内容（那一刻没有「内容」可言），改由引导态接手。
            // sourceUrl 一并带下去：分类入口跳转时要把「这些分类胶囊属于哪个源」写进导航参数
            LibraryContent(listState, kindBooks, bookTypes, sourceState, currentSource?.sourceUrl.orEmpty())
        }
    }
}

/**
 * 书源切换胶囊（ADR-0016 P3-c）：当前书源名 + 下拉箭头，点击弹 [DropdownMenu] 列出启用中的源。
 *
 * 形态沿用本仓既有的胶囊语言（[InfoChip] 那套 `RoundedCornerShape(50)` + primaryContainer，
 * 见同文件 [BookTypeChip] 的配色说明），只是 [InfoChip] 的内容槽只收文本、放不下箭头，
 * 故这里用同一组语义色自己拼一行。配色全部走 `MaterialTheme.colorScheme`，无硬编码色值。
 *
 * **只有一条启用源时它不是一颗「点了没反应」的按钮**：那时没有任何可切的目标，源名照常显示，
 * 但不画箭头，且 [Modifier.clickable] 传 `enabled = false`——禁用态下它既收不到点击、
 * 也不给涟漪，视觉上与行为一致（[Role.Button] 仍然保留，无障碍才会在禁用态把它读成一颗按钮，
 * 而不是读成一段碰巧不能按的文字）。想要更多源是书源管理页的事，那里有入口。
 *
 * 切换即持久化：[onSelectSource] 交给 VM 调 `setDefaultSource`，书库与分类由订阅回流刷新，
 * 本页不自己判「切完了该重拉什么」。
 *
 * @param currentSource 当前默认源；null 表示无可用书源，此时整个切换器不渲染（页面另有引导态）
 * @param sources 可切换的源（VM 已过滤为「启用中」，禁用项不进这份清单，见 LibraryViewModel.sources）
 * @param onSelectSource 选中某条源时回调其 URL
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookSourceSelector(
    currentSource: SourceDefinition?,
    sources: List<BookSourceRule>,
    onSelectSource: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 无源时这里什么都不画：一颗没有名字的胶囊会和下方的引导语抢话，
    // 那一刻页面只该有「请先启用或导入书源」这一句
    val current = currentSource ?: return
    var expanded by remember { mutableStateOf(false) }
    val canSwitch = sources.size > 1
    Box(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.clickable(
                enabled = canSwitch,
                role = Role.Button,
                onClick = { expanded = true }
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = current.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (canSwitch) {
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = stringResource(R.string.switch_book_source),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        // 没有可切目标时连菜单都不挂：弹一份只有一条「已选中」的清单等于没弹
        if (canSwitch) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = RoundedCornerShape(CommonUiTokens.cardCornerSmall),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 3.dp
            ) {
                sources.forEach { option ->
                    val selected = option.url == current.sourceUrl
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        // 选中项要有可见标记：只靠胶囊上那行名字，用户得自己反推菜单里哪条是当前源
                        trailingIcon = {
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = stringResource(R.string.current_book_source),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        onClick = {
                            expanded = false
                            // 再点当前源不值得走一趟持久化（Manager 会 applyDefault 同一个规则，
                            // 订阅侧因值未变而不刷新，白跑一次 Room 写）
                            if (!selected) onSelectSource(option.url)
                        }
                    )
                }
            }
        }
    }
}

/**
 * 书城内容（ADR-0006 共享设计语言重设计）：
 * 「书籍类型」标题 + 胶囊流式标签 + 搜索胶囊 + 分类书籍区块。
 *
 * 书籍类型区去掉 CommonCard 包裹，chips 直接浮在页面上保持通透；
 * 标题提升到 titleSmall / SemiBold，与下方各分类区块标题视觉对齐（本页三处区块标题统一走 [SectionTitle]）。
 *
 * @param sourceState 页面档位（[LibrarySourceState.Ready] 才渲染书库内容）：
 *   [LibrarySourceState.Unknown]（首帧，Room 还没答复默认源）**整片留空**；
 *   [LibrarySourceState.NoSource]（一条源都没启用）与 [LibrarySourceState.BrokenSource]
 *   （有源但当前源解析不出来）各说一句不同的话（两句话由枚举在 [SourceGuidance] 里穷尽分档，
 *   判据只住 VM，页面不自己推断）
 * @param sourceUrl 当前源 URL：本页的分类胶囊/「更多」跳分类页时随导航参数带上，
 *   让那一页明确知道这些分类 url 属于哪个源（空串 = 无源，照常带，由分类页统一处置）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryContent(
    listState: LazyListState,
    kindBooks: List<LibraryKindBookListEntity>,
    bookTypes: List<BookType>,
    sourceState: LibrarySourceState,
    sourceUrl: String,
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
        // 穷尽 when：将来给 LibrarySourceState 加一档时这里编译不过，不会出现
        // 「新档位静默按旧分档渲染」的分裂
        when (sourceState) {
            LibrarySourceState.Ready -> Unit
            // 首帧「还不知道」：整片留空（不画引导语、也不画切换器，见 LibrarySourceState.Unknown）
            LibrarySourceState.Unknown -> return@LazyColumn
            LibrarySourceState.NoSource, LibrarySourceState.BrokenSource -> {
                item { SourceGuidance(sourceState) }
                return@LazyColumn
            }
        }
        // "书籍类型"标题：走本页共享的 [SectionTitle]（titleSmall / SemiBold），与下方分类区块标题同档
        item {
            SectionTitle(
                text = stringResource(R.string.book_type),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        // 书类型胶囊：直接浮在页面上（去掉 CommonCard 包裹，减少视觉层次）
        item {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                bookTypes.forEach { bookType ->
                    BookTypeChip(bookType) {
                        // 跳分类选书页（url/title/source_url 经 TheRouter withString 落到 intent extras，
                        // ChoiceBookActivity 直接读 extras：均经 SavedStateHandle，不用 @Autowired）。
                        // source_url 是「这些分类 url 属于哪个源」——分类页整段会话要锁住它，
                        // 否则它只能自己去猜一个源，多书源下会拿别人的规则解析这个分类 url
                        TheRouter.build(KeyCode.Find.CHOICE_PATH)
                            .withString("url", bookType.url)
                            .withString("title", bookType.bookType)
                            .withString("source_url", sourceUrl)
                            .navigation(context)
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
        //
        // **key 必须含位置**：`kindName` 不是唯一标识——规格 §8.1 明许「缺 `名称::` 的条目标题为空」
        // （`ExploreUrlFormat` 的 KDoc 同口径），两个以上空标题即撞 key；而书源是用户导入的，
        // 这条路径不能假设它不会发生。真实事故（2026-09-10，`番茄（发现）`）里一条 `<js>` 形态的
        // `exploreUrl` 切出 105 条空标题条目，`LazyColumn` 直接抛
        // `IllegalArgumentException: Key "" was already used` 崩在主线程上。
        // 列表在换源/刷新时整份替换，位置标识的代价（重组合丢失跨帧同一性）在这里不成立；
        // 与阅读器目录同一取法（`ReaderPanels` 的 `itemsIndexed(chapters, key = { index, _ -> index })`）。
        itemsIndexed(kindBooks, key = { index, _ -> index }) { _, kind ->
            KindBookSection(kind, sourceUrl)
        }
    }
}

/**
 * 书城页的区块标题排版：`titleSmall` + `SemiBold`（书籍类型、书源引导标题、分类区块名共用一个定义）。
 *
 * **为什么不用 lib_book_common 的 `SectionLabel`**：那是「卡片上方弱化分组标签」的语言
 * （labelMedium + onSurfaceVariant + 12dp 起始缩进），而本页这三处是**区块主标题**——重设计时
 * 特意提升到 titleSmall / SemiBold 与各分类区块标题对齐（见 [LibraryContent] 的 KDoc），
 * 且要能带 `weight(1f)` 与单行省略（分类名右侧还有「更多」）。直接换用会一次性改掉字号、颜色与内边距，
 * 等于把已定稿的观感回退。共享组件按 ADR-0006 只能长在 `lib_book_common` 里，本模块改不动那边，
 * 故先把这三处收成一个私有组件：排版从此只有这一份定义，三处不会各自漂移。
 * **等共享库补出「区块主标题」这一档（或给 [com.ebook.common.ui.SectionLabel] 加上排版参数），
 * 应把它整体上提，本文件不保留副本。**
 *
 * @param text 标题文本
 * @param modifier 外层修饰：对齐/间距/占位由调用方决定（页面级标题要上边距、分类名要 `weight(1f)`、
 *   引导态标题居中）
 * @param maxLines 最大行数，默认不限；分类名限单行
 * @param overflow 溢出策略，默认裁剪；分类名省略号收尾
 */
@Composable
private fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier
    )
}

/**
 * 书类型胶囊：共享 [InfoChip] 的胶囊形态（50 圆角 + primaryContainer 语义色 + labelLarge）。
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
 * 书源引导空态（ADR-0016 P3-c / 决策 5）。
 *
 * 两档、两句话，动作各不相同——这就是它收成一个枚举而不是一个布尔的原因：
 * - [LibrarySourceState.NoSource]：一条启用源都没有 → 「去书源管理**启用或导入**书源」，
 *   正文之后另给一颗「去导入书源」按钮（见下）；
 * - [LibrarySourceState.BrokenSource]：有源，但当前这个源解析不出来了 → 「在顶部**换一个源**，
 *   或到书源管理**重导**这条源」。
 * 把后者误报成前者会把用户支去点那条没用的路：他要的不是「启用」，他启用的正是那条坏源。
 * [LibrarySourceState.Unknown] 与 [LibrarySourceState.Ready] 不走这里，各档处置见下。
 *
 * 触发条件只有 [LibraryViewModel.sourceState] 这一处（判据不是「列表空不空」，也不是「加载失败」——
 * 见其 KDoc）。NoSource 那一刻页面必须同时成立三件事——不发任何请求（没有源可解析，
 * 发了也是打不相干的站）、不白屏、不无限 loading，所以 [LibraryContent] 整片换成这一坨，
 * 而刷新态由 VM 当场收掉。BrokenSource 同样整片换掉：那一刻列表里就算有东西也是上一轮的残留，
 * 配一句「这个源已失效」比继续逛那份可能过期的书目安全。
 *
 * **NoSource 档给「去导入书源」按钮，跨模块路由由独立模式占位承接**：这条路由属于 `module_me`，
 * 模块独立运行时（`isModule=true`）本模块没有这个路由；占位页 [KeyCode.Find.TEST_BOOK_SOURCE_PATH]
 * 与 `TestApplication` 的路径替换（`Me.BOOK_SOURCE_PATH` → 它）正是为此存在，所以按钮在两种
 * 运行形态下都点得动——集成态到真实书源管理页，独立态到桩页。
 *
 * **BrokenSource 档刻意不给按钮**：那一刻顶部切换器就在页面上（这个源是存在的、只是解不动），
 * 用户的动作是当场换源或在书源管理里重导，按钮把他支走反而离开了他真正要操作的地方。
 *
 * 文案走**穷尽 when**：将来给 [LibrarySourceState] 加一档时这里编译不过，
 * 不会出现「新档位静默沿用无源那句」的分裂。
 */
@Composable
private fun SourceGuidance(state: LibrarySourceState) {
    val context = LocalContext.current
    val (titleRes, bodyRes) = when (state) {
        LibrarySourceState.NoSource ->
            R.string.no_book_source_title to R.string.no_book_source_guidance
        LibrarySourceState.BrokenSource ->
            R.string.broken_book_source_title to R.string.broken_book_source_guidance
        // 页面只在这两档画这一坨；Ready 走到这里说明调用点的判据写错了
        LibrarySourceState.Ready -> error("Ready 档位不该渲染引导态")
        // Unknown 是首帧占位：页面那一刻整片留空，根本不会走到这里
        LibrarySourceState.Unknown -> error("Unknown 档位不该渲染引导态")
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 标题与分类区块名共用 [SectionTitle]，只是这里由外层 Column 居中
        SectionTitle(text = stringResource(titleRes))
        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        // 无源档才给入口：这是整页唯一一处「自己走不通、必须去别处操作」的处境，
        // 按钮把「去哪导入」这一步直接递到手上（文案已写全动作，按钮只是让它一键可达）
        if (state == LibrarySourceState.NoSource) {
            TextButton(onClick = {
                TheRouter.build(KeyCode.Me.BOOK_SOURCE_PATH).navigation(context)
            }) {
                Text(text = stringResource(R.string.go_import_book_source))
            }
        }
    }
}

/**
 * 分类书籍区块：分类名（标题样式）+ 更多（主色文本按钮）+ 横向书籍列表。
 *
 * @param sourceUrl 当前书源 URL：「更多」跳分类页时随导航参数带上，让那一页明确知道
 *   这个 `kindUrl` 属于哪个源（与分类胶囊那条跳转同一约定）
 */
@Composable
private fun KindBookSection(kind: LibraryKindBookListEntity, sourceUrl: String) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionTitle(
                text = kind.kindName,
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
                                .withString("source_url", sourceUrl)
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
