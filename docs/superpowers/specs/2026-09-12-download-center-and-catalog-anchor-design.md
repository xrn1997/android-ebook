# 下载中心与目录定位交互（抽屉锁定解耦 + 下载中心两级结构）

- **日期**：2026-09-12
- **状态**：设计定稿，待实施
- **范围**：`module_book`（`reader` 包、`DownloadManageActivity` 及其 ViewModel、`ReadBookActivity`、`DownloadRepository`、`strings.xml`、双 Manifest）。无 DB 迁移、无网络/书源改动、无跨模块改动。
- **修订关系**：本篇是在 `2026-09-12-reader-catalog-order-and-download-grouping-design.md`（v1，已实施）基础上的修订版。v1 的「目录倒序」「百章分组」「500 软上限」「缓存存在性不作判据」决策仍然成立并保留；本篇修订的是 v1 的「切模式总是重新定位到当前章」与「下载选章用 ModalBottomSheet」两个形态决策，并把下载管理页并入为「下载中心」。

## 1. 修订动机（2026-09-12 逐条确认）

v1 实施后收到两点反馈：

1. **「倒序也锁定本章位置」不合理**。v1 里打开抽屉/切模式一律滚动到当前章在当序列表中的显示位置——倒序下这等于把用户拽回「当前章在倒序列表里的等价位置」，最新章明明在顶部却从来看不见，倒序失去意义。
2. **下载选章的抽屉式（ModalBottomSheet）不方便**，且**下载管理页过于抽象**：它的进度条是全书缓存覆盖率（不是下载进度），也看不到具体下了哪些章节。诉求是把「下载页」与「下载管理页」整合。

配套确认的交互口径（用户 2026-09-12）：

- 正序/倒序切换时真正需要保持的是**当前在列表中的位置（锚点）**，不是滚回当前章。
- 顺序按钮一旦被点过，自动「锁定当前章」功能就关闭；可以再加一枚**瞄准镜性质的符号按钮**，点一下锁定、再点一下取消锁定。
- 「顺序」（正序/倒序）与「锁定」（锁定当前章/不锁定）是两套独立逻辑。
- 下载选章确认后**留在本页**（可继续加单）。

## 2. 决策

1. **目录抽屉把「顺序」和「定位」拆成两个独立状态**：`descending`（正序/倒序）与 `lockedToCurrent`（打开时是否自动定位当前章，默认开）。新增瞄准镜按钮切换 `lockedToCurrent`；点顺序胶囊会同时把 `lockedToCurrent` 置为 false（点过顺序即不再自动拽回当前章）。
2. **切顺序保持锚点**：翻转前记录当前视口顶部的章节（`listState.firstVisibleItemIndex` 换算原始章序号），翻转后滚动到该章在新序中的显示位置——用户视角「进度条位置不变」，不管他正在读当前章还是浏览到远处。
3. **下载选章与管理合并为「下载中心」**：改造 `DownloadManageActivity`（路由 `DOWNLOAD_PATH` 不变）为两级结构——一级按书任务列表，二级该书的「章节状态 + 选章」整屏视图。阅读器下载入口直达该书二级。ModalBottomSheet 形态（`ChapterDownloadSheet`）拆除。
4. **确认下载后留在二级**：任务入库拉服务后页面不关闭，二级行状态实时刷新（队列中/下载中标记出现）。
5. **进度口径修正**：一级卡的下载进度以「正在下载 第 N 章 · 章名」+「队列剩余 M 章」表达；全书缓存覆盖率进度条保留但文案明确为「全书已缓存 x/y」，不再冒充本次下载进度。
6. **确认时跳过已在队列中的章节**：同一章不会被重复入队（配合 `dur_chapter_url` 唯一索引的既有语义），跳过的章数在确认文案中体现。避免「队列里有还重下」的误操作。

## 3. 现状链路（改前，本设计的输入）

| 位置 | 职责 |
| --- | --- |
| `ReaderPanels.kt:739` `ChapterListDrawer` | 目录抽屉。`descending` 状态 + `LaunchedEffect(visible, descending)` 打开/切模式都滚到当前章（v1 遗留） |
| `ReaderPanels.kt:845` 顺序胶囊 | `onClick = { descending = !descending }`，只切顺序 |
| `ChapterDownloadSheet.kt` | 下载选章 `ModalBottomSheet`（自 v1 从 ReaderPanels 搬出）：分组/三态/上限/快捷 chips/确认 |
| `ReadBookActivity.kt:796` `ReaderPanel.DOWNLOAD` | 通知权限申请 + 缓存快照查询后开 `ChapterDownloadSheet` |
| `ReadBookActivity.kt:884` `startChapterDownload` | `selected.sorted()` → 构建 `DownloadChapterEntity`（`forceRefresh=true`）→ 入库 + `DownloadService.start` |
| `DownloadManageActivity` / `DownloadManageViewModel` | 一级按书任务卡：状态 + 队列剩余 + **全书缓存覆盖率进度条** + 全部开始/暂停/取消 + 取消本书 |
| `DownloadManageViewModel.onProgressChapter` | 只用于把「活跃书」高亮，当前下载的章节不进一级卡片 |
| `lib_ebook_db` `download_chapter` | **只存未完成的队列任务**（下好/跳过/取消即删）——「哪些章节已下载」无法从队列表得知，须以缓存文件存在性判断（`getCachedChapterIndices`） |

## 4. 设计

### 4.1 目录抽屉：顺序与定位解耦 + 锚点保持

**状态（`ChapterListDrawer` 函数顶层，不进 `AnimatedVisibility`）**：

```kotlin
var descending by remember { mutableStateOf(false) }
var lockedToCurrent by remember { mutableStateOf(true) }
```

**标题栏**：顺序胶囊左侧新增一枚瞄准镜图标按钮（`Icons.Outlined.MyLocation`）：锁定态主色高亮、未锁定弱化；`contentDescription` 与 `stateDescription` 表达「定位当前章（开/关）」；点击取反 `lockedToCurrent`。

**两个职责分离的 `LaunchedEffect`**（避免打开定位与锚点滚动互相干扰）：

- 打开定位：`LaunchedEffect(visible)` —— `visible && lockedToCurrent && durChapter in chapters.indices` 时 `scrollToItem(displayPositionOf(count, descending, durChapter))`；否则不动列表（保留浏览位置）。`lockedToCurrent` 只读、不进 key（打开当刻取现值即可）。
- 锚点滚动：顺序胶囊 `onClick` 内先记锚点再翻转：

```kotlin
onClick = {
    if (listState.layoutInfo.visibleItemsInfo.isNotEmpty()) {
        pendingAnchor = originalIndexAt(count, descending, listState.firstVisibleItemIndex)
    }
    descending = !descending
    lockedToCurrent = false
}
```

`LaunchedEffect(descending)` 消费：`pendingAnchor != null` 时 `scrollToItem(displayPositionOf(count, descending, pendingAnchor!!))` 并清空；`pendingAnchor == null`（无翻转）时不做任何滚动。锚点只在抽屉可见时可能非空（顺序胶囊只在可见时可达）。

**行为表**：

| 场景 | 行为 |
| --- | --- |
| 锁定态打开抽屉 | 定位到当前章（沿用 v1 现状） |
| 未锁定打开抽屉 | 保持上次列表位置，不滚动 |
| 阅读中（视口顶=当前章）切倒序 | 锚点=当前章 → 翻转后仍停在当前章；`lockedToCurrent=false` |
| 浏览到远处切顺序 | 锚点=视口顶章节 → 翻转后仍停在该章；`lockedToCurrent=false` |
| 切倒序后再次打开抽屉 | 保持浏览位置（不再被拽回当前章） |
| 点瞄准镜 | 锁定 ↔ 未锁定，两态独立于顺序 |

**同步更新**：`ChapterListDrawer` KDoc（去掉「切模式时会重新定位到当前章」，改为锚点保持 + 锁定口述）；ADR-0034 对应段落就地更新（见 §4.7）。

### 4.2 下载中心：一级（按书任务列表）

改造 `DownloadManageActivity` / `DownloadManageViewModel`，路由与入口不变：

- `DownloadBookGroup` 新增 `activeChapter: DownloadChapterEntity?`（该书队头/当前下载章）；一级卡内状态区：
  - 运行中：`正在下载 第 N 章 · 章名`（取自 `DownloadState.Progress` 或队头任务）；
  - 其余沿用「服务状态」文案。
- 「队列剩余 M 章」保留（`download_manage_total_format`）。
- 全书覆盖率进度条与「全书已缓存 x/y」保留，语义文案明确为覆盖率，不与下载进度混排。
- 卡片主体可点 → 进该书二级；「取消本书」按钮保留。

### 4.3 下载中心：二级（该书章节状态 + 选章）

同一 Activity 内以页面状态切换（`enum SelectStep { BOOKS, PICK(noteUrl, tag) }` 或等价 sealed 状态），`BackHandler`/返回键：二级 → 一级；一级 → finish。

**数据**（VM 加载，`noteUrl + tag` 定位书）：

- 章节目录（BookStore，随书源走——「解析一律跟书走」，章节名与总数以它为准）；
- `getCachedChapterIndices`（已下载 = 缓存文件存在）；
- 该书队列任务（`DownloadRepository` 按 `noteUrl` 查）。

三方合并为每章状态，由新纯逻辑产线（§4.5）给出。

**列表**（复用 `ChapterSelection.kt` 全部分组/三态/上限纯逻辑，零改动）：

- 组头：三态勾选框 + `第 %1$d-%2$d 章` + `已缓存 %1$d/%2$d` + 展开箭头（沿用 v1 `GroupHeaderRow`）；
- 章行：章号 + 章名 + 状态标签（**下载中** 高亮 / **待下载** / **已缓存** 徽章 / 无标签）+ 勾选框；勾中已缓存章节仍重下（`forceRefresh` 语义不变）。
- 快捷 chips（全选/仅未缓存/清除）+ 底部确认按钮 + 超 500 章二次确认（全部沿用 v1）。
- 顶部：返回一级箭头 + 书名 + 「已选 N 章」。

**确认下发**（留在二级）：

- `selected` 剔除已排队章节（`tasks` 里的 `durChapterIndex`），跳过的章数带进确认文案（如「已跳过 N 章已在下载队列」）；
- 构建 `DownloadChapterEntity`（`sorted()`、`forceRefresh=true`、书信息冗余字段）→ 入库 + `DownloadService.start`（链路抽取见 §4.4）；
- 不 finish——留在二级，行状态随任务实时刷新。

### 4.4 发送链路抽取与阅读器入口

- `ReadBookActivity.startChapterDownload` 的「selected → 任务列表 → 入库 + 拉服务」链路**收进 `DownloadRepository` 的统一入口**（构建任务归属书信息经参数传入），阅读器与下载中心二级共用，不再复制。
- **阅读器下载入口**（`ReaderPanel.DOWNLOAD`）改为：`startActivity` 本 Activity（intent extras：`noteUrl`、`tag`、`durChapter`、直达二级标志）→ 二级该书选章态；原有「通知权限申请 + 缓存快照查询」逻辑迁移到下载中心（二级确认前申请通知权限——进度通知仍是下载必要渠道，拒绝不影响下载，沿用「拒绝仍可下载」语义）。
- `ChapterDownloadSheet.kt` 的 `ModalBottomSheet` 壳拆除，`ChapterDownloadSheet`/`QuickSelectChip`/`DownloadChapterRow` 三个 composable 改编为二级视图组成件（文件名随搬迁更名，保留分组/勾选/上限语义）。
- 原 `ReaderPanel.DOWNLOAD` 分支与 `startChapterDownload` 在 `ReadBookActivity` 中删除（无其他调用点）。

### 4.5 新纯逻辑：章节状态合并（可 JVM 单测）

新增 `chapterDownloadStatus(chapters, cachedIndices, taskIndices, activeChapterIndex)`（放 `ChapterSelection.kt` 同目录新纯逻辑文件或并入 `ChapterSelection.kt`），返回每章一个状态：

- 状态优先级：**下载中**（`activeChapterIndex` 命中，同时在队）> **待下载**（在 `taskIndices`）> **已缓存**（在 `cachedIndices`）> **未下载**。
- 与勾选语义互不干扰：勾选题仍是「重下」；状态只作展示。

### 4.6 文案与 Manifest

- `strings.xml`（`module_book`）新增：瞄准镜描述（两态）、`正在下载 第 %1$d 章 · %2$s`、二级标题（书名 + 选章）、`已跳过 %1$d 章（已在下载队列）`、二级空态等；确定/取消复用现有资源（落地时 grep 现数）。
- **双 Manifest 无改动**：`DownloadManageActivity` 已注册在 `src/main/AndroidManifest.xml` 与 `src/main/module/AndroidManifest.xml`，无新增 Activity；阅读器与其同模块，普通 `startActivity` 即可，无需 TheRouter。

### 4.7 文档

- **ADR-0034 就地更新**：决策 1 的「切模式总是重新定位到当前章」改为「切顺序保持锚点（视口顶章节不变）+ 锁定独立开关（瞄准镜）」，标注修订日期；其余决策（倒序不落盘、分组、软上限、缓存不作判据）不变。
- **新增 ADR-0035「下载中心」**（正文自足，不交叉引用本仓其他 ADR）：选章与管理合一的两级结构；进度口径（「正在下载第 N 章 + 队列剩余」是下载进度，「全书已缓存 x/y」是覆盖率，不混）；「`download_chapter` 只存未完成章，已下载以缓存存在性判」；确认后留在二级；已排队章节跳过不重下。

## 5. 测试策略

- **新增 `chapterDownloadStatus` 纯逻辑单测**（JUnit4、反引号句子式方法名，放 `module_book/src/test/java/com/ebook/book/reader/`）：
  - 四态判定：未下载 / 已缓存 / 待下载 / 下载中；
  - 优先级：已缓存同时在队 → 待下载态；队内命中 active → 下载中；
  - 空输入：空目录、空缓存、空队列不崩溃。
- 抽屉锚点/锁定交互与二级视图属 Compose 层，仓内无 Compose 测试先例，交互正确性归 §6 人工项。
- **编译**：`./gradlew :module_book:testDebugUnitTest :module_book:assembleDebug`，无新 warning（AGENTS.md 要求提交保持警告清洁）。

## 6. 验证分工

- **Agent 侧**：§5 单测、编译、静态检查。
- **人工侧（Agent 未做，不得以「构建通过」暗示已验证）**：
  1. **抽屉锚点**：锁定态打开定位当前章；阅读中切倒序视口顶部仍是当前读的那章；浏览到远处（如 5000 章处）切顺序仍停在原章；切顺序后再次开关抽屉不再被拽回当前章。
  2. **瞄准镜**：点一下锁定（打开定位当前章）、再点取消（保持位置）；与正序/倒序互不干扰。
  3. **倒序首次进入**：锁定关闭后首次切倒序停在顶部（最新章）。
  4. **下载中心一级**：运行中显示「正在下载 第 N 章 · 章名」+ 队列剩余 + 全书已缓存 x/y（三者口径分明）；点卡进二级。
  5. **二级状态**：已缓存徽章 / 待下载 / 下载中高亮正确；分组、三态、快捷 chips、展开吸附不串字（v1 项回归）；确认后**留在二级**且新任务实时出现；勾选已在队中的章被跳过且有文案提示。
  6. **阅读器入口**：下载按钮直达该书二级；返回回一级；通知权限拒绝不影响下载。
  7. **双模式回归**：集成态（`assembleRealDebug`/`assembleMockDebug`）与独立态（`isModule=true`，调完改回 `false`）阅读器与下载中心全链路无异常。

## 7. 风险与遗留

- **锚点保持依赖 `listState.firstVisibleItemIndex`**：翻转发生在抽屉可见时，该值可靠；极端情况（列表未完成首次测量就被点顺序）下 `visibleItemsInfo` 可能为空，此时退化为不滚动（`pendingAnchor` 不记录），不报错。
- **已缓存 + 已排队同章的展示**：状态取队内优先（待下载/下载中），缓存徽章不再单独区分——用户勾中即重下，行为不二义。
- **确认时跳过已排队章节**：跳过量只作文案提示，不弹额外确认（软性约束，与唯一索引语义一致）。
- **阅读器入口不再在阅读器侧发下载**：通知权限申请位置迁移到下载中心，首次从阅读器进下载中心确认下载时申请一次；拒绝后仍可下载（仅无进度通知）。
- **一级覆盖率条保留**：它是全书缓存覆盖率（随阅读/下载单调增长），不是本次批次进度；文案已明确，若日后仍被误读，可考虑视觉再弱化或并入二级状态列表（本次不做）。