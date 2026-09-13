# 下载中心重构实施计划

> **状态说明（2026-09-12 实施期修订，以代码与 ADR-0035/0034 修订说明为准）**：Task 2/3 的「全高 BottomSheet 承载」与 Task 4 的「单主 FAB + 列表尾部取消全部」装机后不符合用户预期，已被撤销回退——二级恢复整屏页（`DownloadCenterStep` 导航 + `bookSheet` 装载结果），一级页不设集中控制按钮、删除「取消全部」；二级选章列表改平铺整列（弃「百章分组折叠」，相关分组纯逻辑与用例移除）。各 Task 正文保留原计划作为历史记录，落地形态见 `docs/adr/0035-download-center.md` 与 `docs/adr/0034-long-catalog-browse-and-select.md` 修订说明。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 重构下载中心：一级页改轻书行列表 + 右下单主 FAB（播放/暂停），二级选章改全高 BottomSheet，组件全面收编 ADR-0006 体系；同时将下载启动改为「空载 Intent + `download_chapter` 表为唯一队列事实源」，根除 `TransactionTooLargeException` 风险。

**Architecture:** 页面侧（DownloadManageActivity / DownloadManageViewModel）把「两级页面切换」改成「一级列表 + 可选的二级 sheet（`bookSheet: StateFlow<BookSelectionState?>`）」，删除 `DownloadCenterStep` 与自绘 BackHandler；`ChapterDownloadSheet.kt` 的选章内容复用进 `ModalBottomSheet`；服务侧启动 Intent 不再携带章节列表，统一读库取篇。

**Tech Stack:** Kotlin + Compose（Material3 语义色）+ ModalBottomSheet + FloatingActionButton + material-icons-extended + Hilt + Room（download_chapter）。

**设计依据:** `docs/superpowers/specs/2026-09-12-download-center-redesign-design.md`（决策 D1–D7）。

---

## 目录

| Task | 内容 | 涉及文件 |
|---|---|---|
| 1 | 下载启动信号化（基础设施） | DownloadRepository.kt、DownloadService.kt |
| 2 | VM 状态机改造（bookSheet）+ Activity 切 sheet 承载 | DownloadManageViewModel.kt、DownloadManageActivity.kt |
| 3 | 二级选章 sheet 内容改造（书头 + 取消本书 + 胶囊化） | ChapterDownloadSheet.kt |
| 4 | 一级页视觉重做（书行卡 + FAB + 尾部取消全部） | DownloadManageActivity.kt、build.gradle.kts |
| 5 | 文案清理 + 全量编译与单测 | strings.xml |
| 6 | ADR-0035 就地修订 | docs/adr/0035-download-center.md |
| 7 | 装机验证清单交付 | （无代码） |

---

## Task 1: 下载启动信号化

**Files:**
- Modify: `module_book/src/main/java/com/ebook/book/repository/DownloadRepository.kt`（`startDownload`，约 206-212 行）
- Modify: `module_book/src/main/java/com/ebook/book/service/DownloadService.kt`（KDoc 41-50、onStartCommand 170-208、addNewTask 215-227、companion 698-699 / 738-757）

- [ ] **Step 1: 改造 `DownloadRepository.startDownload`，启动 Intent 空载**

把 `DownloadRepository.kt` 的 `startDownload` 整体替换为：

```kotlin
    /**
     * 批量下发下载任务：**先入库、再拉起前台服务**（顺序即「发起方先入库再拉服务」——
     * 服务启动被拒时任务已落库不丢；[addTasks] 按章 URL 去重，重入幂等）。
     *
     * 启动 Intent 只作**信号**（[DownloadService.buildStartIntent] 空载）：`download_chapter` 表
     * 是唯一队列事实源（ADR-0035），章节列表不再随 Intent 走——整本大额下载不再接近 Binder
     * 事务 1MB 上限（TransactionTooLargeException 风险根除），冷启动/重启/续跑统一由服务读库取篇。
     *
     * 原 `BookReadViewModel.startDownload` 的实现迁移至此，作为全仓唯一下发入口；
     * 启动被拒（dataSync 配额用尽等）时页内提示，不抛未捕获异常。
     */
    suspend fun startDownload(chapters: List<DownloadChapterEntity>) {
        if (chapters.isEmpty()) return
        addTasks(chapters)
        if (!DownloadService.start(context, DownloadService.buildStartIntent(context))) {
            ToastUtil.showShort(context, context.getString(R.string.download_start_restricted))
        }
    }
```

（仅 `buildStartIntent(context, chapters)` → `buildStartIntent(context)` 一处参数变化，其余原样保留。）

- [ ] **Step 2: 精简 `DownloadService`——删章节载荷**

(a) 类 KDoc（约 43-44 行）：

```kotlin
 * 对外契约全部是 Intent（无 binder，[onBind] 返回 null）：
 * - 信号启动：[buildStartIntent]（空载；任务经 [DownloadRepository.startDownload] 先入 `download_chapter`
 *   库、本服务读库取篇，携带整份章节列表的旧链路已删——见 buildStartIntent 的 KDoc）
```

(b) `onStartCommand`（约 170-178 行）——删除章节提取分支，替换为：

```kotlin
        // 启动 Intent 是空载信号（不再携带章节列表，见 DownloadRepository.startDownload 与 buildStartIntent）：
        // 任务先入库、服务从库取篇——冷启动/START_STICKY 重启/通知续跑统一走这条读库续跑分支。
        if (!isStartDownload && !isDownloading) {
            // 无任务则收尾退出，避免前台服务空转；弹窗/通知上的"继续"按钮另走上方 ACTION_RESUME 分支
```

（原 `else if` 前的 `val chapters = intent?.let { extractChapters(it) }.orEmpty()` 与 `if (chapters.isNotEmpty()) { addNewTask(chapters) }` 两行删除。）

(c) 删除 `addNewTask` 整个函数（约 215-227 行）。

(d) companion（约 698-699 行）删除：

```kotlin
        /** Intent extra 键：随启动/重启动作携带的待下载章节列表（Parcelable） */
        private const val EXTRA_CHAPTERS = "extra_download_chapters"
```

(e) `buildStartIntent`（约 738-746 行）替换为：

```kotlin
        /**
         * 构造启动 Intent（空载信号）。
         *
         * 调用方用 [start] 启动（内含启动被拒的兜底，勿直接调 startForegroundService）。
         * 任务已在 `download_chapter` 表入库，本 Intent 不携带章节列表；服务 onStartCommand 读库续跑，
         * 避免整本大额下载把章节实体塞进 Binder 事务（TransactionTooLargeException 风险）。
         */
        fun buildStartIntent(context: Context): Intent =
            Intent(context, DownloadService::class.java)
```

(f) 删除 `extractChapters` 整个函数（约 749-757 行，含 API 33 分支）。

- [ ] **Step 3: 编译验证 + 确认无残留引用**

```powershell
# 全仓确认旧签名/常量已无引用
grep -rn "buildStartIntent(context, " module_book/src --include=*.kt   # 期望：无输出（唯一调用点是 startDownload）
grep -rn "EXTRA_CHAPTERS\|extractChapters\|addNewTask" module_book/src --include=*.kt   # 期望：无输出
```

```powershell
.\gradlew :module_book:assembleDebug --console=plain
```

期望：BUILD SUCCESSFUL。

- [ ] **Step 4: Commit**

```bash
git add module_book/src/main/java/com/ebook/book/repository/DownloadRepository.kt module_book/src/main/java/com/ebook/book/service/DownloadService.kt
git commit -m "refactor(module_book): 下载启动改空载 Intent 信号，队列以库为唯一事实源

章节列表不再随启动 Intent 传递（根除大额整本下载的
TransactionTooLargeException 风险），冷启动/重启/续跑统一读库。
```

---

## Task 2: VM 状态机改造 + Activity 切 sheet 承载

**Files:**
- Modify: `module_book/src/main/java/com/ebook/book/mvvm/viewmodel/DownloadManageViewModel.kt`
- Modify: `module_book/src/main/java/com/ebook/book/DownloadManageActivity.kt`

- [ ] **Step 1: VM——删 `DownloadCenterStep`，加 `bookSheet`**

(a) 删除 `DownloadCenterStep` sealed interface（当前位于 `BookSelectionState` 下方）：

```kotlin
/** 下载中心页面级步骤：一级按书列表 / 二级某书选章。 */
sealed interface DownloadCenterStep {
    data object Books : DownloadCenterStep
    data class PickBook(val noteUrl: String, val tag: String) : DownloadCenterStep
}
```

(b) 删除字段 `_step` / `step`（约 127-129 行），替换为：

```kotlin
    /** 二级选章 sheet：null = 收起（一级列表态）；非空 = 该书装载结果（Loading/Absent/Failed/Ready）。 */
    private val _bookSheet = MutableStateFlow<BookSelectionState?>(null)
    val bookSheet: StateFlow<BookSelectionState?> = _bookSheet.asStateFlow()
```

(c) 新增两个装载目标字段（紧跟 `pendingFocusChapter` 声明处）：

```kotlin
    /** 当前展开 sheet 的书；openBook 时写入，供 loadSelection/refreshSelection 使用。 */
    private var activeNoteUrl: String = ""
    private var activeTag: String = ""
```

(d) `openBook` / `refreshSelection` / `loadSelection` / `backToBooks` 整体替换为：

```kotlin
    /**
     * 展开某书二级选章 sheet 并装载数据。
     *
     * [focusChapter] 为阅读器传入的当前章；书不在架时落 [BookSelectionState.Absent]（sheet 内空态）。
     * 先落 [BookSelectionState.Loading]：换书（或上次装载失败）时避免把上一本书的
     * [BookChapterSelection] 画在下一本书的 sheet 上。
     */
    fun openBook(noteUrl: String, tag: String, focusChapter: Int = -1) {
        activeNoteUrl = noteUrl
        activeTag = tag
        pendingFocusChapter = focusChapter
        _bookSheet.value = BookSelectionState.Loading
        loadSelection()
    }

    /** 重新装载当前书的二级数据（下载进行中每章推进后刷新状态标签用）。 */
    fun refreshSelection() {
        if (_bookSheet.value != null) loadSelection()
    }

    /**
     * 装载二级数据：书架信息 + 章目录 + 缓存 + 队列三方合并。
     *
     * 失败（Room / 章文件 IO 异常）落 [BookSelectionState.Failed] 并经 [reportFailure] 提示，
     * 不会让页面无限停在「正在加载…」（同模块范式：EditBookMetaViewModel.loadState）。
     */
    private fun loadSelection() {
        val noteUrl = activeNoteUrl
        val tag = activeTag
        if (noteUrl.isEmpty()) return
        viewModelScope.launch {
            try {
                val full = model.getBookFullInfo(noteUrl)
                if (full == null) {
                    // 书架无此书：二级无内容可展示。阅读器入口先确保在架不会到这；
                    // 一级入口的队列书若已被移出书架（任务残留）才会走进这个分支。
                    _bookSheet.value = BookSelectionState.Absent
                    return@launch
                }
                val chapters = full.chapters
                val cached = model.getCachedIndices(noteUrl, tag, chapters)
                val tasks = model.getTasksByBook(noteUrl)
                val queued = tasks.mapTo(mutableSetOf()) { it.durChapterIndex }
                // 当前下载章只在 Progress 期间断言：暂停/完成时 isDownloading=false，
                // 即便该章仍在队列里，二级「下载中」徽章也随之收起（与一级卡片同口径）
                val active = if (isDownloading) {
                    tasks.firstOrNull { it.durChapterUrl == activeChapterUrl }?.durChapterIndex
                } else null
                val initialSelected = buildInitialSelection(chapters, cached, queued)
                _bookSheet.value = BookSelectionState.Ready(
                    BookChapterSelection(
                        noteUrl = noteUrl,
                        tag = tag,
                        bookName = full.info?.name ?: "",
                        coverUrl = full.info?.coverUrl ?: "",
                        chapters = chapters,
                        cachedIndices = cached,
                        queuedIndices = queued,
                        activeChapterIndex = active,
                        initialSelected = initialSelected,
                        focusChapter = pendingFocusChapter,
                    )
                )
            } catch (e: Exception) {
                Logger.e(TAG, "loadSelection 失败", e)
                _bookSheet.value = BookSelectionState.Failed
                reportFailure(e, context.getString(R.string.download_center_load_failed))
            }
        }
    }

    /** 收起二级选章 sheet（[androidx.compose.material3.ModalBottomSheet] 的 onDismiss 触发）。 */
    fun closeBookSheet() {
        _bookSheet.value = null
    }
```

（`buildInitialSelection` / `pendingFocusChapter` / `confirmDownload` / `cancelBook` / `onDownloadState` / `sendAction` / `resumeIfPending` / 其余字段全部不动。）

- [ ] **Step 2: Activity——改 sheet 承载、删 BackHandler 分支**

把 `DownloadCenterScreen` 顶部的步骤逻辑替换：

删除：

```kotlin
    BackHandler(enabled = step is DownloadCenterStep.PickBook) {
        viewModel.backToBooks()
    }
```

删除：

```kotlin
    val step by viewModel.step.collectAsState()
```

新增：

```kotlin
    val step by viewModel.step.collectAsState()
```

改为 `bookSheet`：

```kotlin
    val bookSheet by viewModel.bookSheet.collectAsState()
```

（上两条可合并为一次编辑；此处步骤经 `val bookSheet by ...` 读取。）

把 `when (val current = step)` 整块（现为 Books / PickBook 两分支）替换为：

```kotlin
    DownloadManageScreen(
        viewModel = viewModel,
        onOpenBook = { group -> viewModel.openBook(group.noteUrl, group.tag) }
    )

    // 二级选章 sheet：bookSheet != null 时展开（含 Loading/Absent/Failed/Ready 四态），
    // ModalBottomSheet 自带返回收起语义，不再用 BackHandler 干预。
    if (bookSheet != null) {
        BookChapterSelectSheet(
            state = bookSheet,
            onDismiss = viewModel::closeBookSheet,
            onConfirm = { selected ->
                activity.requestDownloadPermission { viewModel.confirmDownload(selected) }
            },
            onCancelBook = { selection -> viewModel.cancelBook(selection.noteUrl) },
        )
    }
```

同时：
- `DownloadManageScreen` 的 `onOpenBook` 参数类型仍为 `(DownloadBookGroup) -> Unit`（Task 4 会调整其内部渲染，本 Task 不动）。
- `pickParams` 消费的 `LaunchedEffect` 不变（仍调 `viewModel.openBook(params.noteUrl, params.tag, params.focusChapter)`）。
- `import com.ebook.book.reader.BookChapterSelectSheet` 新增；`import com.ebook.db.entity.DownloadChapterEntity` 视需要。

- [ ] **Step 3: 编译验证**

```powershell
.\gradlew :module_book:compileDebugKotlin --console=plain
```

若报 `BookChapterSelectSheet` 未定义，属预期——Task 3 才引入该 composable；先临时将其指向现有 `BookChapterSelectPage` 的薄壳（见 Task 3 Step 0 说明）或直接跳到 Task 3 完成后再编译。推荐顺序：本 Task 与 Task 3 在**同一个提交内**完成以保持编译绿（见 Task 3 末尾合并提交说明）。

- [ ] **Step 4: Commit（与 Task 3 完成后合并提交）**

```bash
git add module_book/src/main/java/com/ebook/book/mvvm/viewmodel/DownloadManageViewModel.kt module_book/src/main/java/com/ebook/book/DownloadManageActivity.kt module_book/src/main/java/com/ebook/book/reader/ChapterDownloadSheet.kt
git commit -m "refactor(module_book): 二级选章从整屏页改全高 BottomSheet 承载

VM 去掉 DownloadCenterStep，改 bookSheet 状态流；Activity 用
ModalBottomSheet 挂载选章内容，删除自绘 BackHandler 分支。
"
```

---

## Task 3: 二级选章 sheet 内容改造

**Files:**
- Modify: `module_book/src/main/java/com/ebook/book/reader/ChapterDownloadSheet.kt`

- [ ] **Step 1: 新增 `BookChapterSelectSheet`（ModalBottomSheet 壳）+ 书头配方**

把现有 `BookChapterSelectPage` 改造为 sheet 内容，并新增外部壳：

```kotlin
/**
 * 二级选章：全高 BottomSheet（设计 D3）。
 *
 * 壳层负责 [ModalBottomSheet] 与四态渲染（加载中/书不在架/失败/就绪）；
 * 内容复用原选章页的章节分组/三态/软上限纯逻辑（ChapterSelection.kt 零改动）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookChapterSelectSheet(
    state: BookSelectionState,
    onDismiss: () -> Unit,
    onConfirm: (Set<Int>) -> Unit,
    onCancelBook: (BookChapterSelection) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        when (state) {
            is BookSelectionState.Loading -> SheetCenteredHint(stringResource(R.string.download_center_loading))
            is BookSelectionState.Absent -> SheetCenteredHint(stringResource(R.string.download_center_not_on_shelf))
            is BookSelectionState.Failed -> SheetCenteredHint(stringResource(R.string.download_center_load_failed))
            is BookSelectionState.Ready -> BookChapterSelectContent(
                selection = state.selection,
                onCancelBook = { onCancelBook(state.selection) },
                onConfirm = onConfirm,
            )
        }
    }
}
```

新增 import：

```kotlin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import com.ebook.book.mvvm.viewmodel.BookSelectionState
```

- [ ] **Step 2: 书头改造——撤返回箭头，加封面 + 状态 + 取消本书**

把 `BookChapterSelectPage` 更名为 `BookChapterSelectContent`，签名去掉 `onBack`、加 `onCancelBook`：

```kotlin
@Composable
private fun BookChapterSelectContent(
    selection: BookChapterSelection,
    onCancelBook: () -> Unit,
    onConfirm: (Set<Int>) -> Unit,
)
```

原页头 Row（返回箭头 + 书名 + 已选计数）替换为书头 Row：

```kotlin
        // 书头：封面 + 书名 + 状态徽章 + 「取消本书下载」（书维度操作归书上下文）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CommonUiTokens.pagePadding, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BookCover(
                url = selection.coverUrl,
                modifier = Modifier.size(width = 40.dp, height = 54.dp),
                contentDescription = selection.bookName
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = selection.bookName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                selection.activeChapterIndex?.let {
                    Text(
                        text = stringResource(R.string.download_manage_active_tag),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            TextButton(onClick = onCancelBook) {
                Text(
                    stringResource(R.string.download_manage_cancel_book),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
```

新 import：`com.ebook.common.ui.BookCover`、`androidx.compose.material3.TextButton`。

原「已选计数」InfoChip（`download_selected_format`）移到快捷选择行之后（见 Step 3）。

- [ ] **Step 3: 快捷选择芯片胶囊化——删自制 `QuickSelectChip`**

把三个 `QuickSelectChip` 调用替换为 `InfoChip` 胶囊（`shape = RoundedCornerShape(50)`），并保留已选计数 Chip 于同排右侧逻辑（排布：胶囊行左对齐、计数放行右端或紧随其后，取实现时最简排布——推荐一行内 [全选][仅未缓存][清除] + Spacer + 已选计数）：

```kotlin
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CommonUiTokens.pagePadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            InfoChip(
                text = stringResource(R.string.select_all),
                shape = RoundedCornerShape(50),
                textStyle = MaterialTheme.typography.labelLarge,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f),
                onClick = { selected = chapters.indices.toSet() }
            )
            InfoChip(
                text = stringResource(R.string.select_uncached),
                shape = RoundedCornerShape(50),
                textStyle = MaterialTheme.typography.labelLarge,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f),
                onClick = { selected = uncachedIndices }
            )
            InfoChip(
                text = stringResource(R.string.clear_selection),
                shape = RoundedCornerShape(50),
                textStyle = MaterialTheme.typography.labelLarge,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f),
                onClick = { selected = emptySet() }
            )
        }
```

（`InfoChip` 需要 `androidx.compose.foundation.layout.PaddingValues` import；`QuickSelectChip` composable 整块删除。）

- [ ] **Step 4: 底部确认区不变 + 取消本书确认弹窗宿主**

`第三级` 中「跳过统计 + 全宽下载按钮 + >500 二次确认」逻辑保持不动；取消本书的 `AlertDialog` 由 Activity 承载（Task 2 的 `onCancelBook` 回调处挂 `cancelBook` 确认框，见 Task 2 交付的清单）——若实现时发现挂 Activity 更绕，则在 `DownloadManageScreen` 内新增 `showCancelBook` 状态并渲染 `AlertDialog`（文案复用 `download_manage_cancel_book_confirm` / `download_manage_cancel_book`）。

- [ ] **Step 5: 编译 + 提交**

```powershell
.\gradlew :module_book:assembleDebug --console=plain
```

期望：BUILD SUCCESSFUL（与 Task 2 一并提交，提交命令见 Task 2 Step 4，若 Task 2 已单独提交则本 Task 单独提交）：

```bash
git add module_book/src/main/java/com/ebook/book/reader/ChapterDownloadSheet.kt
git commit -m "refactor(module_book): 选章 sheet 加书头与取消本书，快捷选择胶囊化"
```

---

## Task 4: 一级页视觉重做

**Files:**
- Modify: `module_book/src/main/java/com/ebook/book/DownloadManageActivity.kt`
- Modify: `module_book/build.gradle.kts`（新增 icons-extended）

- [ ] **Step 1: 依赖——`module_book` 声明 material-icons-extended**

`module_book/build.gradle.kts` dependencies 块（58 行起）追加：

```kotlin
    implementation(libs.androidx.compose.material.iconsExtended)
```

理由：FAB 播放/暂停切换需要 `Icons.Filled.Pause`（iconsExtended；PlayArrow/Delete 在 core 集），业务模块按 AGENTS 允许引扩展图标。

- [ ] **Step 2: 一级列表重做——书行卡 + FAB + 尾部取消全部**

把 `DownloadManageScreen` 整体替换为：

```kotlin
/**
 * 一级：按书下载列表 + 右下单主 FAB（播放/暂停互斥）。
 *
 * - 书行 = [CommonItemCard]（12dp 圆角、listSpacing 行距）：封面 + 书名 + 覆盖率进度条 +
 *   「剩余 N 章 · 正在下载 第 M 章」进行态 + 尾箭头；整行点击展开该书选章 sheet。
 * - 「全部取消」为破坏性操作，不放 FAB（M3 单一主动作），做在列表尾部动作项 + 二次确认。
 * - 全局状态表达：运行/暂停由 FAB 图标互斥；单书进度由行内进行态表达。
 */
@Composable
fun DownloadManageScreen(
    viewModel: DownloadManageViewModel,
    onOpenBook: (DownloadBookGroup) -> Unit
) {
    val groups by viewModel.groups.collectAsState()
    val state by viewModel.downloadState.collectAsState(initial = DownloadState.Finished)
    var showCancelAll by remember { mutableStateOf(false) }
    var pendingCancelBook by remember { mutableStateOf<DownloadBookGroup?>(null) }

    val hasTask = groups.isNotEmpty()
    val isRunning = state is DownloadState.Progress

    Box(modifier = Modifier.fillMaxSize()) {
        if (!hasTask) {
            EmptyState(modifier = Modifier.fillMaxSize())
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = CommonUiTokens.pagePadding)
                    .padding(bottom = 88.dp), // FAB 悬浮区留白，防最后一行被遮挡
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
                item(key = "cancel_all") {
                    // 列表尾部破坏性动作：FAB 只管主操作，取消全部放列表上下文 + 二次确认
                    CommonCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showCancelAll = true }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.download_manage_cancel_all),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        }

        // 主操作 FAB：图标随下载状态互斥（运行中=暂停，否则=播放/继续）
        if (hasTask) {
            FloatingActionButton(
                onClick = {
                    viewModel.sendAction(
                        if (isRunning) DownloadService.ACTION_PAUSE
                        else DownloadService.ACTION_RESUME
                    )
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(CommonUiTokens.pagePadding)
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (isRunning) R.string.download_manage_pause_all
                        else R.string.download_manage_resume_all
                    )
                )
            }
        }
    }

    // 全部取消确认
    if (showCancelAll) {
        AlertDialog(
            onDismissRequest = { showCancelAll = false },
            text = { Text(stringResource(R.string.download_manage_cancel_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showCancelAll = false
                    viewModel.sendAction(DownloadService.ACTION_CANCEL)
                }) {
                    Text(
                        stringResource(R.string.download_manage_cancel_all),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelAll = false }) {
                    Text(stringResource(com.ebook.common.R.string.cancel))
                }
            }
        )
    }
}
```

新 import：`androidx.compose.foundation.layout.PaddingValues`、`androidx.compose.material.icons.filled.Delete`、`androidx.compose.material.icons.filled.Pause`、`androidx.compose.material.icons.filled.PlayArrow`、`androidx.compose.material3.FloatingActionButton`、`androidx.compose.material3.Icon`。

- [ ] **Step 3: 书行组件 `DownloadBookRow`**

新增（替换原 `DownloadGroupCard` 全部内容，`DownloadGroupCard` 删除）：

```kotlin
/**
 * 单本书的下载行：封面 + 书名 + 覆盖率进度 + 进行态副行 + 尾箭头。
 *
 * 取消本书不在行内（在选章 sheet 头部）；本行只负责「点进去选章」。
 * 进行态文案仅 DownloadState.Progress 期间展示（isActive 已随 isDownloading 收起）。
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
                Text(
                    text = group.bookName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { coverageRatio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.download_manage_coverage_format,
                        group.cachedChapters,
                        group.totalChapters
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.download_manage_remaining_format, group.remaining),
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
```

新 import：`com.ebook.common.ui.CommonItemCard`、`androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight`、`androidx.compose.material3.LinearProgressIndicator`；删除 `InfoChip` 相关（若不再使用）、旧 `SummarySection` / `DownloadGroupCard` 及其 KDoc。

删除整块：`SummarySection` composable、`DownloadGroupCard` composable、`paddingValuesForList`（若不再引用；`CommonUiTokens.pagePadding` 改直接内联）。`EmptyState` 保留。

- [ ] **Step 4: 编译 + 提交**

```powershell
.\gradlew :module_book:assembleDebug --console=plain
```

期望：BUILD SUCCESSFUL（Pause/PlayArrow 图标已随 icons-extended 解析）。

```bash
git add module_book/src/main/java/com/ebook/book/DownloadManageActivity.kt module_book/build.gradle.kts
git commit -m "feat(module_book): 下载中心一级页重做——轻书行 + 单主 FAB

删除状态摘要卡与三按钮操作区，全局主操作收敛为右下播放/暂停 FAB，
取消全部改列表尾部动作项；书行信息精简并复用 CommonItemCard 体系。
"
```

---

## Task 5: 文案清理 + 全量验证

**Files:**
- Modify: `module_book/src/main/res/values/strings.xml`

- [ ] **Step 1: 删除已无引用文案**

```powershell
# 先核对当前引用情况，只删确认无引用的
grep -rn "download_manage_status_running\|download_manage_status_paused\|download_manage_status_idle\|download_manage_total_format" module_book/src --include=*.kt
```

若上述四个 key 无任何 Kotlin 引用，从 `strings.xml` 删除：

```xml
    <string name="download_manage_status_running">正在下载</string>
    <string name="download_manage_status_paused">已暂停</string>
    <string name="download_manage_status_idle">待开始</string>
    <string name="download_manage_total_format">共 %1$d 章待下载</string>
```

同时核对 `download_manage_active_tag`（下载中，sheet 书头状态与行徽章仍用）、`download_manage_resume_all` / `download_manage_pause_all`（FAB contentDescription 用，保留）。

- [ ] **Step 2: 全量编译 + 单测**

```powershell
.\gradlew :module_book:testDebugUnitTest :module_book:assembleDebug --console=plain
```

期望：BUILD SUCCESSFUL；`ChapterDownloadStatusTest` 6/6、`ChapterSelectionTest` 13/13 全过。

- [ ] **Step 3: Commit**

```bash
git add module_book/src/main/res/values/strings.xml
git commit -m "chore(module_book): 清理下载中心已无引用的状态/总计文案"
```

---

## Task 6: ADR-0035 就地修订

**Files:**
- Modify: `docs/adr/0035-download-center.md`

- [ ] **Step 1: 决策 1 与决策 6 补充承载与启动契约**

`决策 1` 末尾追加一句：

```markdown
   选章以**全高 BottomSheet** 承载于下载管理页（而非独立整屏二级页），既不打断一级列表现场、又保留整屏可扫性（底部面板半屏扫描的历史问题见动机）。
```

`决策 6` 末尾追加一句：

```markdown
   启动 Intent 为**空载信号**：任务只在 `download_chapter` 表入库，章节列表不再随 Intent 传递——整本大额下载不接近 Binder 事务上限（`TransactionTooLargeException`），冷启动/重启/续跑统一由服务读库取篇。
```

- [ ] **Step 2: 追加修订说明段（文件末尾）**

```markdown
## 修订说明（2026-09-12）

- 「选章与管理合一的两级结构」的**承载形态**演进：二级由整屏选章页改为下载管理页内的全高 `ModalBottomSheet`（决策 1）。选章语义（分组、三态、500 章软上限、「仅未缓存」筛选、确认留在原处）不变。
- 「统一下发入口」的**启动契约**演进：启动 Intent 不再携带任务列表（决策 6），`download_chapter` 表为唯一队列事实源；先入库再拉服务的顺序不变，服务端取章一律读库。
```

- [ ] **Step 3: Commit**

```bash
git add docs/adr/0035-download-center.md
git commit -m "docs(adr): 修订 ADR-0035 承载形态与启动信号化"
```

---

## Task 7: 装机验证清单交付

无代码改动。向用户交付以下人工验证清单（AGENTS.md 分工：Agent 止于编译，二、三步人工做）：

1. 一级列表渲染、空态「暂无下载任务」、FAB 随 hasTask 显示/隐藏；
2. 点书行展开全高选章 sheet，焦点章组默认展开并滚到顶；
3. 阅读器「下载」按钮冷启动直达自动展开该书 sheet；旋转重建不重放直达、不回到一级；
4. FAB 播放/暂停图标随运行状态互斥切换；列表尾部「取消全部下载」二次确认后队列清空；
5. sheet 头部「取消本书下载」确认后该行任务删除、一级进度回退；
6. **全选 3000 章整本下载不崩溃、不抛 TransactionTooLargeException（Task 1 的关键回归点）**；
7. START_STICKY 重启 / 通知「继续」续跑正常。

---

## 自审记录

- **Spec 覆盖**：D1（Task 4 无摘要卡）、D2（Task 4 FAB + 尾行取消）、D3（Task 2/3 sheet）、D4（Task 3 胶囊化 + Task 4 CommonItemCard）、D5（Task 1 信号化）、D6（Task 2 bookSheet）、D7（四态与焦点章组沿用，Task 2/3 保持）；§8 组件资源（Task 4 依赖、Task 5 文案）、§9 验证（Task 5/7）、§10 ADR（Task 6）——全覆盖。
- **占位符**：无 TBD/TODO；「推荐实现时最简排布」仅两处指明取舍点并给了默认值，非占位。
- **类型一致性**：`bookSheet: StateFlow<BookSelectionState?>`、`BookChapterSelectSheet(state, onDismiss, onConfirm, onCancelBook)`、`DownloadBookRow(group, onClick)`、`buildStartIntent(context)` 在任务间引用一致。