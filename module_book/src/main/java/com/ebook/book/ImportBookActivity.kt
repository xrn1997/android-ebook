package com.ebook.book

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ebook.book.mvvm.viewmodel.BookImportViewModel
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

/**
 * 本地书籍导入页（Compose 版，替代原 ViewBinding + RotateLoading + MoProgressHUD 实现）。
 *
 * 行为对齐原实现：
 * - 入场/退场为整页水平滑入滑出；[finish] 先触发退场动画，动画结束后才真正结束
 *   Activity（`isExiting` 防重入，语义同原版）
 * - 存储权限链：Android 11+ 跳过常规存储权限（scoped storage 下不可授予，申请只会死循环）
 *   → 全部文件访问弹窗 → ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION（带 package URI）
 *   直达本应用授权页，解析失败时回退列表页（见 [launchFilesAccessSettings]）
 * - 扫描中点击底栏可取消扫描；扫描结束为空时 Toast 提示
 * - 导入中的"放入书架中..."加载遮罩与"放入书架失败!"信息弹窗用 Compose 覆盖层
 *   实现（原 MoProgressHUD 能力）
 */
@AndroidEntryPoint
class ImportBookActivity : BaseMvvmActivity<BookImportViewModel>() {
    private companion object {
        const val TAG = "ImportBookActivity"
    }

    protected override val viewModel: BookImportViewModel by viewModels()

    // 页面级状态由 Activity 持有：生命周期与 Composable 解耦，
    // 退场动画开关必须在 finish()（非组合期）可写
    private var exiting by mutableStateOf(false)
    private var scanning by mutableStateOf(false)
    private var canCheck by mutableStateOf(false)
    private var importing by mutableStateOf(false)
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

    override fun enableToolbar(): Boolean = false

    override fun initData() {
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
        // 导入成功：撤遮罩 + Toast（对齐原 addSuccess）
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.addSuccessEvent.collect {
                    importing = false
                    showShort(this@ImportBookActivity, getString(R.string.import_add_success))
                }
            }
        }
        // 导入失败：撤遮罩 + 信息弹窗（对齐原 MoProgressHUD.showInfo）
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.addErrorEvent.collect {
                    importing = false
                    showImportError = true
                }
            }
        }
    }

    /**
     * 覆写 finish：先触发退场动画，动画结束后经 [finishImmediately] 真正结束。
     * `exiting` 即原实现的 isExiting 防重入标志。
     */
    override fun finish() {
        if (!exiting) {
            exiting = true
        } else {
            super.finish()
        }
    }

    /** 退场动画结束后的真实收尾（对齐原 animOut.onAnimationEnd） */
    private fun finishImmediately() {
        super.finish()
        @Suppress("DEPRECATION") // 对齐原实现：无过渡动画收尾
        overridePendingTransition(0, 0)
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
        // 整页滑入滑出：visibleState 由 exiting 驱动，动画静止（Idle）且已退出时真正结束
        val visibleState = remember { MutableTransitionState(true) }
        LaunchedEffect(exiting) {
            visibleState.targetState = !exiting
        }
        AnimatedVisibility(
            visibleState = visibleState,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut()
        ) {
            ImportBookScreen(
                books = books,
                scanning = scanning,
                canCheck = canCheck,
                importing = importing,
                showImportError = showImportError,
                showFilesPermissionDialog = showFilesPermissionDialog,
                onStartScan = {
                    viewModel.searchLocationBook()
                    scanning = true
                },
                onCancelScan = { viewModel.scanCancel() },
                onAddShelf = { selected ->
                    importing = true
                    viewModel.importBooks(selected)
                },
                onDismissImportError = { showImportError = false },
                onConfirmFilesPermission = {
                    showFilesPermissionDialog = false
                    launchFilesAccessSettings()
                },
                onCancelFilesPermission = {
                    showFilesPermissionDialog = false
                    finish()
                },
                onBack = {
                    viewModel.scanCancel()
                    onBackPressedDispatcher.onBackPressed()
                }
            )
        }
        // 退场动画播完（状态静止于已退出）→ 真正结束 Activity
        LaunchedEffect(visibleState.currentState, visibleState.targetState, exiting) {
            if (exiting &&
                !visibleState.targetState &&
                visibleState.currentState == visibleState.targetState
            ) {
                finishImmediately()
            }
        }
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
     */
    private fun launchFilesAccessSettings() {
        val direct = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.parse("package:$packageName"))
        try {
            requestPermission.launch(direct)
        } catch (e: ActivityNotFoundException) {
            Logger.w(TAG, "per-app all-files-access page unavailable, fallback to list page", e)
            requestPermission.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }
}

/**
 * 导入页内容（对齐原 activity_importbook.xml 三段结构：顶栏 / 列表 / 底栏）。
 *
 * 纯状态 + 回调（不持有 ViewModel），勾选集合由本页内部状态维护。
 * 顶栏使用 Material3 [TopAppBar]（对齐书架页形态，ADR-0006 共享设计语言）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportBookScreen(
    books: List<File>,
    scanning: Boolean,
    canCheck: Boolean,
    importing: Boolean,
    showImportError: Boolean,
    showFilesPermissionDialog: Boolean,
    onStartScan: () -> Unit,
    onCancelScan: () -> Unit,
    onAddShelf: (List<File>) -> Unit,
    onDismissImportError: () -> Unit,
    onConfirmFilesPermission: () -> Unit,
    onCancelFilesPermission: () -> Unit,
    onBack: () -> Unit
) {
    val selected = remember { mutableStateListOf<File>() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            // 顶栏（对齐书架页：TopAppBar 文字标题 + 返回 + 加入书架 action，ADR-0006）
            TopAppBar(
                title = { Text(stringResource(com.ebook.common.R.string.local_file)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(com.ebook.common.R.string.back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    if (selected.isNotEmpty()) {
                        TextButton(onClick = { onAddShelf(selected.toList()) }) {
                            Text(
                                text = stringResource(com.ebook.common.R.string.add_book),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )

            // 文件列表：12dp 圆角条目卡，页面边距/条目间距走共享 tokens
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
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
            }

            // 底栏（48dp）：扫描中显示转圈+停止扫描，否则显示智能扫描按钮
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(
                        if (scanning || books.isNotEmpty()) {
                            // 扫描中/扫描完成（计数态）用 primary 底（M3 FilledButton 语义）
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                    .clickable {
                        if (scanning) onCancelScan() else onStartScan()
                    },
                contentAlignment = Alignment.Center
            ) {
                if (scanning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(com.ebook.api.R.string.scan_cancel),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                } else if (books.isNotEmpty()) {
                    // 扫描完成：展示结果计数（对齐原 searchFinish 的 tvCount 文案）
                    Text(
                        text = stringResource(com.ebook.common.R.string.tv_importbook_count, books.size),
                        color = MaterialTheme.colorScheme.onPrimary,
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

        // 导入中遮罩：共享 LoadingView（透明遮罩 + 居中卡片 + 吞触摸，语义对齐原 MoProgressHUD.showLoading）
        LoadingView(
            visible = importing,
            modifier = Modifier.fillMaxSize(),
            txt = stringResource(R.string.import_adding),
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
    Surface(
        // clickable 挂在 Surface 上：ripple 按圆角裁剪，点击命中区即整卡
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canCheck) { onToggle(!checked) },
        shape = RoundedCornerShape(CommonUiTokens.cardCornerSmall),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
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
