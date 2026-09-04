package com.ebook.find

import androidx.activity.viewModels
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ebook.common.R as CommonR
import com.ebook.common.event.FROM_SEARCH
import com.ebook.common.event.KeyCode
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.InfoChip
import com.ebook.db.entity.SearchHistoryEntity
import com.ebook.find.mvvm.viewmodel.SearchViewModel
import com.ebook.find.view.SearchBookItem
import com.therouter.TheRouter
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseMvvmRefreshActivity
import com.xrn1997.common.ui.ExplodeOverlay
import com.xrn1997.common.ui.ExplodeState
import com.xrn1997.common.ui.LoadMoreFooter
import com.xrn1997.common.ui.rememberExplodeState
import com.xrn1997.common.ui.theme.MyApplicationTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/** 历史面板揭示动画时长（毫秒），对齐旧 ViewAnimationUtils.createCircularReveal 的开合时长。 */
private const val HISTORY_OPEN_DURATION_MS = 700
private const val HISTORY_CLOSE_DURATION_MS = 300

/**
 * 圆形揭示缓动：AccelerateDecelerateInterpolator 的三次贝塞尔近似
 * （0.45/0.05/0.55/0.95 ≈ 系统 accelerate_decelerate 曲线）。
 */
private val REVEAL_EASING = CubicBezierEasing(0.45f, 0.05f, 0.55f, 0.95f)

/** 触发搜索前等待键盘收起的延迟（毫秒）：旧实现用 Handler.postDelayed 让键盘收起动画先走完，语义保留。 */
private const val SEARCH_AFTER_KEYBOARD_HIDE_DELAY_MS = 300L

/** 进入页面后等待软键盘弹出的宽限期（毫秒）：超期仍未弹出则走"无键盘兜底"直接开历史面板。 */
private const val KEYBOARD_SHOW_GRACE_MS = 600L

/**
 * 搜索页（纯 Compose，ADR-0001 Compose 迁移终态的 module_find 收尾）。
 *
 * 架构说明：
 * - 外壳：lib_common Compose 基类统一提供主题/加载与空态覆盖层/触底加载更多；
 *   本页覆写 [HomePage] 注入自定义骨架（顶部搜索栏 + 结果列表 + 覆盖其上的历史面板），
 *   结果列表仍走基类 RefreshableList（无下拉刷新、有触底加载更多，与旧页行为一致）
 * - 历史面板开合由 [isImeVisible] 驱动（替代旧 GlobalLayoutListener 高度差 hack）：
 *   键盘弹出→面板揭示；键盘收起→若从未搜索过则退出页面，否则收起面板。
 *   键盘弹不出的环境（物理键盘/部分模拟器）由宽限期兜底直接开面板，对齐旧行为兼容逻辑
 * - 圆形揭示动画：Animatable 进度 + 自顶角的圆形 [GenericShape] 裁剪
 *   （替代 ViewAnimationUtils.createCircularReveal，开 700ms/合 300ms）；
 *   进度在形状轮廓生成（布局期）时读取，动画期间不触发面板重组
 * - 清除历史粒子爆炸：[ExplodeOverlay]（Compose Canvas 重制 ExplosionField）
 * - 书架事件同步与搜索历史仍收敛在 [SearchViewModel] 内（VM 零改动）
 */
@AndroidEntryPoint
@Route(path = KeyCode.Find.SEARCH_PATH)
class SearchActivity : BaseMvvmRefreshActivity<SearchViewModel>() {
    override val viewModel: SearchViewModel by viewModels()

    /** 输入框文本（纯 View 状态，不进 VM——对齐 SearchViewModel 的状态划分约定） */
    private var query by mutableStateOf("")

    /** 是否已执行过搜索：决定软键盘收起时退出页面还是仅收起历史面板（对齐旧 hasSearch 语义） */
    private var hasSearched by mutableStateOf(false)

    /** 键盘是否弹出过一次：守卫"键盘收起→退出"边沿，避免首帧 isImeVisible=false 误触发 */
    private var keyboardShownOnce by mutableStateOf(false)

    /** 无键盘环境的兜底开关：宽限期后键盘仍未弹出时直接显示历史面板 */
    private var panelForcedOpen by mutableStateOf(false)

    /** 空输入抖动触发器：自增即播放一次抖动动画 */
    private var shakeTrigger by mutableIntStateOf(0)

    /** 组合内捕获的软键盘控制器（供非组合函数 [toSearch] 隐藏键盘），页面仅一次 setContent，捕获安全 */
    private var keyboardController: SoftwareKeyboardController? = null

    override fun enableToolbar(): Boolean = false

    override fun enableLoadMore(): Boolean = true

    override fun enableRefresh(): Boolean = false

    /**
     * 自定义页面骨架：顶部搜索栏 + 基类列表区（含三层覆盖层）+ 覆盖在列表区之上的历史面板。
     *
     * 面板与列表的层级对齐旧布局：历史面板盖住结果区（含加载浮层）、不盖搜索栏；
     * 顶部阴影条在最上层，对齐旧布局最后一个 View。
     *
     * 阴影条注意：bg_shadow 是固定尺寸位图（xxhdpi 下固有约 65×20dp），
     * 必须用 [ContentScale.FillBounds] 拉伸到全屏宽，对齐旧布局 View 背景（match_parent 自动拉伸）；
     * 若用默认 Fit 会保持宽高比、只以固有宽度居中显示，出现"阴影宽度不对"的视觉问题。
     */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun HomePage(modifier: Modifier) {
        val imeVisible = WindowInsets.isImeVisible
        keyboardController = LocalSoftwareKeyboardController.current
        val focusRequester = remember { FocusRequester() }

        // 键盘状态边沿：弹出→记录并取消兜底；收起→未搜索过则退出页面（对齐旧 resetLayoutParams）
        LaunchedEffect(imeVisible) {
            if (imeVisible) {
                keyboardShownOnce = true
                panelForcedOpen = false
            } else if (keyboardShownOnce && !hasSearched) {
                finishAfterTransition()
            }
        }

        // 进入即加载全量历史（对齐旧 initView 末尾的 querySearchHistory("")，
        // 取该类型全部历史，语义见 ADR-0005）；随后聚焦弹键盘，宽限期后仍无键盘则兜底开面板
        LaunchedEffect(Unit) {
            viewModel.querySearchHistory()
            delay(100.milliseconds)
            focusRequester.requestFocus()
            keyboardController?.show()
            delay(KEYBOARD_SHOW_GRACE_MS.milliseconds)
            if (!keyboardShownOnce) panelForcedOpen = true
        }

        val historyVisible = imeVisible || panelForcedOpen

        Column(modifier = modifier.fillMaxSize()) {
            SearchBarRow(
                query = query,
                onQueryChange = ::onQueryChange,
                trailingLabel = if (historyVisible) stringResource(R.string.search) else stringResource(R.string.str_return),
                // 配色对齐规则：主操作（搜索）用 primary，中性操作（返回）用 onSurface，
                // 与书城页「更多」等全模块主操作色统一（见 HistoryPanel KDoc 中的配色规则）
                trailingColor = if (historyVisible) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                onTrailingClick = {
                    if (historyVisible) toSearch() else finishAfterTransition()
                },
                onImeSearch = ::toSearch,
                shakeTrigger = shakeTrigger,
                focusRequester = focusRequester,
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                super.HomePage(Modifier.fillMaxSize())
                HistoryPanelWithReveal(
                    modifier = Modifier.matchParentSize(),
                    historyVisible = historyVisible,
                )
                Image(
                    painter = painterResource(CommonR.drawable.bg_shadow),
                    contentDescription = null,
                    // FillBounds：旧布局中该位图作为 View 背景被 match_parent 拉伸，
                    // 保持宽高比的 Fit 会退化为固有宽度（约 65dp）居中（见 HomePage KDoc）
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                        .align(Alignment.TopCenter),
                )
            }
        }
    }

    /** 搜索结果列表（与迁移前一致：复用基类 LazyListState 保证触底检测正确）。 */
    @Composable
    override fun PageContent(state: LazyListState) {
        val books by viewModel.list.collectAsState()
        // 加载更多底部状态：由基类渲染镜像推导，失败可点重试
        val loadingMore by remember { isLoadingMoreState }
        val loadMoreFailed by remember { loadMoreFailedState }
        val hasMore by remember { hasMoreDataState }
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = CommonUiTokens.pagePadding,
                top = CommonUiTokens.sectionSpacing,
                end = CommonUiTokens.pagePadding,
                bottom = CommonUiTokens.pagePadding
            ),
            // 条目改为独立圆角卡片（见 SearchBookItem），用间距分隔替代条目内分割线
            verticalArrangement = Arrangement.spacedBy(CommonUiTokens.listSpacing)
        ) {
            items(books, key = { it.noteUrl }) { searchBook ->
                SearchBookItem(
                    searchBook = searchBook,
                    onItemClick = {
                        TheRouter.build(KeyCode.Book.DETAIL_PATH)
                            .withInt("from", FROM_SEARCH)
                            .withObject("data", searchBook)
                            .navigation(this@SearchActivity)
                    },
                    onAddShelf = { viewModel.addBookToShelf(searchBook) }
                )
            }
            // 触底加载反馈：加载中 / 失败重试 / 没有更多
            item {
                LoadMoreFooter(
                    isLoadingMore = loadingMore,
                    loadMoreFailed = loadMoreFailed,
                    hasMoreData = hasMore,
                    onRetry = { retryLoadMore() },
                )
            }
        }
    }

    /**
     * 历史面板 + 圆形揭示动画。
     *
     * 用 panelPresent 维持退出动画期间的组合存活（historyVisible=false 后仍需播完收缩动画），
     * revealFraction 驱动以面板左上角为圆心的圆形裁剪半径。
     *
     * @param modifier 必须由 BoxScope 调用方传入 matchParentSize（面板覆盖整个列表区）
     */
    @Composable
    private fun HistoryPanelWithReveal(modifier: Modifier, historyVisible: Boolean) {
        val histories by viewModel.successEvent.collectAsState(initial = emptyList())
        val explodeState = rememberExplodeState()
        // ⚠️ 仅在 Shape lambda（布局期）内读取 .value；组合期读取会导致逐帧重组
        val revealFraction = remember { Animatable(0f) }
        // 裁剪形状只创建一次：进度在 Shape.createOutline（布局期）读取而非组合期读取，
        // 动画每帧只触发裁剪轮廓重算（重布局），不重组整个历史面板子树；
        // 若在组合期读 revealFraction.value，700ms 揭示动画期间面板（含全部词条胶囊）
        // 会被逐帧重组且每帧新建 Shape 实例
        val circularRevealClipShape = remember {
            GenericShape { size, _ ->
                val radius = revealFraction.value *
                    hypot(size.width.toDouble(), size.height.toDouble()).toFloat()
                addOval(Rect(center = Offset(0f, 0f), radius = radius))
            }
        }
        var panelPresent by remember { mutableStateOf(false) }

        LaunchedEffect(historyVisible) {
            if (historyVisible) {
                panelPresent = true
                revealFraction.snapTo(0f)
                revealFraction.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(HISTORY_OPEN_DURATION_MS, easing = REVEAL_EASING),
                )
            } else if (panelPresent) {
                revealFraction.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(HISTORY_CLOSE_DURATION_MS, easing = REVEAL_EASING),
                )
                panelPresent = false
            }
        }

        if (panelPresent) {
            HistoryPanel(
                modifier = modifier.clip(circularRevealClipShape),
                histories = histories,
                explodeState = explodeState,
                onTagClick = { history ->
                    query = history.content
                    toSearch()
                },
                onClean = { viewModel.cleanSearchHistory() },
            )
        }
    }

    /** 输入变化：仅更新本地文本（纯 View 状态）。历史面板不再实时过滤，进入/插入/清除时才查询全量。 */
    private fun onQueryChange(newValue: String) {
        query = newValue
    }

    /**
     * 发起搜索（空输入改为触发抖动）。
     *
     * 顺序与旧实现一致：先置 [hasSearched]（避免关键盘触发的"收起→退出"边沿），
     * 插入历史、关键盘后延迟 300ms 再请求，让键盘收起动画先行。
     */
    private fun toSearch() {
        val key = query.trim()
        if (key.isEmpty()) {
            shakeTrigger++
            return
        }
        hasSearched = true
        panelForcedOpen = false
        viewModel.insertSearchHistory(key)
        keyboardController?.hide()
        lifecycleScope.launch {
            delay(SEARCH_AFTER_KEYBOARD_HIDE_DELAY_MS.milliseconds)
            viewModel.initPage()
            viewModel.toSearchBooks(key)
        }
    }
}

/**
 * 顶部搜索栏：圆角输入框（左侧搜索图标）+ 右侧"返回/搜索"文本按钮。
 *
 * 对齐旧 activity_search.xml 顶部 LinearLayout：13dp 上下边距、48dp 输入框高、
 * surfaceVariant 背景（原 bg_search_content）；圆角统一为全胶囊 50（对齐书城页搜索胶囊，
 * 全模块搜索入口同一形态）；空输入触发搜索时整体水平抖动（替代 YoYo Shake）。
 * 左侧放大镜用矢量 [Search] + onSurfaceVariant 着色（替代固定色位图 icon_search_nor：
 * 位图颜色无法适配深色模式，且与占位文字色割裂；paired 的 icon_search_sel 从未被引用，随迁移清理）。
 * 尺寸取 20dp 而非沿用旧布局的 15dp：矢量图标在 24×24 视口内自带留白，可见图形比尺寸值小一圈，
 * 而旧位图是满幅绘制，照搬 15dp 会显著偏小；20dp ≈ M3 搜索栏 56dp 高配 24dp 图标按 48dp 胶囊等比折算。
 *
 * @param trailingLabel 右侧按钮文案：历史面板可见时为"搜索"，否则为"返回"（对齐旧 checkTvToSearch）
 * @param trailingColor 右侧按钮文字色：主操作（搜索）用 primary，中性操作（返回）用 onSurface
 * @param shakeTrigger 抖动触发器，自增播放一次
 */
@Composable
private fun SearchBarRow(
    query: String,
    onQueryChange: (String) -> Unit,
    trailingLabel: String,
    trailingColor: Color,
    onTrailingClick: () -> Unit,
    onImeSearch: () -> Unit,
    shakeTrigger: Int,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val shakeOffset = remember { Animatable(0f) }
    // 空输入抖动：衰减振荡（≈旧 YoYo Techniques.Shake 观感）
    LaunchedEffect(shakeTrigger) {
        if (shakeTrigger == 0) return@LaunchedEffect
        shakeOffset.snapTo(0f)
        shakeOffset.animateTo(-14f, tween(55))
        shakeOffset.animateTo(12f, tween(55))
        shakeOffset.animateTo(-8f, tween(55))
        shakeOffset.animateTo(4f, tween(55))
        shakeOffset.snapTo(0f)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .padding(start = 10.dp)
                .offset { IntOffset(shakeOffset.value.roundToInt(), 0) }
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = stringResource(R.string.please_input_author_or_work),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                // 20dp 而非旧布局 15dp：矢量自带视口留白，同尺寸下可见图形比满幅位图小（见 SearchBarRow KDoc）；
                // padding 先于 size，保证 12dp 左间距不被计入图标尺寸（顺序反了会把占位撑到 32dp 宽）
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp)
                    .size(20.dp),
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .matchParentSize()
                    .focusRequester(focusRequester)
                    // start=40：图标占 12~32dp，留 8dp 间距再开始文字；end=16 与图标侧视觉配平（旧 28/28 是为 15dp 满幅小图标调的）
                    .padding(start = 40.dp, end = 16.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Search,
                    keyboardType = KeyboardType.Text,
                ),
                keyboardActions = KeyboardActions(onSearch = { onImeSearch() }),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.search_book_or_author),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
        Text(
            text = trailingLabel,
            style = MaterialTheme.typography.titleMedium,
            color = trailingColor,
            modifier = Modifier
                .height(48.dp)
                .clickable(onClick = onTrailingClick)
                .padding(start = 10.dp, end = 15.dp)
                .wrapContentHeight(Alignment.CenterVertically),
        )
    }
}

/**
 * 搜索历史面板：标题行（「搜索历史」+「清除」）+ 流式胶囊标签（共享 [InfoChip]，
 * 替代 TagFlowLayout）+ 粒子爆炸层。
 *
 * **module_find 统一配色规则（对齐 ADR-0006 共享设计语言）**：
 * - 页面底色：[androidx.compose.material3.ColorScheme.surface]（与书城/选书页一致，
 *   不再用 surfaceVariant 整面板铺灰）
 * - 可交互胶囊（历史词条）：primaryContainer + onPrimaryContainer（与书城页书籍类型胶囊一致；
 *   原实底 primary 弱化为主色容器，避免面板内大面积强主色）
 * - 中性信息标签（状态/分类/字数）：InfoChip 默认 surfaceVariant + onSurfaceVariant
 * - 主操作（清除/搜索/更多/加入书架按钮）：primary
 *
 * 标签从旧 shape_search_history_roundrect（3dp 圆角 + primary 底 + onPrimary 文字）
 * 重设计为 InfoChip 胶囊形态（50 圆角 + labelLarge）。清除时先取各标签中心坐标触发
 * [ExplodeOverlay] 粒子动画（粒子色跟随标签底色），再回调 [onClean] 清数据，
 * 对齐旧版"标签原地炸开"观感。
 *
 * @param explodeState 粒子爆炸状态机（由调用方 remember，面板销毁时随之释放）
 */
@Composable
private fun HistoryPanel(
    modifier: Modifier = Modifier,
    histories: List<SearchHistoryEntity>,
    explodeState: ExplodeState,
    onTagClick: (SearchHistoryEntity) -> Unit,
    onClean: () -> Unit,
) {
    // 面板在根坐标系的位置：标签中心换算到面板本地坐标系（与 ExplodeOverlay 对齐）
    var panelRootPosition by remember { mutableStateOf(Offset.Zero) }
    // 标签中心快照（面板本地坐标），常驻 map、由各标签 onGloballyPositioned 持续覆写最新值，
    // 仅在「清除」点击时读取 → 读到的必是当前帧最新坐标，规避 remember(histories) 快照的
    // 首帧 panelRootPosition = Zero 偏差；首帧 map 为空时 explode() 内部空列表守卫直接跳过爆炸，
    // 不影响清数据流程
    val tagCenters = remember { mutableMapOf<Int, Offset>() }
    // 胶囊底色/粒子色：primaryContainer（与书城页书籍类型胶囊统一，见本组件 KDoc 配色规则）
    val chipContainerColor = MaterialTheme.colorScheme.primaryContainer

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .onGloballyPositioned { panelRootPosition = it.positionInRoot() }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.str_search_history),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 18.dp, top = 5.dp, bottom = 5.dp),
                )
                Spacer(modifier = Modifier.weight(1f))
                if (histories.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.str_clean),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable {
                                explodeState.explode(tagCenters.values.toList(), chipContainerColor)
                                onClean()
                            }
                            .padding(start = 15.dp, top = 5.dp, end = 15.dp, bottom = 5.dp),
                    )
                }
            }
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 3.dp, top = 10.dp, end = 3.dp),
            ) {
                histories.forEachIndexed { index, history ->
                    // 胶囊形态 InfoChip（ADR-0006）；onGloballyPositioned 挂在 modifier 上，
                    // 量测到的边界含内边距，供清除时粒子爆炸定位（见组件 KDoc）
                    InfoChip(
                        text = history.content,
                        modifier = Modifier
                            .padding(start = 7.dp, end = 3.dp, bottom = 4.dp)
                            .onGloballyPositioned { coords ->
                                tagCenters[index] = coords.boundsInRoot().center - panelRootPosition
                            },
                        shape = RoundedCornerShape(50),
                        containerColor = chipContainerColor,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        textStyle = MaterialTheme.typography.labelLarge,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        // 历史词条可能较长，允许折行而非省略
                        maxLines = Int.MAX_VALUE,
                        onClick = { onTagClick(history) }
                    )
                }
            }
        }
        // 粒子爆炸层：叠在标签之上，与面板共享坐标系
        ExplodeOverlay(state = explodeState)
    }
}

/** 预览：搜索栏（无焦点、"返回"态）。 */
@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SearchBarRowPreview() {
    MyApplicationTheme {
        SearchBarRow(
            query = "",
            onQueryChange = {},
            trailingLabel = "返回",
            trailingColor = MaterialTheme.colorScheme.onSurface,
            onTrailingClick = {},
            onImeSearch = {},
            shakeTrigger = 0,
            focusRequester = remember { FocusRequester() },
        )
    }
}

/** 预览：历史面板（含三条示例历史）。 */
@Preview(showBackground = true, widthDp = 360, heightDp = 320)
@Composable
private fun HistoryPanelPreview() {
    MyApplicationTheme {
        HistoryPanel(
            modifier = Modifier.fillMaxSize(),
            histories = List(3) { i -> SearchHistoryEntity(2, "示例历史 ${i + 1}", 0L) },
            explodeState = rememberExplodeState(),
            onTagClick = {},
            onClean = {},
        )
    }
}
