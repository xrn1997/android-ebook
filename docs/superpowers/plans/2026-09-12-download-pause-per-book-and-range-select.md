# 下载中心：按书暂停 + 分组 FAB 面板 + 范围选章

> **状态说明（2026-09-12 实施期修订，以代码与 `docs/adr/0036-download-pause-per-book.md` 修订说明为准）**：Task 6/9 的二级操作面形态已被同日多轮评审推翻——「本书」组不再与勾选工具同住面板，而是搬到书头状态胶囊右侧上下两枚（上「取消本书」描边、下「下载|暂停|继续」一枚三态钮），面板从此是固定四枚勾选工具、一排不随状态增减；「无任务且无勾选时整组消失」改为常驻 + 置灰；面板不再在执行后自动收起（收起只由 FAB 上的 X 承担），列表让位高度与滚动条轨道按面板**实测**高度动态叠加；预演小字从面板底部搬进书头胶囊行下方；「仅未缓存」「范围」「清除」三枚图标各换形（斜杠云 / 123 / 取消选择），因原选的下载箭头、漏斗与滑块刻度分别被读成「开始下」「排序筛选」「调节一个值」。Task 7 一级页额外并入空态引导文案、元信息合并行与 `navigationBarsPadding`，阅读器直达态的二级返回改为直接退出本页。各 Task 正文保留原计划作为历史记录。

## Context

用户对二级选章页的反馈（经多轮效果图迭代收敛）：

1. 缺「暂停」，操作应归组、风格统一（FAB 家族）
2. 需要范围选章（第 X 章到第 Y 章，确认后**填入勾选**，用户已拍板）
3. 选中数不放 FAB 角标——与书头状态胶囊共用；「下载 X 章」简化为「下载」
4. 四个选择工具（全选/仅未缓存/范围/清除）是三个操作（下载/暂停/取消）的**前一步**——展开面板**分组**呈现，不混排
5. 状态机要捋清：「下载」与「继续下载」合并为**同一主操作**（同一意图），「暂停本书」是它唯一的对向操作，按书状态决定各自何时出现

暂停语义：二级页是书上下文，暂停 = **按书暂停**（任务保留、服务跳过该书取篇）。持久化（新表 `paused_book`，v7→v8）——进程重启不静默恢复。

## 状态机（按书，面板随态；「下载」与「继续下载」是同一主操作的两种语境）

```
无任务 ──下载──> 排队/下载中 ──暂停本书──> 已暂停
  ^                    │                     │
  │    取消本书（两个有任务态均可）          │ 下载（自动解除暂停，与继续同一按钮）
  └────────────────────┴─────────────────────┘
```

| 书状态 × 勾选 | 「本书」组 | 主操作（**同一按钮**，状态化） |
|---|---|---|
| 无任务 | **整组消失** | 下载（置灰） |
| 排队/下载中 · 无勾选 | 暂停本书 + 取消本书 | 下载（置灰——已在跑，无可加） |
| 已暂停 · 无勾选 | 取消本书 | **继续下载**（恢复既有队列） |
| 任意状态 · 有勾选 | 按上表 | **下载**（入队；已暂停顺带解除） |

- 「下载」与「继续下载」**合并**：有有效勾选＝入队下发（已暂停自动解除暂停）；已暂停且无勾选＝继续跑既有队列。两者从来都是「让这本书下起来」的同一意图，不设两个按钮
- 「暂停本书」是它唯一的对向操作，只在有任务且未暂停时出现
- 「下载中 vs 排队中」仅书头胶囊显示差异（下载中 = 当前活跃章属于该书），面板操作相同

## 二级页最终布局

```
Toolbar 下载管理
书头：封面 48x64 + 书名 + 状态胶囊（共 N 章 · 已选 M 章[M>0] · 下载中|已暂停）
      —— 选中数与下载状态共用胶囊行，勾选实时刷新，不放 FAB 角标
章节列表（全屏占满 + 右缘快速滚动条）
右下角主 FAB（Tune 图标）：点击展开分组面板
  ┌ 面板（锚在 FAB 上方，点空白/返回键收起）────────┐
  │ 勾选                                                │
  │ [全选] [仅未缓存] [范围] [清除]                      │ ← 图标圆钮+下标签
  │ ─────────────────────                               │
  │ 本书                                                │
  │ [暂停本书] [取消本书] [下载|继续下载]                │ ← 同一视觉语言，左右排布；
  │        已跳过 N 章（小字，有才显示）                  │    无任务时整组消失
  └─────────────────────────────────────────────────────┘
```

- 面板内**所有操作统一为「图标圆钮 + 下标签」一种视觉语言**，左右排布，不用文字胶囊/通栏大按钮；「下载|继续下载」以主色实底圆钮置尾为主操作，「取消本书」红描边
- 状态化主操作（同一按钮，见状态表）：`downloadCount > 0` → 「下载」（走 confirmDownload：入队 + 已暂停自动解除）；`downloadCount == 0 && paused && hasTasks` → 「继续下载」（走 resumeBook）；否则「下载」置灰
- 范围选择：「范围」工具弹对话框（起/止章号数字输入 + 全书共 N 章 + 校验），确定后替换勾选并滚动到起点；超 500 章仍走既有二次确认
- 面板展开态是页面本地状态；页面内 `BackHandler(enabled = 展开中) { 收起 }` 组合期晚于活动层注册、先消费

## 实施步骤

### 1. ADR（先文档后代码）

- 新建 `docs/adr/0036-download-pause-per-book.md`：按书暂停（表设计、队列跳过、全暂停停服、确认即解除）+ 分组 FAB 面板与状态机 + 范围选章
- `docs/adr/0035-download-center.md` 修订说明：二级操作面改为右下主 FAB + 分组面板，选中数并入书头状态胶囊
- `docs/adr/0034-long-catalog-browse-and-select.md` 修订说明：平铺选择新增范围入口

### 2. lib_ebook_db（v7 → v8）

- 新建 `entity/PausedBookEntity.kt`（`paused_book`，主键 `note_url`，@Parcelize 对齐包内约定）
- 新建 `dao/PausedBookDao.kt`：`insert`（REPLACE）/ `delete(noteUrl)` / `getAll(): List<String>` / `clearAll()`
- `AppDatabase.kt`：version = 8、entities+、`pausedBookDao()`
- `DatabaseModule.kt`：`MIGRATION_7_8`（CREATE TABLE，DDL 与 Room 导出 createSql 同字）挂链尾 + `providePausedBookDao`
- 构建生成 `schemas/com.ebook.db.AppDatabase/8.json` 并提交
- `AppDatabaseSchemaTest`：SCHEMA_LATEST_VERSION = 8 + 「v8 相对 v7 只多 paused_book 表」
- 新建 `PausedBookDaoTest`（in-memory Room，参照 `SearchHistoryDaoTest`）

### 3. DownloadRepository（module_book）

- 注入 `PausedBookDao`
- `getNextDownloadTask()` / `findLatestDownloadTask()`：循环前取一次暂停集，遍历书架时跳过暂停书（**不改 DownloadChapterDao 既有查询**——lib_book_common 的 `FakeDownloadChapterDao` 零波及）
- 新增 `pauseBook(noteUrl)` / `resumeBook(noteUrl)` / `getPausedBooks(): Set<String>`
- `deleteTasksForBook`：顺带删该书暂停行；`clearAllTasks`：顺带 `pausedBookDao.clearAll()`
- 新增 `deleteTasksOutsideShelf()`：Kotlin 侧按书清理孤儿行（书不在架），承接原 drain 时 clearAll 的清理职责

### 4. DownloadService

- `toDownload()` else 分支：清孤儿 → `countTasks() > 0` = 全在暂停书 → `emitState(Paused)` + 静默停服（不发完成误报，省 dataSync 配额；RESUME Intent 可随时拉起）→ 否则照旧 `finishDownload()`
- `isPause()` null 分支：`countTasks() > 0` → Paused，否则 Finished
- KDoc：暂停不打断已发出的当前章请求（该章完成后下一轮跳过）

### 5. DownloadManageViewModel

- `DownloadBookGroup.paused`、`BookChapterSelection.paused`；loadGroups/loadSelection 合入暂停集
- 新增 `pauseBook(noteUrl)`（仓库写 + 双刷新）与 `resumeBook(noteUrl)`（仓库删 + 双刷新 + `sendAction(ACTION_RESUME)`——服务死了经 getForegroundService 拉起续跑）；两方法分开不复用
- `confirmDownload()`：下发前 `model.resumeBook(noteUrl)` 解除暂停

### 6. 二级页 ChapterDownloadSheet

- 书头：封面 48x64 + 书名 + 胶囊行（共 N 章 + 已选 M 章[M>0，随勾选实时变] + 下载中|已暂停）
- 列表占满 + `ReaderFastScroll`（已 internal）
- 右下主 FAB：`FloatingActionButton`（Icons.Outlined.Tune），点击展开/收起分组面板
- 分组面板（锚 FAB 上方，`AnimatedVisibility`，全部操作统一为图标圆钮 + 下标签、左右排布）：
  - 「勾选」组：四个图标圆钮（全选=SelectAll、仅未缓存=Download、范围=LinearScale（主色高亮）、清除=ClearAll/Backspace）
  - 「本书」组（有任务才渲染，同一视觉语言）：`暂停本书`（Pause，仅未暂停时）+ `取消本书`（Delete，红描边，保留二次确认）+ `下载|继续下载`（Download/PlayArrow，主色实底圆钮置尾——状态化同一按钮：`downloadCount > 0` → 「下载」走 confirmDownload（入队 + 已暂停自动解除）；`downloadCount == 0 && paused && hasTasks` → 「继续下载」走 resumeBook；否则「下载」置灰）
  - 跳过统计小字（skippedQueued > 0 时，面板底部）
- 页面内 `BackHandler(enabled = 面板展开) { 收起 }`
- 范围对话框（本地状态）：起/止章输入 + `parseChapterRange` 校验 + 确定替换勾选 + 滚到起点
- 回调：`onConfirm` / `onCancelBook` / `onPauseBook` / `onResumeBook` / `onRetry`（主操作由页面按状态路由到 onConfirm 或 onResumeBook，VM 层方法仍分开）

### 7. 一级页 DownloadManageActivity

- `DownloadBookRow` 书名旁「已暂停」InfoChip（`group.paused`）；其余不动（保持只展示 + 导航）

### 8. 纯逻辑与测试

- `reader/ChapterSelection.kt` 新增 `parseChapterRange(fromText, toText, total): IntRange?`（1-based 起止 → 0-based 索引闭区间；空/非数字/越界/起>止 → null）
- `ChapterSelectionTest` 补范围解析用例
- `FakeDownloadChapterDao` 不受影响（接口零改动）

### 9. strings.xml（module_book）

新增：`download_manage_pause_book`（暂停本书）、`download_manage_resume_book`（继续下载）、`download_manage_paused_tag`（已暂停）、`download_manage_selected_format`（已选 %1$d 章）、`download_manage_action_download`（下载）、`download_actions_fab_desc`（下载操作，主 FAB contentDescription）、`download_manage_group_select`（勾选）/ `download_manage_group_book`（本书）分组小标题、`download_range_title`（范围选择）、`download_range_from_label`（起始章）、`download_range_to_label`（结束章）、`download_range_total_format`（全书共 %1$d 章）、`download_range_invalid`（范围无效提示）。复用既有 `confirm` 与 common `cancel`。

## 验证

```powershell
.\gradlew :lib_ebook_db:testDebugUnitTest
.\gradlew :module_book:testDebugUnitTest :module_book:assembleDebug
```

- 单测全绿、改动文件零新增编译警告；`8.json` 生成并纳入版本管理

### 人工装机验证项（Agent 止于编译，以下未验证）

1. 覆盖安装升级（v7→v8）不崩，书架/队列旧数据完好
2. 书头「已选 M 章」随勾选实时增减；主 FAB 无角标；面板展开/收起动画正常，返回键先收面板再退页
3. 下载中暂停本书：当前章完成后停、书头胶囊变「已暂停」、任务保留；继续后恢复
4. 全部书暂停：服务静默停、无「下载完成」误报；继续后 RESUME 拉起
5. 无任务时面板只有「勾选」组 + 置灰下载（「本书」组消失）
6. 范围选择：起止填入勾选 + 滚到起点；非法输入确定置灰有提示；超 500 章走二次确认
7. 一级列表「已暂停」胶囊展示
