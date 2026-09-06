package com.ebook.book

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ebook.book.mvvm.viewmodel.BookImportViewModel
import com.ebook.common.importer.ImportDuplicateState
import com.ebook.common.ui.CommonItemCard
import com.ebook.common.ui.CommonUiTokens
import com.permissionx.guolindev.PermissionX
import com.permissionx.guolindev.request.ExplainScope
import com.permissionx.guolindev.request.ForwardScope
import com.xrn1997.common.mvvm.compose.BaseMvvmActivity
import com.xrn1997.common.ui.LoadingView
import com.xrn1997.common.util.Logger
import com.xrn1997.common.util.ToastUtil.showShort
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import java.text.DecimalFormat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 本地书籍导入页（Compose 版，替代原 ViewBinding + RotateLoading + MoProgressHUD 实现）。
 *
 * 行为对齐原实现：
 * - 存储权限链：Android 11+ 跳过常规存储权限（scoped storage 下不可授予，申请只会死循环）
 *   → 全部文件访问弹窗 → ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION（带 package URI）
 *   直达本应用授权页，解析失败时回退列表页（见 [launchFilesAccessSettings]）
 * - 扫描中点击底栏可取消扫描；扫描结束为空时 Toast 提示
 * - 导入中的"放入书架中..."加载遮罩与"放入书架失败!"信息弹窗用 Compose 覆盖层
 *   实现（原 MoProgressHUD 能力）
 */
@AndroidEntryPoint
class ImportBookActivity : BaseMvvmActivity<BookImportViewModel>() {

    override val viewModel: BookImportViewModel by viewModels()

    // 页面级状态由 Activity 持有：生命周期与 Composable 解耦
    private var scanning by mutableStateOf(false)
    private var canCheck by mutableStateOf(false)
    private var showImportError by mutableStateOf(false)
    private var showFilesPermissionDialog by mutableStateOf(false)
    private var books by mutableStateOf<List<File>>(emptyList())

    /**
     * 全部文件访问权限设置页的回跳：仍未授权则退出导入页（对齐原实现）。
     *
     * 注意：不看 resultCode——设置页无论用户是否拨动开关，通常都以 RESULT_CANCELED 结束，
     * 旧实现以 `resultCode != RESULT_OK` 提前 return 导致授权状态复检永远不执行。
     */
    private val requestPermission: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                !Environment.isExternalStorageManager()
            ) {
                onBackPressedDispatcher.onBackPressed()
            }
        }

    /**
     * 所有需要的权限。
     *
     * Android 11（R）+ 不再申请 WRITE/READ_EXTERNAL_STORAGE：scoped storage 下它们
     * 不可授予（请求永远被拒），PermissionX 会陷入"解释→去设置"死循环并把用户
     * 领到找不到开关的应用详情页；扫描全盘文件改由全部文件访问（MANAGE_EXTERNAL_STORAGE）
     * 承担，见 [launchFilesAccessSettings]。
     */
    private fun allNeedPermissions(): List<String> {
        val permissions: MutableList<String> = ArrayList()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissions.add(PermissionX.permission.POST_NOTIFICATIONS)
        return permissions
    }

    override fun showBackButton(): Boolean = true

    override fun initData() {
        toolbarTitle.value = getString(com.ebook.common.R.string.local_file)
        initPermission()
        // LiveData 镜像为 Compose state（避免引入 runtime-livedata 依赖）
        viewModel.mImportBookList.observe(this) { books = it }
        // 扫描结束事件：停转圈；空结果提示并恢复扫描按钮，否则开放勾选
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.searchFinishEvent.collect {
                    scanning = false
                    if (books.isEmpty()) {
                        showShort(this@ImportBookActivity, getString(R.string.import_no_local_book))
                    } else {
                        canCheck = true
                    }
                }
            }
        }
        // 导入成功：Toast（对齐原 addSuccess；遮罩由 isImporting 驱动，无需手工撤）
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.addSuccessEvent.collect {
                    showShort(this@ImportBookActivity, getString(R.string.import_add_success))
                }
            }
        }
        // 导入失败：信息弹窗（对齐原 MoProgressHUD.showInfo）
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.addErrorEvent.collect {
                    showImportError = true
                }
            }
        }
    }

    private fun initPermission() {
        PermissionX
            .init(this)
            .permissions(allNeedPermissions())
            .onExplainRequestReason { scope: ExplainScope, deniedList: List<String> ->
                scope.showRequestReasonDialog(
                    deniedList,
                    getString(R.string.import_permission_reason),
                    getString(R.string.permission_understood),
                    getString(com.ebook.common.R.string.cancel)
                )
            }
            .onForwardToSettings { scope: ForwardScope, deniedList: List<String> ->
                scope.showForwardToSettingsDialog(
                    deniedList,
                    getString(R.string.import_permission_forward),
                    getString(R.string.permission_understood),
                    getString(com.ebook.common.R.string.cancel)
                )
            }
            .request { allGranted: Boolean, _: List<String?>?, _: List<String?>? ->
                if (!allGranted) {
                    onBackPressedDispatcher.onBackPressed()
                } else {
                    // Android 11+ 需额外的全部文件访问授权（弹 Compose 确认框）
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        !Environment.isExternalStorageManager()
                    ) {
                        showFilesPermissionDialog = true
                    }
                }
            }
    }

    @Composable
    override fun PageContent() {
        val progress by viewModel.importProgress.collectAsStateWithLifecycle()
        val duplicateState by viewModel.duplicateState.collectAsStateWithLifecycle()
        // 遮罩显隐由进程级协调器的批次状态驱动：重进页面也能看到仍在跑的批次
        val importing by viewModel.isImporting.collectAsStateWithLifecycle()
        // totalCount 在导入开始时锁定（onAddShelf 时 selected 的大小）
        var totalCount by remember { mutableStateOf(0) }

        ImportBookScreen(
            books = books,
            scanning = scanning,
            canCheck = canCheck,
            importing = importing,
            progress = progress,
            totalCount = totalCount,
            showImportError = showImportError,
            showFilesPermissionDialog = showFilesPermissionDialog,
            duplicateState = duplicateState,
            onStartScan = {
                viewModel.searchLocationBook()
                scanning = true
            },
            onCancelScan = { viewModel.scanCancel() },
            onAddShelf = { selected ->
                totalCount = selected.size
                viewModel.importBooks(selected)
            },
            onDismissImportError = { showImportError = false },
            onConfirmFilesPermission = {
                showFilesPermissionDialog = false
                // 弹窗只在 SDK_INT >= R 时展示，此处再守一道满足 @RequiresApi 契约
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    launchFilesAccessSettings()
                }
            },
            onCancelFilesPermission = {
                showFilesPermissionDialog = false
                finish()
            },
            onResolveKeepBoth = { viewModel.resolveKeepBoth() },
            onResolveOverwrite = { viewModel.resolveOverwrite() },
            onResolveMerge = { viewModel.resolveMerge() },
            onResolveCancel = { viewModel.resolveCancel() }
        )
    }

    /**
     * 跳转「所有文件访问权限」授权页。
     *
     * 首选 [Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION] + `package:` URI，
     * 直达**本应用**的授权开关页。不能用裸 [Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION]：
     * 它打开的是全量应用列表页，用户需自行在列表中找到本应用再点进一层，
     * 且 Android 17（API 37）的设置已不再为该 action 注册带 `package:` data 的过滤器，
     * 旧写法附加 URI 会直接解析失败（ActivityNotFoundException）。
     *
     * 个别 ROM 若未注册 per-app action，回退到列表页保证流程不断。
     *
     * 仅 Android 11+ 需要此权限，调用方已保证 API 级别（见 [initPermission] 中
     * `SDK_INT >= R` 分支），此处用 [RequiresApi] 将隐式约束提升为编译期契约。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun launchFilesAccessSettings() {
        val direct = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData("package:$packageName".toUri())
        try {
            requestPermission.launch(direct)
        } catch (e: ActivityNotFoundException) {
            Logger.w(TAG, "per-app all-files-access page unavailable, fallback to list page", e)
            requestPermission.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }
}

/**
 * 导入页内容（两段结构：文件列表 / 底栏）。
 *
 * 纯状态 + 回调（不持有 ViewModel），勾选集合由本页内部状态维护。
 * 顶栏由基类 [BaseMvvmActivity] Toolbar 提供（ADR-0006 共享设计语言）。
 */
@Composable
private fun ImportBookScreen(
    books: List<File>,
    scanning: Boolean,
    canCheck: Boolean,
    importing: Boolean,
    progress: Int,
    totalCount: Int,
    showImportError: Boolean,
    showFilesPermissionDialog: Boolean,
    duplicateState: ImportDuplicateState,
    onStartScan: () -> Unit,
    onCancelScan: () -> Unit,
    onAddShelf: (List<File>) -> Unit,
    onDismissImportError: () -> Unit,
    onConfirmFilesPermission: () -> Unit,
    onCancelFilesPermission: () -> Unit,
    onResolveKeepBoth: () -> Unit,
    onResolveOverwrite: () -> Unit,
    onResolveMerge: () -> Unit,
    onResolveCancel: () -> Unit
) {
    val selected = remember { mutableStateListOf<File>() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            // 文件列表：12dp 圆角条目卡，页面边距/条目间距走共享 tokens
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = CommonUiTokens.pagePadding,
                    end = CommonUiTokens.pagePadding,
                    top = CommonUiTokens.listSpacing,
                    bottom = CommonUiTokens.listSpacing
                ),
                verticalArrangement = Arrangement.spacedBy(CommonUiTokens.listSpacing)
            ) {
                items(books, key = { it.absolutePath }) { file ->
                    ImportBookItem(
                        file = file,
                        canCheck = canCheck,
                        checked = file in selected,
                        onToggle = { checked ->
                            if (checked) selected.add(file) else selected.remove(file)
                        }
                    )
                }
            }

            // 底栏：左侧「加入书架」（有选中项时）、右侧扫描/停止扫描
            BottomBar(
                scanning = scanning,
                books = books,
                selected = selected,
                onStartScan = onStartScan,
                onCancelScan = onCancelScan,
                onAddShelf = onAddShelf
            )
        }

        // 导入中遮罩：复用 LoadingView 卡片样式，文案随进度更新
        // 同名检测弹窗期间隐藏遮罩，避免盖住对话框
        val isDuplicateDialog = duplicateState is ImportDuplicateState.Detected
        LoadingView(
            visible = importing && !isDuplicateDialog,
            modifier = Modifier.fillMaxSize(),
            txt = if (progress > 0)
                stringResource(R.string.importing_progress, progress, totalCount)
            else
                stringResource(R.string.import_adding),
        )

        // 导入失败信息弹窗（替代 MoProgressHUD.showInfo("放入书架失败!")）
        if (showImportError) {
            AlertDialog(
                onDismissRequest = onDismissImportError,
                text = { Text(stringResource(R.string.import_add_failed)) },
                confirmButton = {
                    TextButton(onClick = onDismissImportError) {
                        Text(stringResource(com.ebook.common.R.string.cancel))
                    }
                }
            )
        }

        // Android 11+ 全部文件访问授权弹窗（原 AlertDialog，语义一致：取消即退出页面）
        if (showFilesPermissionDialog) {
            AlertDialog(
                onDismissRequest = { },
                text = { Text(stringResource(R.string.import_android11_files_permission)) },
                confirmButton = {
                    TextButton(onClick = onConfirmFilesPermission) {
                        Text(stringResource(R.string.confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onCancelFilesPermission) {
                        Text(stringResource(com.ebook.common.R.string.cancel))
                    }
                }
            )
        }

        // 导入判重处置框
        (duplicateState as? ImportDuplicateState.Detected)?.let { detected ->
            DuplicateDispositionDialog(
                detected = detected,
                onKeepBoth = onResolveKeepBoth,
                onMerge = onResolveMerge,
                onOverwrite = onResolveOverwrite,
                onCancel = onResolveCancel,
            )
        }
    }
}

/**
 * 判重处置框（spec §6 重复检测的「模糊」一级，处置语义见 ADR-0023）。
 *
 * 几个刻意的地方：
 * - **列全部命中项**：同一 `comment_key` 下可能已挂着多个条目，而「覆盖」会删掉全部命中项，
 *   只展示第一条等于让用户在看不见后果的情况下按按钮。
 * - **「继续添加」占主按钮位**：共存不是需要被打扰的缺陷——同键条目读评论取并集，本来就是
 *   §9 这套模型支持的正常形态；破坏性与改数据的动作全部降到次要位。
 * - **「智能合并」只在有本地命中时出现**：补章的载体是本机章文件，网络书的正文在书源那边。
 * - 点框外不关闭：四个动作语义差别太大，误触的代价不对等。
 */
@Composable
private fun DuplicateDispositionDialog(
    detected: ImportDuplicateState.Detected,
    onKeepBoth: () -> Unit,
    onMerge: () -> Unit,
    onOverwrite: () -> Unit,
    onCancel: () -> Unit,
) {
    val canMerge = detected.matches.any { it.isLocal }
    AlertDialog(
        onDismissRequest = { },
        title = { Text(stringResource(R.string.import_duplicate_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.import_duplicate_summary, detected.meta.title),
                    style = MaterialTheme.typography.bodyMedium,
                )
                detected.matches.forEach { match ->
                    Text(
                        text = stringResource(
                            R.string.import_duplicate_match_item,
                            match.title,
                            match.author,
                            stringResource(
                                if (match.isLocal) R.string.import_duplicate_source_local
                                else R.string.import_duplicate_source_network
                            ),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(
                        if (canMerge) R.string.import_duplicate_merge_desc
                        else R.string.import_duplicate_no_merge_desc
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.import_duplicate_overwrite_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            Button(onClick = onKeepBoth) {
                Text(stringResource(R.string.import_duplicate_keep_both))
            }
        },
        dismissButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (canMerge) {
                    TextButton(onClick = onMerge) {
                        Text(stringResource(R.string.import_duplicate_merge))
                    }
                }
                TextButton(onClick = onOverwrite) {
                    Text(
                        text = stringResource(R.string.import_duplicate_overwrite),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(com.ebook.common.R.string.cancel))
                }
            }
        }
    )
}

/**
 * 导入页底栏：左侧「加入书架」（有选中项时）、右侧扫描/停止扫描/扫描结果计数。
 *
 * 无选中项时扫描区占满全宽居中；有选中项时两区并排各占一半。
 */
@Composable
private fun BottomBar(
    scanning: Boolean,
    books: List<File>,
    selected: List<File>,
    onStartScan: () -> Unit,
    onCancelScan: () -> Unit,
    onAddShelf: (List<File>) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = CommonUiTokens.pagePadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧「加入书架」：仅选中项时显示
            if (selected.isNotEmpty()) {
                Button(
                    onClick = { onAddShelf(selected) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(com.ebook.common.R.string.add_book))
                }
            }

            // 右侧扫描区（无选中项时占满全宽居中）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        enabled = scanning || books.isNotEmpty() || selected.isNotEmpty(),
                        onClick = { if (scanning) onCancelScan() else onStartScan() }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (scanning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(com.ebook.api.R.string.scan_cancel),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                } else if (books.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            com.ebook.common.R.string.tv_importbook_count,
                            books.size
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge
                    )
                } else {
                    Text(
                        text = stringResource(com.ebook.common.R.string.scan),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

/**
 * 导入文件条目（ADR-0006 共享设计语言重设计，替代原 view_adapter_importbook.xml）：
 * 12dp 圆角条目卡（surfaceContainer 语义底）+ Material 矢量文件图标 + 勾选框，
 * 字号全部走 Material typography，条目间距由列表 spacedBy 承担（不再手绘分割线）。
 */
@Composable
private fun ImportBookItem(
    file: File,
    canCheck: Boolean,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    CommonItemCard(
        onClick = { onToggle(!checked) },
        enabled = canCheck,
        shadowElevation = 0.dp,
        // 书源条目密度高，比默认 12dp 更紧
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Description,
                contentDescription = null,
                modifier = Modifier
                    .padding(horizontal = 7.dp)
                    .size(36.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = file.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = convertByte(file.length()),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    text = file.absolutePath.replace(
                        Environment.getExternalStorageDirectory().absolutePath,
                        stringResource(R.string.storage_space)
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (canCheck) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = onToggle,
                    modifier = Modifier.padding(start = 10.dp, end = 7.dp)
                )
            } else {
                Spacer(modifier = Modifier.width(34.dp))
            }
        }
    }
}

/** 文件大小格式化（自原 ImportBookAdapter.convertByte 迁移，逻辑不变）。 */
private val BYTE_FORMAT = DecimalFormat("###.#")

private fun convertByte(size: Long): String {
    return when {
        size < 1024 -> BYTE_FORMAT.format(size.toDouble()) + "B"
        size < 1024 * 1024 -> BYTE_FORMAT.format(size / 1024.0) + "KB"
        size < 1024L * 1024 * 1024 -> BYTE_FORMAT.format(size / (1024.0 * 1024)) + "MB"
        else -> BYTE_FORMAT.format(size / (1024.0 * 1024 * 1024)) + "GB"
    }
}
