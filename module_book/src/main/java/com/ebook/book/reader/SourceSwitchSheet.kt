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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ebook.book.R
import com.ebook.book.mvvm.viewmodel.CandidateProgress
import com.ebook.book.mvvm.viewmodel.SourceSwitchOutcome
import com.ebook.book.mvvm.viewmodel.SourceSwitchViewModel
import com.ebook.common.ui.CommonItemCard
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.InfoChip
import com.ebook.common.ui.preview.AppPreview
import com.ebook.common.ui.preview.SAMPLE_SOURCE_URL
import com.ebook.common.ui.preview.sampleSearchBook
import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.SearchBookEntity

/**
 * 一条候选 + 它与当前书的匹配度标签档。
 *
 * 为什么把标签算好再交下去：`matchScore` 是 [SourceSwitchViewModel] 的 internal 判据，
 * 让面板的长相直接依赖 ViewModel 就等于把这屏锁死在「有 VM」的形态上（`@Preview` 不经 Hilt）。
 * 折成一份不可变的行数据后，根组件只看「这一行标哪一档」，而分数→档位的映射仍在
 * [matchBadgeOf] 那一处，与排序依据同源（VM 只交回排好序的候选、不落存分数）。
 */
internal data class SourceSwitchCandidate(
    val book: SearchBookEntity,
    val badge: MatchBadge,
)

/**
 * 阅读中换源的候选弹层（ADR-0016 决策 8，P3-d）的**壳层**。
 *
 * 形态对齐阅读器既有面板的弹层惯例（`ModalBottomSheet` + 半屏限高列表 + `navigationBarsPadding`）：
 * 五个面板已是同一套 chrome，另立一套会让「从菜单里滑出来的东西」有两种脾气。
 * **不额外套 MaterialTheme**——本弹层是阅读页组合的后代，自然解析到基类 `AppTheme` 装配点那份
 * colorScheme，与其余 chrome 同随外观主题深浅色（AGENTS.md：全局主题由基类/页面装配点提供）。
 *
 * 留在这一层的三件事都带 ViewModel 或本地状态，搬进根组件就再也预览不了：
 * - 四个状态流的收集
 * - 打开即搜一轮（[LaunchedEffect]，以 noteUrl 作 key）
 * - 换源闸门 `pendingUrl` 与面板内联的失败提示 `failureHint`（异步结果回来时才清/置）
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

    ModalBottomSheet(onDismissRequest = onDismiss) {
        SourceSwitchSheetContent(
            bookName = bookName,
            candidates = candidates.map {
                SourceSwitchCandidate(
                    book = it,
                    badge = matchBadgeOf(viewModel.matchScore(it, bookName, bookAuthor)),
                )
            },
            progress = progress,
            isSearching = isSearching,
            failureHint = shownHint,
            pendingUrl = pendingUrl,
            onSwitch = { book ->
                // 闸门与 pendingUrl 同处一层：闸门判的就是「这一层手上有没有正在跑的换源」
                if (pendingUrl != null) return@SourceSwitchSheetContent
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
            },
        )
    }
}

/**
 * 换源面板的**无状态根**：弹层里那一栏内容（AGENTS.md「屏幕的无状态根」）。
 *
 * 只吃不可变状态与回调，于是这些形态第一次可以预览、也能被 JVM 渲染用例组合出来。
 * **弹层壳不在这一层**：`ModalBottomSheet` 是独立窗口，设计期拍不出可靠的一张图，
 * 进出场与遮罩仍由 [SourceSwitchSheet] 持有，这一层只画 sheet 的 body。
 *
 * 三态必须都有可见反馈，不许白屏：搜索中且无候选 → 加载行；搜索结束且无候选 → 空态文案；
 * 有候选 → 列表。书源进度只在「本轮还有源没结束」时占一行，结束即收起（分母含失败的源，
 * 所以不会永远差一格，判据见 `SourceSwitchViewModel.finishRound`）。
 *
 * @param candidates 候选行，匹配度标签档已由壳层按 [matchBadgeOf] 折好
 * @param failureHint 要说在列表上方的失败档；null 表示这一轮没有失败。
 *   它是「本地提示优先于整轮失败」两者折完之后的**唯一**结论（口径见 [SourceSwitchSheet]），
 *   根组件不再自己判谁盖谁——两处各判一次就会长出两套优先级。
 * @param pendingUrl 正在换源的那一条候选的 noteUrl；非空时整列表置灰不可点（见 [SourceSwitchSheet]）
 */
@Composable
internal fun SourceSwitchSheetContent(
    bookName: String,
    candidates: List<SourceSwitchCandidate>,
    progress: CandidateProgress,
    isSearching: Boolean,
    failureHint: SwitchFailureHint?,
    pendingUrl: String?,
    onSwitch: (SearchBookEntity) -> Unit,
) {
    val failureText = when (failureHint) {
        SwitchFailureHint.SourceInvalid -> stringResource(R.string.source_switch_target_invalid)
        SwitchFailureHint.AlreadyOnShelf -> stringResource(R.string.source_switch_target_on_shelf)
        SwitchFailureHint.Generic -> stringResource(R.string.source_switch_failed)
        SwitchFailureHint.SearchFailed -> stringResource(R.string.source_switch_search_failed)
        null -> null
    }

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
                    pendingUrl = pendingUrl,
                    onItemClick = onSwitch,
                )

                isSearching -> SheetStateRow(stringResource(R.string.loading), showSpinner = true)
                else -> SheetStateRow(stringResource(R.string.source_switch_no_candidates), showSpinner = false)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
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
 *
 * 匹配度标签来自 [SourceSwitchCandidate.badge]（壳层按 `matchScore` 现折，见该类说明）：
 * 档位仍是每次现算、不落存中间量，只是把「算」这一步从长相里挪了出去。
 */
@Composable
private fun CandidateList(
    candidates: List<SourceSwitchCandidate>,
    pendingUrl: String?,
    onItemClick: (SearchBookEntity) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(candidates, key = { it.book.noteUrl }) { row ->
            CommonItemCard(
                onClick = { onItemClick(row.book) },
                // 有一笔换源在跑时整列表置灰不可点：见 pendingUrl 的注释
                enabled = pendingUrl == null,
                shadowElevation = 0.dp,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) {
                CandidateRow(
                    book = row.book,
                    badge = row.badge,
                    isApplying = pendingUrl == row.book.noteUrl,
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
                shape = CommonUiTokens.pillShape,
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
                        shape = CommonUiTokens.pillShape,
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

/**
 * 预览：候选列表三档匹配度——**完全匹配 / 部分匹配 / 仅作者匹配**，外加空作者与超长源名。
 *
 * 三档的差别只有一处配色（只有「完全匹配」提亮到 primaryContainer，其余两档走 surfaceVariant），
 * 写坏了不报错、只是把「可能不是同一本」说成与首选同样可靠——两张（深浅）并排才看得出高亮确实
 * 只落在第一行。另外两档边界也在这张图里：作者为空时那一行只剩书源胶囊（第三方站点常把作者留空，
 * 不该硬造占位），超长书名 + 超长源名同屏时看省略号落在哪一列——尾部胶囊被挤出行外就点不着了。
 */
@PreviewLightDark
@Composable
private fun SourceSwitchSheetCandidatesPreview() {
    AppPreview { previewSourceSwitchSheet() }
}

/**
 * 预览：**一笔换源正在跑**（pendingUrl 命中第二行）。
 *
 * 这一档同时出现两件事，缺一个都是静默缺陷：那一行下方多出「正在换源…」的转圈，而**整个列表**
 * 置灰不可点（`enabled = pendingUrl == null`）。只做前者的话用户会在等待中点第二行，
 * 两笔「先插新、后删旧」的事务交错，最后界面可能切到不是他最后选的那本；只做后者的话
 * 等待没有任何反馈，看上去像面板卡死。
 */
@Preview(showBackground = true)
@Composable
private fun SourceSwitchSheetApplyingPreview() {
    AppPreview {
        previewSourceSwitchSheet(pendingUrl = "$SAMPLE_SOURCE_URL/book/2")
    }
}

/**
 * 预览：**本轮还在收书源结果**（进度行 + 还没有候选）。
 *
 * `progress.isRunning` 为真才占那一行（分母含失败的源，见 CandidateProgress）：进度条与
 * 「已收到 X/Y 个书源的结果」是一件事的两半，漏画比例就只能靠数字猜还要等多久。
 * 这一档下面跟着的是「加载数据中…那一行」——候选区此时没有内容，白屏就是从这里来的。
 */
@Preview(showBackground = true)
@Composable
private fun SourceSwitchSheetProgressPreview() {
    AppPreview {
        previewSourceSwitchSheet(
            candidates = emptyList(),
            progress = CandidateProgress(finished = 2, total = 5),
            isSearching = true,
        )
    }
}

/**
 * 预览：候选区两种占位行——**还在搜**与**搜完了什么都没有**。
 *
 * 两句话各走各的：把「还在搜」画成「没有找到」，用户会关掉面板不再等（本轮结果马上就到）；
 * 反过来则是让他对着一行转圈一直等一笔已经结束的搜索。这一张拍的是后者（`isSearching = false`），
 * 前者见 [SourceSwitchSheetProgressPreview]。
 */
@Preview(showBackground = true)
@Composable
private fun SourceSwitchSheetEmptyPreview() {
    AppPreview {
        previewSourceSwitchSheet(candidates = emptyList(), progress = CandidateProgress(total = 5, finished = 5))
    }
}

/**
 * 预览：换源失败的四种说法，一次出四张图。
 *
 * 四句是四个**动作不同**的档位，把它们并成一句「换源失败」就会指错路：
 * - `SourceInvalid`（源已下架/坏行）→ 另选一本，或回书源管理页重导；
 * - `AlreadyOnShelf` → 动作是「去读架上那一本」，不是在同一堆候选里再找一次这本书；
 * - `Generic` → 要交代「书架上的书未受影响」；
 * - `SearchFailed` → 候选那一轮整条流出问题，一笔换源都没发生过，那句「书架未受影响」答非所问。
 * 失败时候选列表仍然可用、弹层也不关——四张图里列表都该在，画成整片空白就是错的。
 */
@Preview(showBackground = true)
@Composable
private fun SourceSwitchSheetFailurePreview(
    @PreviewParameter(SwitchFailureHintProvider::class) hint: SwitchFailureHint,
) {
    AppPreview { previewSourceSwitchSheet(failureHint = hint) }
}

/**
 * 预览：**书名取不到**时的副标题（`bookName` 为空串）。
 *
 * 副标题那句要说「正在为《…》查找」，空书名时落「未知书籍」占位。写坏了不报错，只会画出一句
 * 「正在为《》在其他书源中查找同一本书」——而这条链路上书名恰恰可能取不到（本地书、缺元信息的行）。
 */
@Preview(showBackground = true)
@Composable
private fun SourceSwitchSheetUnknownBookPreview() {
    AppPreview { previewSourceSwitchSheet(bookName = "") }
}

/**
 * 面板 body 的预览装配点：默认喂「三档匹配度的候选」，各档按需覆盖。
 *
 * 与 [com.ebook.book.ImportBookActivity] 的 `previewImportBookScreen` 同一口径：预览函数只声明
 * 「哪一档被改了」，回调一律 no-op（这些回调背后是换源事务，设计期既没有 VM 也没有库）。
 */
@Composable
private fun previewSourceSwitchSheet(
    bookName: String = "山海拾遗",
    candidates: List<SourceSwitchCandidate> = previewSwitchCandidates(),
    progress: CandidateProgress = CandidateProgress(),
    isSearching: Boolean = false,
    failureHint: SwitchFailureHint? = null,
    pendingUrl: String? = null,
) {
    SourceSwitchSheetContent(
        bookName = bookName,
        candidates = candidates,
        progress = progress,
        isSearching = isSearching,
        failureHint = failureHint,
        pendingUrl = pendingUrl,
        onSwitch = {},
    )
}

/**
 * 候选行样例：三档匹配度各一条，第二条作者为空、第三条书名与源名都长到该被省略。
 *
 * 书名走 [com.ebook.common.ui.preview.sampleSearchBook]（ebook 域的共享条目形状），
 * 而 [SourceSwitchCandidate] 是本页自己的行数据（实体 + 已折好的档位），故就地组装。
 * `internal` 的理由与 [com.ebook.book.previewDownloadGroups] 同：预览与渲染冒烟测试吃同一份样例。
 */
internal fun previewSwitchCandidates(): List<SourceSwitchCandidate> = listOf(
    SourceSwitchCandidate(book = sampleSearchBook(index = 1), badge = MatchBadge.Exact),
    SourceSwitchCandidate(
        book = sampleSearchBook(index = 2, author = ""),
        badge = MatchBadge.Partial,
    ),
    SourceSwitchCandidate(
        book = sampleSearchBook(
            index = 3,
            name = "一部书名长得该被省略掉后半段的示例作品",
            origin = "一家站名同样长到会与作者抢同一行的样例书源",
        ),
        badge = MatchBadge.AuthorOnly,
    ),
)

/** 四档失败提示按枚举声明顺序出图，图上直接用枚举名标注，免得对着图猜是哪一句 */
private class SwitchFailureHintProvider : PreviewParameterProvider<SwitchFailureHint> {
    override val values: Sequence<SwitchFailureHint>
        get() = SwitchFailureHint.entries.toList().asSequence()

    override fun getDisplayName(index: Int): String = SwitchFailureHint.entries[index].name
}
