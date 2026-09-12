# 下载中心重构设计（2026-09-12）

> 状态：设计已获批，进入实施计划阶段（brainstorming 收尾产物，落地后不回写）。
>
> **状态说明（2026-09-12 实施期修订，以代码与 ADR-0035 修订说明为准）**：D2 的单主 FAB 与列表尾部「取消全部」、D3 的全高 BottomSheet 承载，装机观感不符合预期，已被用户否决——一级页不再设集中控制按钮，「取消全部」删除；二级恢复整屏二级页（一级点书行进入、返回键回一级）。D1/D4/D5/D6 部分（轻书行、组件收编、启动信号化、bookSheet 装载态）保留，但状态机恢复为 `DownloadCenterStep`（Books/PickBook）导航 + `bookSheet` 装载结果的组合。

## 1 背景与动机

当前下载中心（`DownloadManageActivity` 两级：一级按书任务列表 + 二级整屏选章页）存在四类问题：

1. **顶部操作区拥挤**：状态摘要卡里并排三个 `OutlinedButton`（全部开始/全部暂停/取消全部），窄屏很挤；
2. **书卡信息过杂**：单行内叠封面 + 状态标签 + 剩余 + 正在下载 + 覆盖率进度条 + 取消按钮，层级混乱；
3. **二级整屏太重**：选章占据整屏二次导航页，而项目惯例是「明细类内容用 `ModalBottomSheet`」（见缓存管理页）；
4. **组件不统一**：自制 `QuickSelectChip`、自定义卡片布局，未走 ADR-0006 的 CommonCard / CommonItemCard / InfoChip / 语义色体系。

另顺带修复评审遗留项：`DownloadRepository.startDownload` 把整份章节实体列表经 `buildStartIntent` 塞进 Intent（`DownloadService.kt` 的 `putParcelableArrayListExtra`），大额整本下载接近 Binder 事务 1MB 上限，有 `TransactionTooLargeException` 风险。通过「启动信号化」在结构上根除。

## 2 设计决策

| # | 决策 | 理由 |
|---|---|---|
| D1 | **一级页去掉状态摘要卡** | 全局状态由 FAB 按钮形态（播放/暂停互斥）与行内进行态表达，摘要卡冗余且挤 |
| D2 | **全局主操作 = 右下单主 FAB**（图标随状态切换播放/暂停），**破坏性「全部取消」不进 FAB** | M3 最佳实践：一个 FAB 只承载一个主动作；基类 `ToolbarLayout` 无 actions 插槽（BookSourceManageActivity 已验证），故改放列表尾部动作项 |
| D3 | **二级选章 = 全高 `ModalBottomSheet`** | 项目明细惯例（缓存页同款），全高保住 ADR-0035 动机里的「整屏可扫性」，不重蹈半屏面板覆辙 |
| D4 | **组件全面收编 ADR-0006 体系** | CommonItemCard / BookCover / InfoChip / ModalBottomSheet / AlertDialog / 全语义色；删自制 QuickSelectChip |
| D5 | **下载启动信号化**：`startDownload` 只「入库 + 发空载 Intent」，`download_chapter` 表为唯一队列事实源 | 删 Intent 大载荷（根除 TransactionTooLargeException）；冷启动/重启/续跑统一读库 |
| D6 | **页面状态机简化**：去掉 `DownloadCenterStep`，二级改由 `bookSheet: StateFlow<BookSelectionState?>` 承载 | sheet 收起自带返回语义，不再需要步骤态 + 自绘 BackHandler |
| D7 | 装载态沿用既有四态（Loading / Absent / Failed / Ready）与焦点章组默认展开 | 上一轮评审修复已沉淀，保持行为不回归 |

## 3 一级页（下载列表）

```
┌─────────────────────────────────┐
│  书行 ①  ┌────┐ 书名               ›  │
│          │封面│  ▓▓▓░░░░ 已缓存 42/505 │
│          └────┘  剩余 12 章 · 正在下载 第 3 章 │
│  书行 ②  ...                       │
│          ……                        │
│  [delete] 取消全部下载  （列表尾部动作项）   │
│              ● FAB 播放/暂停（右下）      │
└─────────────────────────────────┘
```

- **页面骨架**：`Surface(background)` + `Box`：`LazyColumn`（`weight(1f)`）+ 右下 `FloatingActionButton`（`align(BottomEnd)` + 底部留白防遮挡）。
- **书行**：`CommonItemCard`（12dp 圆角、`listSpacing` 8dp 行距），内容 = `BookCover` 48×64 + 文本列：
  - 书名 `bodyLarge`；
  - 覆盖率小进度条 `LinearProgressIndicator` + `已缓存 x/y`（`labelSmall` / `onSurfaceVariant`）；
  - 进行态副行 `剩余 N 章 · 正在下载 第 M 章`（仅 `DownloadState.Progress` 期间展示，暂停/完成收起，与既有 `isDownloading` 口径一致）。
  - 尾箭头 `KeyboardArrowRight`。整行点击 → 展开该书选章 sheet。
- **取消本书**：不在行内，收进该书选章 sheet 头部（见 §4）。
- **空态**：无任务时整页居中「暂无下载任务」，FAB 隐藏。
- **FAB**：图标随 `downloadState` 互斥切换——`Icons.Filled.PlayArrow`（待开始/暂停）⇄ `Icons.Filled.Pause`（运行中）。仅 `hasTask` 显示。`Pause` 属 icons-extended，`module_book` 需声明依赖（`PlayArrow`/`Delete` 在 core 集）。
- **全部取消**：一级列表尾部的动作项（`CommonListItem` 形态 + `Icons.Default.Delete` + `error` 色 + `AlertDialog` 二次确认，文案沿用 `download_manage_cancel_all_*`）。仅 `hasTask` 显示。不放 FAB / 不放工具栏（基类无 actions 插槽）。

## 4 二级选章（全高 BottomSheet）

```
┌─────────────────────────────────┐
│ 手柄                             │
│ [封面] 书名            取消本书下载  │  ← TextButton(error) + AlertDialog 确认
│   [下载中]                      │  ← 状态 InfoChip（仅 Progress 期间展示）
│ ── 快捷选择 ──                    │
│  [全选] [仅未缓存] [清除]          │  ← InfoChip 胶囊（替代 QuickSelectChip）
│ ── 分组列表（sticky 组头）──        │
│  ▣ 第 1-100 章  已缓存 98/100   ⌄ │  ← 三态勾选 + 范围 + 缓存计数 + 展开箭头
│    ☑ 第 1 章  [已缓存]            │  ← 勾选框+序号+章名+状态徽章（展示性）
│    ☐ 第 2 章  [待下载]            │
│  ...                            │
│  已跳过 3 章（已在下载队列）         │
│ [ 下载 197 章 ]（全宽，>500 二次确认）│
└─────────────────────────────────┘
```

- **Sheet 形态**：`ModalBottomSheet`（沿用缓存页细节的分发：`navigationBarsPadding`、全高内容区）。内容可与现有 `BookChapterSelectPage` 主体复用（分组/三态/上限纯逻辑零改动）。
- **焦点章组**：打开时默认展开含 `focusChapter` 的一组并滚动到顶（`focusChapter` 字段沿用，不回归）。
- **快捷选择**：全选 / 仅未缓存 / 清除 → `InfoChip` 胶囊（`RoundedCornerShape(50)`），删除自制 `QuickSelectChip`。
- **确认下发**：剔除已排队章 → 乐观更新 `queuedIndices` → `confirmDownload`；>500 章 `AlertDialog` 二次确认沿用。
- **取消本书**：sheet 头部 `TextButton`（error 色）→ `AlertDialog` 确认 → `cancelBook(noteUrl)`（删除该队列任务，不动已缓存内容）。

## 5 数据流与状态机

`DownloadManageViewModel`：

- 删除 `DownloadCenterStep`（Books / PickBook）；新增：
  - `bookSheet: StateFlow<BookSelectionState?>`：`null` = sheet 收起；非空 = 该书的装载结果（复用 `BookSelectionState` 的 Loading / Absent / Failed / Ready）。
  - `openBook(noteUrl, tag, focusChapter = -1)`：置 Loading → 装载 → Ready/Absent/Failed（含失败 `reportFailure`，行为与现状一致）。
  - `closeBookSheet()`：置 `null`。
- 保留：`groups`、`downloadState`、`confirmDownload`（乐观更新）、`cancelBook`、`sendAction`、`resumeIfPending`、`remainingCount`。
- 装载四态（Loading/Absent/Failed/Ready）与文案（`download_center_loading` / `download_center_not_on_shelf` / `download_center_load_failed`）沿用，不改。

## 6 下载启动信号化（Important 1 修复）

现状：`startDownload` → `addTasks(入库)` → `buildStartIntent(chapters)` 携带全部章节 → 服务 `extractChapters` → `addNewTask` **再插一遍库**（与 `addTasks` 重复写入）。

改后：

```kotlin
// DownloadRepository
suspend fun startDownload(chapters: List<DownloadChapterEntity>) {
    if (chapters.isEmpty()) return
    addTasks(chapters)                                    // 先入库（唯一事实源）
    if (!DownloadService.start(context, buildStartIntent(context))) {
        ToastUtil.showShort(context, context.getString(R.string.download_start_restricted))
    }
}
```

- `buildStartIntent(context)` → 空载 Intent（不再携带任何章节）。
- `DownloadService.onStartCommand`：删除 `EXTRA_CHAPTERS` / `extractChapters`（含 API 33 分支）/ `addNewTask`；无载启动落入既有「无携带任务」分支——`findNextDownloadChapter()` 读库 → `toDownload()`。
- 不变量复核：
  - 冷启动 / START_STICKY 重启 / 通知续跑：一律读库续跑（本就如此，此改仅统一）；
  - 运行中追加任务：新 start 被 `!isStartDownload && !isDownloading` 挡住时，循环下一轮 `findNextDownloadChapter` 自动捞到新任务，行为等价；
  - `forceRefresh` 标记随 `addTasks` 落库不丢；
  - 启动被拒（dataSync 配额等）：任务已入库，toast 提示后稍后重试，与现状一致。
- 删除物：`EXTRA_CHAPTERS`、`extractChapters`、`addNewTask`，约 50 行 Parcelable 提取样板。

## 7 导航与入口

- 不变：`DownloadManageActivity` 仍 `@Route(KeyCode.Book.DOWNLOAD_PATH)`，双 Manifest 无变更；`pickParams`（`EXTRA_OPEN_PICK` + noteUrl/tag/focusChapter）冷启动一次性消费逻辑保持（`savedInstanceState == null` gate）。
- 阅读器 `ReaderPanel.DOWNLOAD`：仍先确保在架 → 带参打开下载中心 → 自动展开该书 sheet。
- 返回语义：一级 Back → finish；sheet 打开时 Back → 收 sheet（`ModalBottomSheet` 自带），删除自绘 `BackHandler` 分支。

## 8 组件、资源与依赖

- **删除**：`SummarySection`、`DownloadGroupCard`（含行内取消按钮）、`QuickSelectChip`、`BackHandler` 分支、`EXTRA_CHAPTERS`/`extractChapters`/`addNewTask`、`DownloadCenterStep`。
- **新增依赖**：`module_book` 声明 material-icons-extended（取 `Icons.Filled.Pause`）。
- **文案**：撤「共 N 章待下载」（`download_manage_total_format` 若仅此处引用则删除，实施时 grep）；「全部取消」文案复用；FAB 无文字。实施时 `grep download_manage_resume_all/pause_all` 清理无用残留。
- **换行/加载态文案复用**：`download_center_loading` / `not_on_shelf` / `load_failed` 沿用。

## 9 测试与装机验证

- 单测：`ChapterSelectionTest` / `ChapterDownloadStatusTest` 不受影响（纯逻辑零改动）；本次无新纯逻辑产线。
- **装机验证清单**（人工项，Agent 止于编译）：
  1. 一级列表渲染、空态、FAB 显示/隐藏；
  2. 点书行展开 sheet，焦点章组默认展开并滚到顶；
  3. 阅读器下载按钮冷启动直达自动开 sheet；旋转重建不重放直达；
  4. FAB 播放/暂停互斥切换；列表尾部「取消全部」确认弹窗；
  5. sheet 头部「取消本书下载」确认后该行任务删除、进度回滚；
  6. **全选 3000 章整本下载不崩（Important 1 关键回归，TransactionTooLargeException 不再出现）**；
  7. START_STICKY 重启续跑。

## 10 文档同步

- **ADR-0035 就地修订**（实施落地时同步）：
  - 决策 1 补充「二级承载形态为全高 BottomSheet」；
  - 决策 6 补充「启动 Intent 不再携带任务列表（信号化），`download_chapter` 表为唯一队列事实源」；
  - 增加修订说明段（注明日期与为何演进）。
- 本 spec 为一次性设计工件，落地后不回写（AGENTS.md 口径）。