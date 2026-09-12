package com.ebook.book

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ebook.book.mvvm.viewmodel.BookChapterSelection
import com.ebook.book.mvvm.viewmodel.BookSelectionState
import com.ebook.book.mvvm.viewmodel.DownloadBookGroup
import com.ebook.book.mvvm.viewmodel.DownloadCenterStep
import com.ebook.book.mvvm.viewmodel.DownloadManageViewModel
import com.ebook.book.reader.BookChapterSelectPage
import com.ebook.book.repository.DownloadState
import com.ebook.common.event.KeyCode
import com.ebook.common.ui.BookCover
import com.ebook.common.ui.CommonItemCard
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.InfoChip
import com.permissionx.guolindev.PermissionX
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseMvvmActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * 下载管理页（Compose，替代书架上那个 80dp 的下载小弹窗）。
 *
 * 布局：一级按书分组的任务列表（无概览卡、无集中控制——只展示 + 导航，
 * 全局状态由行内进行态表达，操作经通知或二级页上下文承接）。
 *
 * 进度口径两条并行（刻意分开，见 [DownloadBookGroup]）：
 * - "剩余 N 章"：`download_chapter` 队列里这本书还没下完的量，随批次消长
 * - "已缓存 y/z"：全书已可离线的比例（进度条），随阅读/下载单调增长
 *
 * 数据来源：任务/覆盖率实时查库（[DownloadManageViewModel.loadGroups]），当前进度经
 * [DownloadState] 状态流推送；打开页面时若队列有任务则自动续跑（对齐原弹窗 initWait）。
 */
@AndroidEntryPoint
@Route(path = KeyCode.Book.DOWNLOAD_PATH)
class DownloadManageActivity : BaseMvvmActivity<DownloadManageViewModel>() {
    override val viewModel: DownloadManageViewModel by viewModels()

    /**
     * 阅读器直达的一本书（仅在冷启动消费一次）。
     *
     * 旋转重建（savedInstanceState != null）时 ViewModel 已持有 bookSheet 现场，
     * 不重放直达参数，避免把已退回一级列表的用户硬拽回二级选章。
     *
     * [pickParams] 消费后保持非空，作为「本次会话从阅读器直达」的返回语义标记：
     * 直达态下二级的返回直接退出本页（见 DownloadCenterScreen 的 BackHandler），
     * 经显式导航（取消本书后回一级）才清空退出直达态。
     */
    internal class PickBookParams(
        val noteUrl: String,
        val tag: String,
        val focusChapter: Int,
    )

    /** 阅读器直达参数：仅在 onCreate(savedInstanceState==null) 时消费一次，旋转重建不再重放。 */
    internal var pickParams: PickBookParams? = null

    companion object {
        const val EXTRA_NOTE_URL = "extra_note_url"
        const val EXTRA_TAG = "extra_tag"
        const val EXTRA_FOCUS_CHAPTER = "extra_focus_chapter"
        const val EXTRA_OPEN_PICK = "extra_open_pick"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toolbarTitle.value = getString(R.string.download_manage_title)
        // 阅读器直达只该发生在冷启动：旋转重建（savedInstanceState != null）时 ViewModel 已
        // 持有 bookSheet 现场，不重放直达参数，避免用户退回一级后旋转被硬拽回二级选章。
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_OPEN_PICK, false)) {
            val params = PickBookParams(
                noteUrl = intent.getStringExtra(EXTRA_NOTE_URL) ?: "",
                tag = intent.getStringExtra(EXTRA_TAG) ?: "",
                focusChapter = intent.getIntExtra(EXTRA_FOCUS_CHAPTER, -1)
            )
            // 直达切换在 onCreate 同步执行：首帧即二级选章，不给一级列表闪一帧
            // 「暂无下载任务」的机会（组合作用域里再切就晚于首帧了）。pickParams
            // 保持非空作为「直达态」标记，返回语义见 DownloadCenterScreen 的 BackHandler。
            pickParams = params
            viewModel.openBook(params.noteUrl, params.tag, params.focusChapter)
        }
    }

    @Composable
    override fun PageContent() {
        DownloadCenterScreen(activity = this, viewModel = viewModel)
    }

    /**
     * 申请下载进度通知权限（POST_NOTIFICATIONS）。
     *
     * 无论授予与否都回调 [onResult]：通知只是进度的展示渠道，前台服务与落库不依赖它；
     * 把它当成下载前置门槛会造成"拒绝过一次通知 → 点下载完全没反应"。
     */
    fun requestDownloadPermission(onResult: () -> Unit) {
        PermissionX
            .init(this)
            .permissions(PermissionX.permission.POST_NOTIFICATIONS)
            .request { _: Boolean, _: List<String?>?, _: List<String?>? -> onResult() }
    }
}

/**
 * 下载中心两级编排：一级按书任务列表，二级该书选章整屏页。
 *
 * 系统返回：二级 → 一级；一级 → 退出（finish）。
 * 从阅读器带 extras 进入时直达二级（EXTRA_OPEN_PICK），此时二级返回**直接退出**
 * 回阅读器（用户不是从一级来的，回落一级是绕路）；经显式导航（取消本书后回一级）
 * 清掉直达标记后，返回栈恢复常规语义。
 */
@Composable
private fun DownloadCenterScreen(
    activity: DownloadManageActivity,
    viewModel: DownloadManageViewModel,
) {
    val step by viewModel.step.collectAsState()

    BackHandler(enabled = step is DownloadCenterStep.PickBook) {
        if (activity.pickParams != null) {
            // 阅读器直达态：返回直接退出本页回阅读器，不再回落一级
            activity.finish()
        } else {
            // 从一级点书进入的二级：返回回一级（既有栈语义）
            viewModel.backToBooks()
        }
    }

    // 状态驱动刷新：进度每推进一章，一级分组与（若在二级）该书状态标签同步刷新；
    // 打开页面时若队列有任务则自动续跑（对齐原弹窗 initWait，见 resumeIfPending）
    LaunchedEffect(Unit) {
        viewModel.loadGroups()
        viewModel.resumeIfPending()
        viewModel.downloadState.collect { s ->
            viewModel.onDownloadState(s)
            when (s) {
                is DownloadState.Progress -> {
                    viewModel.refreshSelection()
                    viewModel.loadGroups()
                }
                DownloadState.Paused, DownloadState.Finished -> {
                    // 暂停/收尾也刷新二级状态：isDownloading 已在 onDownloadState 置 false，
                    // activeChapterIndex 收起，二级「下载中」徽章与一级卡片同口径不再误显
                    viewModel.refreshSelection()
                    viewModel.loadGroups()
                }
            }
        }
    }

    when (val current = step) {
        is DownloadCenterStep.Books -> DownloadManageScreen(
            viewModel = viewModel,
            onOpenBook = { group -> viewModel.openBook(group.noteUrl, group.tag) }
        )

        is DownloadCenterStep.PickBook -> {
            val bookSheet by viewModel.bookSheet.collectAsState()
            // 取消本书二次确认：书维度操作归书上下文（二级操作面板触发），确认后回一级
            var pendingCancelBook by remember { mutableStateOf<BookChapterSelection?>(null) }
            BookChapterSelectPage(
                state = bookSheet ?: BookSelectionState.Loading,
                onConfirm = { selected ->
                    activity.requestDownloadPermission { viewModel.confirmDownload(selected) }
                },
                // 暂停/继续都只作用于「本书」这一维度，不需要二次确认：
                // 暂停不丢任务、继续只是恢复取篇，两者都可逆（ADR-0036 决策 1/3）
                onPauseBook = { selection -> viewModel.pauseBook(selection.noteUrl) },
                onResumeBook = { selection -> viewModel.resumeBook(selection.noteUrl) },
                onCancelBook = { selection -> pendingCancelBook = selection },
                onRetry = { viewModel.refreshSelection() },
            )
            pendingCancelBook?.let { ready ->
                AlertDialog(
                    onDismissRequest = { pendingCancelBook = null },
                    text = {
                        Text(stringResource(R.string.download_manage_cancel_book_confirm, ready.bookName))
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            pendingCancelBook = null
                            // 取消后回一级是显式导航（非返回）：退出直达态，
                            // 此后二级返回恢复「回一级」的常规栈语义
                            activity.pickParams = null
                            viewModel.cancelBook(ready.noteUrl)
                            viewModel.backToBooks()
                        }) {
                            Text(
                                stringResource(R.string.download_manage_cancel_book),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingCancelBook = null }) {
                            Text(stringResource(com.ebook.common.R.string.cancel))
                        }
                    }
                )
            }
        }
    }
}

/**
 * 一级：按书下载列表。
 *
 * - 书行 = [CommonItemCard]（12dp 圆角、listSpacing 行距）：封面 + 书名 + 覆盖率进度条 +
 *   进行态副行（「正在下载 第 M 章」，仅 Progress 期间展示）+ 合并元信息行
 *   （「已缓存 y/z · 剩余 N 章」）；尾箭头；整行点击进入该书选章二级页。
 * - 行内信息层级：进度条紧贴书名（长期覆盖率一眼可见），下载中的活进度用主色副行
 *   插在进度条与元信息之间——「正在发生的事」优先于「累计的事实」。
 * - 本页不放集中控制按钮（播放/暂停/取消）：全局状态由行内进行态表达，操作经通知或
 *   二级页上下文承接（设计修订：去掉 FAB 与「取消全部」，保持「只展示 + 导航」单一职责）。
 */
@Composable
fun DownloadManageScreen(
    viewModel: DownloadManageViewModel,
    onOpenBook: (DownloadBookGroup) -> Unit
) {
    val groups by viewModel.groups.collectAsState()

    if (groups.isEmpty()) {
        EmptyState(modifier = Modifier.fillMaxSize())
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                // 基类 Scaffold 在带工具栏页面不注入系统栏 insets（内容 edge-to-edge），
                // 末行需自行避开手势条，否则最后一本书被导航条压住
                .navigationBarsPadding()
                .padding(horizontal = CommonUiTokens.pagePadding),
            contentPadding = PaddingValues(
                top = CommonUiTokens.listSpacing,
                bottom = CommonUiTokens.sectionSpacing
            ),
            verticalArrangement = Arrangement.spacedBy(CommonUiTokens.listSpacing)
        ) {
            items(groups, key = { it.noteUrl }) { group ->
                DownloadBookRow(
                    group = group,
                    onClick = { onOpenBook(group) }
                )
            }
        }
    }
}

/**
 * 单本书的下载行：封面 + 书名（尾随「已暂停」胶囊）+ 覆盖率进度 + 进行态副行 + 元信息行 + 尾箭头。
 *
 * 取消本书/暂停本书/继续下载都不在行内（在二级页操作面板）；本行只负责「点进去选章」，
 * 暂停态只**展示**不在此处切换——一级维持「只展示 + 导航」的单一职责（见 ADR-0035 修订说明）。
 * 元信息把两个进度口径合并为一行展示：「已缓存 y/z 章」是全书覆盖率的长期事实、
 * 「剩余 N 章」是本批队列的瞬时余量（口径分立见 [DownloadBookGroup]），合并只省一行、
 * 语义仍是两件事；下载中的活进度（主色副行）单独占一行，不与元信息混排。
 */
@Composable
private fun DownloadBookRow(
    group: DownloadBookGroup,
    onClick: () -> Unit,
) {
    val coverageRatio = remember(group.totalChapters, group.cachedChapters) {
        if (group.totalChapters > 0) group.cachedChapters.toFloat() / group.totalChapters else 0f
    }

    CommonItemCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BookCover(
                url = group.coverUrl,
                modifier = Modifier.size(width = 48.dp, height = 64.dp),
                contentDescription = group.bookName
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                // 书名行尾挂「已暂停」胶囊：暂停是书级事实（任务保留、取篇跳过，见 ADR-0036），
                // 不标出来时一级只剩「剩余 N 章」却一直不推进，用户无从判断是卡住还是被自己暂停了
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = group.bookName,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (group.paused) {
                        Spacer(modifier = Modifier.width(6.dp))
                        InfoChip(
                            text = stringResource(R.string.download_manage_paused_tag),
                            shape = RoundedCornerShape(50),
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            textStyle = MaterialTheme.typography.labelSmall,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { coverageRatio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                // 进行态优先于静态元信息：下载中的书，活进度（主色）紧跟进度条
                group.activeChapter?.let { active ->
                    Text(
                        text = stringResource(
                            R.string.download_manage_downloading_chapter_format,
                            active.durChapterIndex + 1,
                            active.durChapterName
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                Text(
                    text = stringResource(
                        R.string.download_manage_meta_format,
                        group.cachedChapters,
                        group.totalChapters,
                        group.remaining
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 无任务时的空态占位：图标 + 主文案 + 引导副文案。
 *
 * 空态是唯一的「怎么用」教学位：告诉用户下载任务从哪里发起（阅读页顶部下载入口），
 * 而不是只丢一句「暂无任务」让新用户对着白页猜。
 */
@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.CloudDownload,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.download_manage_no_task),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.download_manage_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
