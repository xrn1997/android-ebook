package com.ebook.me.view

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ebook.api.entity.SourceFormat
import com.ebook.common.analyze.source.BookSourceItem
import com.ebook.common.event.KeyCode
import com.ebook.common.ui.CommonListDivider
import com.ebook.common.ui.CommonListItem
import com.ebook.common.ui.CommonItemCard
import com.ebook.common.ui.CommonUiTokens
import com.ebook.common.ui.InfoChip
import com.ebook.me.R
import com.ebook.me.domain.ScriptWarning
import com.ebook.me.domain.ValidationReason
import com.ebook.me.domain.ValidationResult
import com.ebook.me.mvvm.viewmodel.BookSourceViewModel
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseMvvmActivity
import com.xrn1997.common.ui.NoDataView
import com.xrn1997.common.util.ToastUtil
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书源管理页：清单 + 启用/禁用 + 设为默认 + 删除 + JSON 导入导出（ADR-0016 第 9 条 / 路线图 P2）。
 *
 * 页面形态对齐同模块的 [CacheManageActivity]：条目用 `lib_book_common` 的共享组件
 * （[CommonItemCard] 作壳、[CommonListItem] 作操作层行、[InfoChip] 作标记），
 * 颜色全部走 `MaterialTheme.colorScheme`，主题由基类装配（本页不重复包 MaterialTheme）。
 *
 * **顶部两个动作不放 Toolbar**：基类 `ToolbarLayout` 没有 actions 插槽，为一颗按钮自绘整条
 * 顶栏要连带接管返回箭头、insets 与深浅色配色（极易漏，本仓书籍详情页已就此留过注释），
 * 所以导入/导出做成内容区首行右侧的两个 IconButton——视觉上仍是「右上角」。
 *
 * **导入导出的文件读写在本页做**（SAF 的 `contentResolver` 归属页面），ViewModel 只管
 * 「文本 → 预览项」与「预览项 → 落库」两件事，见 [BookSourceViewModel]。
 * 读写一律挪到 [Dispatchers.IO]：SAF 走跨进程 ContentProvider，主线程读整包书源 JSON
 * （社区常见几 MB）会掉帧甚至 ANR。
 *
 * 独立运行（isModule=true）不需要跨模块路由占位：本页路由就在 module_me 自己名下，
 * 但两份 Manifest 都得声明它（清单是替换关系，漏独立清单就没这个页面）。
 */
@AndroidEntryPoint
@Route(path = KeyCode.Me.BOOK_SOURCE_PATH)
class BookSourceManageActivity : BaseMvvmActivity<BookSourceViewModel>() {
    override val viewModel: BookSourceViewModel by viewModels()

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        toolbarTitle.value = getString(R.string.book_source_title)
    }

    @Composable
    override fun PageContent() {
        val state by viewModel.bookSourceState.collectAsState()
        val context = LocalContext.current
        val resolver = context.contentResolver
        // 协程作用域跟着组合走：SAF 回调回来时页面仍在栈顶，读写完的 Toast 也发得到
        val ioScope = rememberCoroutineScope()

        // 提示文案在组合期解析好再交给一次性命令（回调不是 @Composable 上下文）
        val readFailedText = stringResource(R.string.book_source_read_failed)
        val exportEmptyText = stringResource(R.string.book_source_export_empty)
        val exportSuccessText = stringResource(R.string.book_source_export_success)
        val exportFailedText = stringResource(R.string.book_source_export_failed)
        val noticeText = state.notice?.let { noticeMessage(it) }

        // 一次性提示：弹完立刻清零，否则页面重组（如清单再来一帧）会把同一条 Toast 再弹一遍
        LaunchedEffect(state.notice) {
            if (noticeText != null) {
                ToastUtil.showShort(context, noticeText)
                viewModel.consumeNotice()
            }
        }

        // 待写入的导出内容：SAF 的 CreateDocument 只回一个用户选定的 Uri，「要写哪一份」得自己存住。
        // 用页面状态而不是 ViewModel，是因为它纯粹属于这次文件对话框的往返，
        // 转屏重建时随组合一起丢掉才对——留着反而可能把上一次的清单写进新文件。
        var pendingExport by remember { mutableStateOf<String?>(null) }

        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                ioScope.launch {
                    // UTF-8 读取：社区书源 JSON 的通行编码；GBK 等少见编码会解不成 JSON、
                    // 由 parseImportJson 走「读不出内容」的提示，不做编码嗅探（MVP 不值得）
                    val text = withContext(Dispatchers.IO) { uri.readJsonText(resolver) }
                    if (text == null) ToastUtil.showShort(context, readFailedText)
                    else viewModel.parseImportJson(text)
                }
            }
        }

        val exportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(MIME_JSON)
        ) { uri ->
            val json = pendingExport
            pendingExport = null
            if (uri != null && json != null) {
                ioScope.launch {
                    val written = withContext(Dispatchers.IO) { uri.writeJsonText(resolver, json) }
                    ToastUtil.showShort(
                        context,
                        if (written) exportSuccessText else exportFailedText
                    )
                }
            }
        }

        BookSourceManageScreen(
            state = state,
            onImportClick = { importLauncher.launch(arrayOf(MIME_JSON, MIME_TEXT)) },
            onExportAllClick = {
                ioScope.launch {
                    val json = viewModel.exportAll()
                    if (json.isBlank()) {
                        ToastUtil.showShort(context, exportEmptyText)
                    } else {
                        pendingExport = json
                        exportLauncher.launch(buildExportFileName(null))
                    }
                }
            },
            onSetDefault = { url -> viewModel.setDefaultSource(url) },
            onToggleEnabled = { url, enabled -> viewModel.setEnabled(url, enabled) },
            onExportSource = { item ->
                // null = ViewModel 按出身拒了（脚本行的导出随 Plan 2 补，它自己记了日志）：
                // 页面本就不给脚本行渲染这个入口，这里只是不让越界的调用去弹一个「最后写不出东西」的文件对话框
                val json = viewModel.exportSourceJson(item)
                if (json != null) {
                    pendingExport = json
                    exportLauncher.launch(buildExportFileName(item.rule.name))
                }
            },
            onDelete = { url -> viewModel.removeSource(url) },
            onConfirmImport = { items -> viewModel.confirmImport(items) },
            onDismissPreview = { viewModel.dismissPreview() },
        )
    }
}

/** 导出用的 MIME：SAF 据此决定默认扩展名与可写的文档类型 */
private const val MIME_JSON = "application/json"

/** 导入侧多带一个 text/plain：不少文件管理器把 .json 归为纯文本，只给 json 会选不到文件 */
private const val MIME_TEXT = "text/plain"

/**
 * 导出文件名：`book_sources_yyyyMMdd.json`（全部）/ `book_source_<书源名>_yyyyMMdd.json`（单条）。
 *
 * 书源名要过一遍清洗：它会成为用户可见的文件名，站点名里出现 `/`、`:` 或空白是常态
 * （「笔趣阁 / 首页」这种），未清洗时 SAF 会直接把创建请求拒掉。
 */
private fun buildExportFileName(sourceName: String?): String {
    val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    val cleaned = sourceName?.trim()
        ?.takeIf { it.isNotEmpty() }
        // 只保留各文种的字母、数字与 . _ -，其余（空格、斜杠、冒号、emoji）一律换成下划线
        ?.replace(Regex("""[^\p{L}\p{N}._-]+"""), "_")
        ?.take(24)
    return if (cleaned == null) "book_sources_$date.json" else "book_source_${cleaned}_$date.json"
}

/** 读 SAF 文本；流打不开或读失败返回 null（调用方提示「无法读取该文件」） */
private fun Uri.readJsonText(resolver: ContentResolver): String? = try {
    resolver.openInputStream(this)?.bufferedReader()?.use { it.readText() }
} catch (e: Exception) {
    null
}

/** 写 SAF 文本；流不可用或写失败返回 false */
private fun Uri.writeJsonText(resolver: ContentResolver, text: String): Boolean = try {
    resolver.openOutputStream(this)?.use { it.write(text.toByteArray()) } != null
} catch (e: Exception) {
    false
}

/**
 * 书源管理页内容：汇总行（含导入/导出动作）+ 清单 / 空态 + 三个弹层（行操作、导入预览、删除确认）。
 *
 * 纯状态 + 回调，不持有 ViewModel（对齐 [CacheManageScreen] / [SettingScreen] 的形态，便于预览与后续复用）。
 */
@Composable
fun BookSourceManageScreen(
    state: BookSourceViewModel.BookSourcePageState,
    onImportClick: () -> Unit,
    onExportAllClick: () -> Unit,
    onSetDefault: (String) -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onExportSource: (BookSourceItem) -> Unit,
    onDelete: (String) -> Unit,
    onConfirmImport: (List<BookSourceViewModel.ImportPreviewItem>) -> Unit,
    onDismissPreview: () -> Unit,
) {
    // 行操作层与删除确认是两个独立弹层：长按行→选动作→选删除→再确认，
    // 因此状态分开存（Sheet 关掉后才把「删哪条」交给确认框）。
    // 存 BookSourceItem 而不是 rule：操作层要按 format 决定导出入口的取舍（脚本行不给导出），
    // 只留 rule 就等于把刚拿到的元数据在弹层门口扔掉、退回「点了才知道导不出」。
    var actionTarget by remember { mutableStateOf<BookSourceItem?>(null) }
    var pendingDelete by remember { mutableStateOf<BookSourceItem?>(null) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // 汇总 + 两个动作：基类 Toolbar 没有 actions 插槽，这一行就是「右上角」
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.book_source_summary,
                        state.sources.size,
                        state.enabledCount
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onImportClick) {
                    Icon(
                        imageVector = Icons.Outlined.FolderOpen,
                        contentDescription = stringResource(R.string.book_source_import),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = onExportAllClick) {
                    Icon(
                        imageVector = Icons.Outlined.Save,
                        contentDescription = stringResource(R.string.book_source_export),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            if (state.sources.isEmpty()) {
                // 空态叠放层自带背景，只给尺寸即可（叠放层契约见 NoDataView 的 KDoc）
                NoDataView(
                    visible = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    title = stringResource(R.string.book_source_empty_title),
                    hint = stringResource(R.string.book_source_empty_hint),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(CommonUiTokens.listSpacing)
                ) {
                    // key 用 url：它是表主键（自然键），清单内必然唯一，禁用项也留在列表里
                    items(state.sources, key = { it.rule.url }) { item ->
                        BookSourceRow(
                            item = item,
                            isDefault = item.rule.url == state.defaultSourceUrl,
                            onToggleEnabled = { enabled -> onToggleEnabled(item.rule.url, enabled) },
                            onOpenActions = { actionTarget = item },
                        )
                    }
                }
            }
        }
    }

    actionTarget?.let { item ->
        BookSourceActionSheet(
            item = item,
            isDefault = item.rule.url == state.defaultSourceUrl,
            onDismiss = { actionTarget = null },
            onSetDefault = { onSetDefault(item.rule.url) },
            onExport = { onExportSource(item) },
            onDelete = { pendingDelete = item },
        )
    }

    state.preview?.let { items ->
        ImportPreviewSheet(
            items = items,
            onDismiss = onDismissPreview,
            onConfirm = {
                onConfirmImport(items)
                // 预览清单项由 ViewModel 在 confirmImport 收尾时一并清空（弹层随之关闭）
            },
        )
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.book_source_delete_title)) },
            text = { Text(stringResource(R.string.book_source_delete_confirm, item.rule.name)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(item.rule.url)
                }) {
                    Text(
                        stringResource(R.string.common_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/**
 * 单条书源行：名称 + URL 副标题 + 「默认」/「脚本」标记 + 启用开关。
 *
 * 不走 [CommonListItem]：它没有副标题槽位（标题只有一个 String），而本页每条源都要同时给出
 * 站点名与 URL 供人核对（导入多个同站源时只有 URL 能区分）。共享组件表达不了的形态一律走
 * 「壳复用、内容自绘」：**壳**仍复用 [CommonItemCard]（圆角、语义色、ripple、点击/长按面
 * 都在壳里），只有内容是本页形状。
 *
 * 两个标记各答一个问题：「默认」= 书城现在用哪个源，「脚本」= 这条是脚本书源格式
 * （[BookSourceItem.rule] 只是按实体列合成的展示用空壳，选择器一类字段一条都没有）。
 * 两者**可以同时命中**（一条脚本书源同样能当默认源），所以不是 when 二选一，而是各自独立的 if 并排。
 * 「脚本」这一位尤其不能省：脚本书源的行在清单里与原生源**长得几乎一样**（都有名字、地址、开关），
 * 差别却全在能力上（不参与书城与聚合搜索、暂时也不能导出），
 * 不标出来就等于让用户去猜——他会反复试那条看起来「坏了」的源。
 * 标色用 [InfoChip] 的中性默认色：出身是事实，不是状态，不该比「默认」那个更抢眼。
 */
@Composable
private fun BookSourceRow(
    item: BookSourceItem,
    isDefault: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onOpenActions: () -> Unit,
) {
    val rule = item.rule
    CommonItemCard(
        onClick = onOpenActions,
        onLongClick = onOpenActions,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.name.ifBlank { stringResource(R.string.book_source_unnamed) },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = rule.url.ifEmpty { stringResource(R.string.common_not_set) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isDefault || item.format == SourceFormat.SCRIPT) {
                Spacer(modifier = Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (item.format == SourceFormat.SCRIPT) {
                        InfoChip(text = stringResource(R.string.book_source_script_badge))
                    }
                    if (isDefault) {
                        InfoChip(
                            text = stringResource(R.string.book_source_default_badge),
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            // 开关单独吃自己的点击，不冒泡到行：拨开关不该顺手弹操作层
            Switch(checked = rule.enabled, onCheckedChange = onToggleEnabled)
        }
    }
}

/**
 * 行操作层：设为默认 / 导出该源 / 删除。
 *
 * 点击与长按都打开它（长按是这条链路的常规手势，但只有长按入口的话新用户找不到）。
 * 「设为默认」对当前默认源不出现——它是幂等的，摆在那里只会让人以为能再点出别的什么。
 *
 * 「导出该源」对**脚本书源整项不出现**：脚本行的导出物现在还做不出来——它的
 * [BookSourceItem.rule] 只是展示用空壳，导出去就是一份「看着成功、实则什么都解析不出来」的
 * 空规则文件；真正的脚本源导出（原样吐出 `rule_json`）随 Plan 2 的解释器一起补。
 * 不渲染只是表现层，事实上的守卫在 [BookSourceViewModel.exportSourceJson]（按 `format` 拒绝并记日志）。
 *
 * 「删除」对每一行都可用：`book_source` 表里每一行都是用户导入的，删除无保护。
 * 失败只可能是该行已不存在（多为另一处界面刚删过），由 [BookSourceViewModel.removeSource]
 * 翻成「已不在清单中」的提示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookSourceActionSheet(
    item: BookSourceItem,
    isDefault: Boolean,
    onDismiss: () -> Unit,
    onSetDefault: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val rule = item.rule
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                text = rule.name.ifBlank { stringResource(R.string.book_source_unnamed) },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 2.dp)
            )
            Text(
                text = rule.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
            )
            if (!isDefault) {
                CommonListItem(
                    icon = Icons.Outlined.StarOutline,
                    title = stringResource(R.string.book_source_set_default),
                    showArrow = false,
                    iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = {
                        onDismiss()
                        // 不必先拨启用开关：setDefaultSource 对禁用源会顺带启用（见其接口 KDoc）
                        onSetDefault()
                    }
                )
                CommonListDivider()
            }
            // 脚本书源不给导出入口（见上方 KDoc），连同一条分隔线一起省掉，否则两行之间会多空一档
            if (item.format == SourceFormat.NATIVE) {
                CommonListItem(
                    icon = Icons.Outlined.Save,
                    title = stringResource(R.string.book_source_export_one),
                    showArrow = false,
                    iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    iconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = {
                        onDismiss()
                        onExport()
                    }
                )
                CommonListDivider()
            }
            CommonListItem(
                icon = Icons.Outlined.DeleteOutline,
                title = stringResource(R.string.book_source_delete),
                showArrow = false,
                iconContainerColor = MaterialTheme.colorScheme.errorContainer,
                iconContentColor = MaterialTheme.colorScheme.onErrorContainer,
                onClick = {
                    onDismiss()
                    onDelete()
                }
            )
        }
    }
}

/**
 * 导入预览层：逐条列出解析结果与校验结论，用户确认后才落库。
 *
 * 「确认导入 N 条」的 N 只数校验通过的条目（不通过的条目导不了，摆在数字里会骗人）；
 * 全都不通过时按钮置灰，但清单仍完整展示——用户需要知道**为什么**导不进去。
 *
 * 两种出身的条目**共用这一套行样式**（图标 + 名称 + 地址 + 原因行），脚本项只多两处：
 * 名称旁的「脚本」出身标记，与原因行下方的若干警示行。刻意不做成两种长相的弹层：
 * 用户在同一包里看到的是一列同构的结果，只有「这一条多说了几句」的差异，
 * 而警示**不影响**该条是否计入可导入数（它仍然带着通过图标）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportPreviewSheet(
    items: List<BookSourceViewModel.ImportPreviewItem>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val validCount = items.count { it.isValid }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = stringResource(R.string.book_source_preview_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.book_source_preview_desc,
                    items.size,
                    items.size - validCount
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 高度上限 360dp：社区整包常见上百条，Sheet 铺满全屏就没法对照着滚
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                items.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    ImportPreviewRow(item)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onConfirm,
                enabled = validCount > 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.book_source_import_confirm, validCount))
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * 预览单行：状态图标 + 名称/URL + 「脚本」出身标记 + 校验原因 + 警示行 + 「将覆盖」标记。
 *
 * 名称与地址读 [BookSourceViewModel.ImportPreviewItem.displayName] / `displayUrl` 而不是某一种规则对象：
 * 两种出身的字段名不同（`name`/`url` 与 `bookSourceName`/`bookSourceUrl`），
 * 由数据类给一处派生值，本行就不必先判格式再决定读哪个字段（判漏一次就是渲染出一条空名字）。
 */
@Composable
private fun ImportPreviewRow(item: BookSourceViewModel.ImportPreviewItem) {
    val isValid = item.isValid
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = if (isValid) Icons.Outlined.CheckCircleOutline else Icons.Outlined.Error,
            contentDescription = stringResource(
                if (isValid) R.string.book_source_validation_pass else R.string.book_source_validation_fail
            ),
            tint = if (isValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.width(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.displayName.ifBlank { stringResource(R.string.book_source_unnamed) },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // 出身标记：让用户在落库前就知道这一条是哪种格式（能力差别要到用时才看得出来的事，
                // 必须在这里说清，否则他会在书城找不到它）
                if (item.format == SourceFormat.SCRIPT) {
                    Spacer(modifier = Modifier.width(8.dp))
                    InfoChip(text = stringResource(R.string.book_source_script_badge))
                }
                if (item.willOverwrite) {
                    Spacer(modifier = Modifier.width(8.dp))
                    InfoChip(
                        text = overwriteText(item),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            Text(
                text = item.displayUrl.ifEmpty { stringResource(R.string.common_not_set) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 失败原因逐条列全：一条源常同时缺好几个字段，只报第一个会逼用户改一次导一次
            (item.validation as? ValidationResult.Invalid)?.reasons?.let { reasons ->
                Text(
                    text = reasonText(reasons),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            // 警示行：与原因行同一个行样式，只换语义色（警示不是错误）。
            // 每条一行而不是 join 成一句：三项文案都是完整句子，挤在一起反而读不完
            item.scriptWarnings.forEach { warning ->
                Text(
                    text = stringResource(scriptWarningRes(warning)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

/**
 * 「将覆盖」标记的文案。
 *
 * 出身相同（原生覆盖原生、脚本覆盖脚本）就说「将覆盖」——那正是既有语义（同 URL 整行 REPLACE）。
 * 出身不同时补一句是**哪种出身被换掉**：同一条 URL 从规则源翻成脚本书源（或反过来）之后，
 * 它在书城/搜索里的参与方式会整片变掉，只说「将覆盖」会让人以为只是改了改规则。
 * 一句话就够，不为这件事另做组件。
 */
@Composable
private fun overwriteText(item: BookSourceViewModel.ImportPreviewItem): String =
    if (item.overwriteFormat == null || item.overwriteFormat == item.format) {
        stringResource(R.string.book_source_will_overwrite)
    } else {
        stringResource(
            if (item.overwriteFormat == SourceFormat.SCRIPT) {
                R.string.book_source_will_overwrite_script
            } else {
                R.string.book_source_will_overwrite_native
            }
        )
    }

/**
 * 校验原因文案：domain 侧只给枚举，中文全部来自字符串资源（切换语言时不残留硬编码文案）。
 *
 * 写成块体而不是 `= reasons.joinToString { stringResource(...) }`：单表达式函数里的内联 lambda
 * 不会被 Compose 插件标成 composable 上下文，那样写直接编译不过。
 */
@Composable
private fun reasonText(reasons: List<ValidationReason>): String {
    val texts = reasons.map { stringResource(validationReasonRes(it)) }
    return texts.joinToString(separator = "；")
}

/** 校验原因 → 文案资源（唯一事实，见 `BookSourceValidator` 的两条入口与四条原生判据） */
@StringRes
private fun validationReasonRes(reason: ValidationReason): Int = when (reason) {
    ValidationReason.NAME_BLANK -> R.string.book_source_reason_name_blank
    ValidationReason.URL_NOT_HTTP -> R.string.book_source_reason_url_not_http
    ValidationReason.NO_ENTRY -> R.string.book_source_reason_no_entry
    ValidationReason.NO_PARSE_RULE -> R.string.book_source_reason_no_parse_rule
    ValidationReason.SCRIPT_UNPARSABLE -> R.string.book_source_reason_script_unparsable
}

/**
 * 警示 → 文案资源。
 *
 * 与 [validationReasonRes] 分列两处而不是合成一张 `when`：原因是错误（条目不通过），
 * 警示是知情（条目照样通过），两者的颜色与计数口径都不同，映射也不该混在一张表里。
 */
@StringRes
private fun scriptWarningRes(warning: ScriptWarning): Int = when (warning) {
    ScriptWarning.HAS_EXECUTABLE_CODE -> R.string.book_source_warning_executable_code
    ScriptWarning.NEEDS_LOGIN -> R.string.book_source_warning_needs_login
    ScriptWarning.NOT_TEXT_SOURCE -> R.string.book_source_warning_not_text
}

/**
 * 一次性提示 → 文案。
 *
 * ViewModel 侧的 [BookSourceViewModel.Notice] 只带语义与数字，资源解析放在这里，
 * 于是「导入结果统计」这类带参文案的模板也留在 `strings.xml` 里可译。
 */
@Composable
private fun noticeMessage(notice: BookSourceViewModel.Notice): String = when (notice) {
    BookSourceViewModel.Notice.ImportUnreadable ->
        stringResource(R.string.book_source_import_unreadable)

    BookSourceViewModel.Notice.DeleteMissing ->
        stringResource(R.string.book_source_delete_missing)

    is BookSourceViewModel.Notice.Imported -> stringResource(
        R.string.book_source_import_result,
        notice.success,
        notice.overwritten,
        notice.failed,
    )
}
