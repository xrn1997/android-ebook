package com.ebook.me.view

import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import com.ebook.common.event.KeyCode
import com.ebook.common.ui.CommonCard
import com.ebook.common.ui.CommonListDivider
import com.ebook.common.ui.CommonListItem
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.SectionLabel
import com.ebook.common.ui.preview.AppPreview
import com.ebook.me.R
import com.ebook.me.mvvm.viewmodel.CacheManageViewModel
import com.ebook.me.mvvm.viewmodel.categoryTitleRes
import com.ebook.me.repository.CacheType
import com.ebook.common.util.formatSize
import com.therouter.TheRouter
import com.therouter.router.Route
import com.therouter.router.matchRouteMap
import com.xrn1997.common.mvvm.compose.BaseMvvmActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * 缓存管理页：分类展示 + 分类明细 BottomSheet + 全量清理（带确认）。
 *
 * 设置页的「缓存管理」入口跳转到此。相比设置页原「点击即全清」的交互：
 * - 点击分类行打开 BottomSheet，先看清该类缓存的具体内容（目录/文件 + 大小），
 *   再通过 Sheet 内「清理」按钮显式触发——带箭头的行必须是「点进去」而非直接执行
 * - 「清理全部缓存」保留二次确认（影响面大，防误触）
 *
 * 清理成功提示由 ViewModel 经 [com.xrn1997.common.mvvm.viewmodel.BaseViewModel.sendToast] 下发
 * （VM 注入 Application Context 后自行解析文案资源，页面不再收集事件流）。
 */
@AndroidEntryPoint
@Route(path = KeyCode.Me.CACHE_PATH)
class CacheManageActivity : BaseMvvmActivity<CacheManageViewModel>() {
    override val viewModel: CacheManageViewModel by viewModels()

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        toolbarTitle.value = getString(R.string.cache_manage_title)
    }

    @Composable
    override fun PageContent() {
        val cacheState by viewModel.cacheState.collectAsState()
        val detailState by viewModel.detailState.collectAsState()

        // 书架在 module_main，独立运行时该路由不存在（TheRouter 找不到路由只记一行日志、不报错），
        // 所以先探测：取不到就把书籍行留成纯展示，不摆一个点了没反应的箭头
        val canOpenShelf = matchRouteMap(KeyCode.Main.MAIN_PATH) != null

        // 清理成功提示由 ViewModel 经 sendToast 下发（文案在 VM 侧走资源解析，见 CacheManageViewModel）
        CacheManageScreen(
            uiState = cacheState,
            detailState = detailState,
            onOpenDetail = { viewModel.openDetail(it) },
            onDismissDetail = { viewModel.dismissDetail() },
            onClearCategory = { type -> viewModel.clearCategory(type) },
            onClearAll = { viewModel.clearAll() },
            canOpenShelf = canOpenShelf,
            // 本页不删书，只把人送去做这件事：MainActivity 未声明 launchMode，CLEAR_TOP 会重建它
            // 并落回 startDestination（正是书架），所以不需要「选哪个 Tab」的跨模块约定。
            // 代价是设置/缓存两页一并出栈——「去管理我的书」通常正是这个语义。
            onOpenShelf = {
                TheRouter.build(KeyCode.Main.MAIN_PATH)
                    .withFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .navigation()
            }
        )
    }
}

/**
 * 缓存管理页内容：总占用摘要卡 + 分类明细卡 + 底部全量清理按钮 + 分类明细 Sheet。
 */
@Composable
fun CacheManageScreen(
    uiState: CacheManageViewModel.CacheUiState,
    detailState: CacheManageViewModel.CacheDetailState?,
    onOpenDetail: (CacheType) -> Unit,
    onDismissDetail: () -> Unit,
    onClearCategory: (CacheType) -> Unit,
    onClearAll: () -> Unit,
    canOpenShelf: Boolean,
    onOpenShelf: () -> Unit,
) {
    var showClearAllDialog by remember { mutableStateOf(false) }
    // 总占用空串 = 计算中，占位文案经资源解析
    val totalDisplay = uiState.totalText.ifEmpty { stringResource(R.string.common_pending) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // 页面左右留白取统一令牌（唯一事实源），不在页内写同语义的 16dp 字面值
                .padding(horizontal = CommonUiTokens.pagePadding)
        ) {
            // 总占用摘要：让用户先建立整体量级认知，再看明细
            CommonCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.cache_total_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = totalDisplay,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // 总占用摘要卡与分类明细卡之间的区块间距取统一令牌：与书城等页密度一致
            Spacer(modifier = Modifier.height(CommonUiTokens.sectionSpacing))

            SectionLabel(text = stringResource(R.string.cache_section_detail))
            CommonCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    uiState.items.forEachIndexed { index, item ->
                        if (index > 0) CommonListDivider()
                        val (containerColor, contentColor) = categoryColors(item.type)
                        CommonListItem(
                            icon = categoryIcon(item.type),
                            title = stringResource(categoryTitleRes(item.type)),
                            trailingText = item.sizeText,
                            iconContainerColor = containerColor,
                            iconContentColor = contentColor,
                            onClick = { onOpenDetail(item.type) }
                        )
                    }
                }
            }

            // 分类明细卡与书籍内容卡之间的区块间距，同上取统一令牌
            Spacer(modifier = Modifier.height(CommonUiTokens.sectionSpacing))

            // 书籍内容：解释「缓存总占用」为什么远小于手机报的应用占用。它不参与本页清理
            // （删书的唯一入口仍是书架长按），但书架路由可达时这一行可点，把人送到能删的地方。
            CommonCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    // 这一行直接复用共享的 CommonListItem，不再自己拼一份行几何（ADR-0006：
                    // 跨模块共享组件不各模块重复实现）。两处细节由组件承担：
                    // - `onClick` 虽然必传，但它不是「必须可点」——路由取不到时（独立运行，
                    //   书架不在依赖图里）传 `enabled = false` 即可拿到组件内建的诚实置灰渲染
                    //   （图标与标题一并走 onSurfaceVariant，并把 disabled 写进 semantics），
                    //   不会留下一个点了没反应的假入口；箭头一并由 `showArrow` 控制。
                    // - 图标容器颜色取中性 surfaceVariant：这一行是「说明」而不是入口，
                    //   不借用 primary/secondary/tertiary 那套入口色。
                    CommonListItem(
                        icon = Icons.AutoMirrored.Outlined.MenuBook,
                        title = stringResource(R.string.cache_books_title),
                        iconContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        iconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        trailingText = stringResource(
                            R.string.cache_books_value,
                            uiState.booksSizeText,
                            uiState.bookCount
                        ),
                        showArrow = canOpenShelf,
                        enabled = canOpenShelf,
                        onClick = onOpenShelf,
                    )
                    CommonListDivider()
                    Text(
                        text = stringResource(
                            if (canOpenShelf) R.string.cache_books_desc_open else R.string.cache_books_desc
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }

            // 书籍内容卡与全量清理按钮之间的区块间距：按钮是页面最后一个区块，
            // 与卡片之间同用区块间距令牌（此前 24dp 比卡片之间多一倍，密度不齐）
            Spacer(modifier = Modifier.height(CommonUiTokens.sectionSpacing))

            // 全量清理：影响面大（含未识别目录），按钮置灰零缓存 + 点击二次确认
            Button(
                onClick = { showClearAllDialog = true },
                enabled = uiState.totalBytes > 0L,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.cache_clear_all))
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // 分类明细：点进去看内容 → 看清后由 Sheet 内按钮显式清理（不二次弹窗）
    detailState?.let { detail ->
        CacheDetailSheet(
            state = detail,
            onDismiss = onDismissDetail,
            onClear = { onClearCategory(detail.type) }
        )
    }

    // 全量清理二次确认：影响面大防误触
    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text(stringResource(R.string.cache_clear_all)) },
            text = { Text(stringResource(R.string.cache_clear_all_message, totalDisplay)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearAllDialog = false
                    onClearAll()
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/**
 * 分类明细 BottomSheet：分类说明 + 内容列表（名称 + 大小）+ 底部清理按钮。
 *
 * 列表高度限制 40%（长列表内部滚动），避免 Sheet 占满全屏。
 *
 * 内容区的整体左右内边距取 [CommonUiTokens.pagePadding]：它是弹层内容区的页面级留白，
 * 与页面正文同一语义（不是某个局部元素的额外缩进），故用同一令牌。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CacheDetailSheet(
    state: CacheManageViewModel.CacheDetailState,
    onDismiss: () -> Unit,
    onClear: () -> Unit
) {
    // 大小空串 = 计算中，占位文案经资源解析
    val sizeDisplay = state.sizeText.ifEmpty { stringResource(R.string.common_pending) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 弹层内容区整体左右内边距＝页面级留白语义，取统一令牌（本页此前为 24dp）
                .padding(horizontal = CommonUiTokens.pagePadding)
                .navigationBarsPadding()
        ) {
            Text(
                text = stringResource(categoryTitleRes(state.type)),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(categoryDescriptionRes(state.type)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (state.loading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(24.dp))
                }
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (state.entries.isEmpty()) {
                        Text(
                            text = stringResource(R.string.cache_empty_detail),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 20.dp)
                        )
                    } else {
                        state.entries.forEachIndexed { index, entry ->
                            if (index > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (entry.isDirectory) {
                                        Icons.Outlined.Folder
                                    } else {
                                        Icons.Outlined.Description
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = formatSize(entry.sizeBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onClear,
                enabled = !state.loading && state.entries.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(
                        R.string.cache_clear_category_button,
                        stringResource(categoryTitleRes(state.type)),
                        sizeDisplay
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** 分类说明资源：BottomSheet 内告知用户这类缓存是什么、清理后有什么影响 */
@StringRes
private fun categoryDescriptionRes(type: CacheType): Int = when (type) {
    CacheType.IMAGE -> R.string.cache_category_image_desc
    CacheType.TEMP -> R.string.cache_category_temp_desc
    CacheType.OTHER -> R.string.cache_category_other_desc
}

/** 分类图标 */
private fun categoryIcon(type: CacheType): ImageVector = when (type) {
    CacheType.IMAGE -> Icons.Outlined.Image
    CacheType.TEMP -> Icons.Outlined.Description
    CacheType.OTHER -> Icons.Outlined.FolderOpen
}

/** 分类图标配色：与 MePage 菜单一致的 primary/secondary/tertiary 语义色 */
@Composable
private fun categoryColors(type: CacheType): Pair<Color, Color> = when (type) {
    CacheType.IMAGE -> MaterialTheme.colorScheme.primaryContainer to
            MaterialTheme.colorScheme.onPrimaryContainer
    CacheType.TEMP -> MaterialTheme.colorScheme.secondaryContainer to
            MaterialTheme.colorScheme.onSecondaryContainer
    CacheType.OTHER -> MaterialTheme.colorScheme.tertiaryContainer to
            MaterialTheme.colorScheme.onTertiaryContainer
}

/**
 * 预览：缓存管理页，三档分类都有内容。
 *
 * 页面上的两件事都在这里核对：① 「可清理总量」与「书籍内容占用」是**两个口径**，
 * 后者不计入前者、也不在本页删（删书唯一入口是书架长按，见 ADR-0026）；② 三档分类各自
 * 的图标容器语义色（primary/secondary/tertiary container）互不重复。
 */
@Preview(showBackground = true)
@Composable
private fun CacheManageScreenPreview() {
    AppPreview {
        CacheManageScreen(
            uiState = CacheManageViewModel.CacheUiState(
                items = listOf(
                    CacheManageViewModel.CacheItemState(CacheType.IMAGE, "12.8 MB"),
                    CacheManageViewModel.CacheItemState(CacheType.TEMP, "4.1 MB"),
                    CacheManageViewModel.CacheItemState(CacheType.OTHER, "1.5 MB"),
                ),
                totalText = "18.4 MB",
                totalBytes = 19_294_208L,
                booksSizeText = "142.6 MB",
                bookCount = 12,
            ),
            // 明细抽屉关闭：BottomSheet 是「点进某一档之后」才出现的形态，
            // 静态预览里弹着它反而挡住了本页真正要看的两行占用口径
            detailState = null,
            onOpenDetail = {},
            onDismissDetail = {},
            onClearCategory = {},
            onClearAll = {},
            canOpenShelf = true,
            onOpenShelf = {},
        )
    }
}

/**
 * 预览：首帧「计算中」+ 独立运行态。
 *
 * `totalText`/`booksSizeText` 给空串走 `common_pending` 占位（进页面第一眼必然是这个形态，
 * 目录遍历还没回来）；`canOpenShelf = false` 是模块独立运行时没有书架路由的退化档——
 * 那一行应当不可点、不留假箭头。
 */
@PreviewLightDark
@Composable
private fun CacheManageScreenPendingPreview() {
    AppPreview {
        CacheManageScreen(
            uiState = CacheManageViewModel.CacheUiState(
                items = listOf(
                    CacheManageViewModel.CacheItemState(CacheType.IMAGE, ""),
                    CacheManageViewModel.CacheItemState(CacheType.TEMP, ""),
                    CacheManageViewModel.CacheItemState(CacheType.OTHER, ""),
                ),
            ),
            detailState = null,
            onOpenDetail = {},
            onDismissDetail = {},
            onClearCategory = {},
            onClearAll = {},
            canOpenShelf = false,
            onOpenShelf = {},
        )
    }
}
