package com.ebook.book.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ebook.book.R
import com.ebook.book.mvvm.viewmodel.CandidateProgress
import com.ebook.book.mvvm.viewmodel.SourceSwitchOutcome
import com.ebook.book.mvvm.viewmodel.SourceSwitchViewModel
import com.ebook.common.ui.CommonItemCard
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.InfoChip
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.SearchBookEntity

/**
 * 阅读中换源的候选弹层（ADR-0016 决策 8，P3-d）。
 *
 * 形态对齐阅读器既有面板的弹层惯例（`ModalBottomSheet` + 半屏限高列表 + `navigationBarsPadding`）：
 * 五个面板已是同一套 chrome，另立一套会让「从菜单里滑出来的东西」有两种脾气。
 * **不额外套 MaterialTheme**——阅读页 [ReadBookActivity.PageContent] 已把整片钉在浅色作用域，
 * 本弹层作为它的后代自然解析到同一份 colorScheme（AGENTS.md：全局主题由基类/页面装配点提供）。
 *
 * 三态必须都有可见反馈，不许白屏：搜索中且无候选 → 加载行；搜索结束且无候选 → 空态文案；
 * 有候选 → 列表。书源进度只在「本轮还有源没结束」时占一行，结束即收起（分母含失败的源，
 * 所以不会永远差一格，判据见 `SourceSwitchViewModel.finishRound`）。
 *
 * @param viewModel 候选与换源的 ViewModel（`hiltViewModel()` 在宿主处取得后传入，本组件不自己拿：
 *   与下载中心的 [com.ebook.book.reader.BookChapterSelectPage] 一样保持「纯展示 + 回调」的形态，方便宿主决定用哪个作用域的 VM）
 * @param oldShelf 阅读器当前正在读的条目：书名/作者用作搜索词，`tag` 用于排除所属源
 * @param onDismiss 关闭弹层
 * @param onSwitched 换源成功：把 [SourceSwitchOutcome] 交给宿主，由它把阅读器整体切到新条目。
 *   失败不会走到这里，也不会关弹层——失败时用户手上的处置就是「另选一本」
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceSwitchSheet(
    viewModel: SourceSwitchViewModel,
    oldShelf: BookShelfEntity,
    onDismiss: () -> Unit,
    onSwitched: (SourceSwitchOutcome) -> Unit,
) {
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val searchFailure by viewModel.searchFailure.collectAsStateWithLifecycle()

    val bookName = oldShelf.bookInfo?.name.orEmpty()
    val bookAuthor = oldShelf.bookInfo?.author.orEmpty()

    // 打开即搜一轮（只搜第 1 页）。以 noteUrl 作 key：面板每次从关闭到打开都是一次新组合，
    // LaunchedEffect 必然重跑；`searchCandidates` 自己在开头清零候选与进度，
    // 所以「换完一本再打开面板」看到的不会是上一轮的残留。
    LaunchedEffect(oldShelf.noteUrl) {
        viewModel.searchCandidates(bookName, bookAuthor, oldShelf.tag)
    }

    // 提交中的候选 noteUrl：既是「一次只允许一笔换源」的闸门，也是那一行的加载指示依据。
    // 换源要抓详情 + 抓目录 + 写事务，是条秒级长操作；没有反馈的等待会让用户去点第二行，
    // 两笔事务交错就可能出现「后点的先完成、界面切到不是用户最后选的那本」。
    var pendingUrl by remember { mutableStateOf<String?>(null) }

    // 面板内联是这条链路**唯一**的提示出口：VM 侧不弹 Toast——它的命令通道没有 binder 在收集
    // （宿主绑的是 BookReadViewModel），写了等于没写，见 SourceSwitchViewModel 类 KDoc
    var failureHint by remember { mutableStateOf<SwitchFailureHint?>(null) }
    // 候选那一轮的整流出问题由 VM 持有（面板重开就随新一轮清零），点候选的失败由本地状态持有
    val shownHint = failureHint ?: searchFailure?.let { SwitchFailureHint.SearchFailed }
    val failureText = when (shownHint) {
        SwitchFailureHint.SourceInvalid -> stringResource(R.string.source_switch_target_invalid)
        SwitchFailureHint.AlreadyOnShelf -> stringResource(R.string.source_switch_target_on_shelf)
        SwitchFailureHint.Generic -> stringResource(R.string.source_switch_failed)
        SwitchFailureHint.SearchFailed -> stringResource(R.string.source_switch_search_failed)
        null -> null
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CommonUiTokens.pagePadding)
                .navigationBarsPadding()
        ) {
            Text(
                text = stringResource(R.string.switch_source),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            // 副标题交代「在为哪本书找源」：面板是从阅读页打开的，一句上下文省掉一次来回确认
            Text(
                text = stringResource(
                    R.string.source_switch_subtitle_format,
                    bookName.ifBlank { stringResource(R.string.unknown_book) }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(10.dp))
            if (progress.isRunning) {
                SourceProgressRow(progress)
                Spacer(modifier = Modifier.height(10.dp))
            }
            failureText?.let {
                // 失败句常驻在列表上方：候选此时仍然可用，用户下一个动作就是另选一本
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // 候选区高度限半屏（弹层不该把整页正文挤没，
            // 而「滚得动的候选列表」比「一屏看全」更符合换源这个动作）
            val listHeight = with(LocalDensity.current) {
                LocalWindowInfo.current.containerSize.height.toDp() / 2
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(listHeight)
            ) {
                when {
                    candidates.isNotEmpty() -> CandidateList(
                        candidates = candidates,
                        name = bookName,
                        author = bookAuthor,
                        pendingUrl = pendingUrl,
                        viewModel = viewModel,
                        onItemClick = { book ->
                            if (pendingUrl != null) return@CandidateList
                            pendingUrl = book.noteUrl
                            failureHint = null
                            viewModel.switchSource(oldShelf, book) { result ->
                                result.fold(
                                    onSuccess = { outcome ->
                                        pendingUrl = null
                                        onSwitched(outcome)
                                    },
                                    // 失败只留一句面板内提示 + 保留弹层：
                                    // 仓库在解析阶段失败时一行库都没动，书架仍是原样
                                    onFailure = { e ->
                                        pendingUrl = null
                                        failureHint = switchFailureHintOf(e)
                                    },
                                )
                            }
                        }
                    )

                    isSearching -> SheetStateRow(stringResource(R.string.loading), showSpinner = true)
                    else -> SheetStateRow(stringResource(R.string.source_switch_no_candidates), showSpinner = false)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * 书源进度行：确定性的 [LinearProgressIndicator] +「已收到 X/Y 个书源的结果」。
 *
 * 与 module_find 搜索页同形（同一份 `AggregateSearchEvent` 语义），但**不复用其组件**：
 * 功能模块之间不互相依赖，跨模块 import 直接编译不过；真出现第三处需求时按「共享件上浮两步走」
 * 收到 `lib_book_common`，而不是让本模块去依赖 module_find。
 */
@Composable
private fun SourceProgressRow(progress: CandidateProgress) {
    Column(modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            // 比例只从 CandidateProgress.fraction 取：total 为 0 时它按 0 收，UI 不自己防除零
            progress = { progress.fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(
                R.string.source_switch_source_progress_format,
                progress.finished,
                progress.total
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 候选列表。
 *
 * `key = noteUrl`：本仓列表分页已经踩过一次——重复条目会让 Compose 直接抛异常。VM 侧已按
 * `noteUrl` 全局去重（与这里同口径），但把 key 写上才是「同一本书出现两次立刻暴露」的保险。
 */
@Composable
private fun CandidateList(
    candidates: List<SearchBookEntity>,
    name: String,
    author: String,
    pendingUrl: String?,
    viewModel: SourceSwitchViewModel,
    onItemClick: (SearchBookEntity) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(candidates, key = { it.noteUrl }) { book ->
            // 匹配度每次现算：VM 只交回排好序的候选、不落存分数（分数是排序的中间量，
            // 存下来等于两份事实源），标签档位于是永远与排序依据同源
            val badge = matchBadgeOf(viewModel.matchScore(book, name, author))
            CommonItemCard(
                onClick = { onItemClick(book) },
                // 有一笔换源在跑时整列表置灰不可点：见 pendingUrl 的注释
                enabled = pendingUrl == null,
                shadowElevation = 0.dp,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) {
                CandidateRow(
                    book = book,
                    badge = badge,
                    isApplying = pendingUrl == book.noteUrl,
                )
            }
        }
    }
}

/**
 * 单条候选：书名 + 匹配度标签 / 作者 + 所属书源 / 最新章节。
 *
 * 书名与匹配度标签同一行：标签是「要不要选这条」的首要判据，放到第二行会被书名长度挤到看不见。
 * 书源名（`origin`）单独一枚胶囊——换源换的就是源，这条信息必须与作者同屏可见。
 */
@Composable
private fun CandidateRow(
    book: SearchBookEntity,
    badge: MatchBadge,
    isApplying: Boolean,
) {
    val badgeLabel = when (badge) {
        MatchBadge.Exact -> stringResource(R.string.source_switch_badge_exact)
        MatchBadge.Partial -> stringResource(R.string.source_switch_badge_partial)
        MatchBadge.AuthorOnly -> stringResource(R.string.source_switch_badge_author)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = book.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(8.dp))
            InfoChip(
                text = badgeLabel,
                shape = RoundedCornerShape(50),
                // 只有「完全匹配」提亮到主色容器：其余两档是「可能不是同一本」的备选，
                // 都给高亮等于告诉用户这三条同样可靠
                containerColor = if (badge == MatchBadge.Exact) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (badge == MatchBadge.Exact) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textStyle = MaterialTheme.typography.labelSmall,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        // 作者为空时不硬造占位：第三方站点常把作者留空，而本模块只有「未知书籍」这类书名占位文案，
        // 挪到作者位语义就错了。故整行只在作者与书源名至少有一项可展示时才画。
        if (book.author.isNotBlank() || book.origin.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (book.author.isNotBlank()) {
                    Text(
                        text = book.author,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (book.origin.isNotBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    InfoChip(
                        text = book.origin,
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
        if (book.lastChapter.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.source_switch_latest_format, book.lastChapter),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isApplying) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.source_switch_applying),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 无候选时的两种占位行（加载中 / 空态）。
 *
 * 单独抽出来是为了让「白屏」在这两条分支里根本没有可达路径：调用处的 when 已把三态穷尽。
 */
@Composable
private fun SheetStateRow(text: String, showSpinner: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showSpinner) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(10.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
