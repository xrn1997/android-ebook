# 阅读器长目录的浏览与选择形态（目录倒序 + 下载面板分组）

- **日期**：2026-09-12
- **状态**：设计定稿，待实施
- **范围**：`module_book` 阅读器 chrome 层（`reader` 包 + `ReadBookActivity` + `strings.xml`）。
  无 DB 迁移、无网络/书源改动、无跨模块改动。

## 1. 背景

**目录抽屉（`ChapterListDrawer`，`ReaderPanels.kt:736`）只有正序**：`chapters` 按 `durChapterIndex` 升序渲染，长连载书（数千章）的读者要翻到最新章只能靠右侧快速滚动条拖到底。目录倒序是同类阅读器的常见能力。

**下载面板（`ChapterDownloadSheet`，`ReaderPanels.kt:1553`）是单层平铺**：一次渲染全部章节行，面板高度固定为窗口高的 1/2（`ReaderPanels.kt:1629-1631`），3000 章的书要在这半屏里手滚找章。代码注释自认"大目录快速滚动可加，本面板以选择为目的、逐行可视更重要，不引入 FastScroll"（`ReaderPanels.kt:1627-1628`）——该缺口此前只是被搁置，本设计把它闭合。

**本设计的输入（2026-09-12 逐条确认）**：

1. 目录倒序：**仅本次会话有效**，不落盘（不进 `ReadBookControl`）。
2. 下载面板：**每 100 章一个分组**，组头可展开查看子项——用它替代给下载面板加倒序。
3. 组头语义：**勾选框 = 选中整组 100 章**（含已缓存章节，仍按现有 `forceRefresh` 重下），组内子项仍可单独勾。
4. 默认展开：**只展开含当前章的那一组，并把列表滚动到该组头**，确保用户上眼即见。
5. 章数护栏：**软上限 500 章**（= 5 组），超过则二次确认后照常下发。
6. 护栏**不以"是否已缓存"为判据**：缓存的章文件存在不等于内容正确（缓存失败也可能落盘），用户选了就下。
7. 倒序入口用**文字胶囊**（非图标按钮）。
8. 早先提出的两处列表小清理**随本次一并做**；决策沉淀为 ADR。

## 2. 目标与非目标

**目标**

- 目录抽屉支持正序/倒序切换，章号、当前章高亮、点击跳转、打开定位在两种模式下都正确。
- 下载面板的导航面从"章节数"降到"组数"（3000 章 → 30 行），批量选章的手势成本从数十次点击降到数次。
- 大批量误触（尤其含已缓存内容的重下）有一道明确的确认护栏。

**非目标**

- 不给下载面板加倒序（分组即为该问题的解）。
- 不改动下载任务的构建与下发链路（`startChapterDownload` 的排序、`forceRefresh`、先入库再拉服务一律不动）。
- 不给目录抽屉加章节号跳转输入框（快速滚动条已覆盖该需求）。
- 不改动 Room 实体、实体迁移、`getCachedChapterIndices` 的查询方式。
- 不为下载面板再引入第二套快速滚动条。

## 3. 现状链路（改前）

| 位置 | 职责 |
| --- | --- |
| `ReaderPanels.kt:736` `ChapterListDrawer` | 目录抽屉；`itemsIndexed(chapters, key = { index, _ -> index })`，`LaunchedEffect(visible)` 定位当前章 |
| `ReaderPanels.kt:868` `ChapterRow` | 目录行；每行 `clip(RoundedCornerShape(12.dp))` + `background`，`index + 1` 显示章号 |
| `ReaderPanels.kt:930` `ReaderFastScroll` | 目录快速滚动条；按位置比例 `scrollToItem`，位置映射与排序模式无关 |
| `ReaderPanels.kt:1553` `ChapterDownloadSheet` | 下载面板；`selected: Set<Int>`（原始章序号）、三个快捷 chip、半屏 `LazyColumn`、底部确认按钮 |
| `ReaderPanels.kt:1563-1565` | `uncachedIndices`：全仓唯一以"缓存存在性"为选择输入的地方 |
| `ReaderPanels.kt:1701` `DownloadChapterRow` | 下载面板行；序号 + 章名 + 已缓存徽章 |
| `ReadBookActivity.kt:591-602` | 面板异步参数快照：查 `getCachedChapterIndices`，预选 `durChapter..durChapter+50` 中的未缓存章，再开面板 |
| `ReadBookActivity.kt:884` `startChapterDownload` | `selected.sorted()` → 构建 `DownloadChapterEntity`（`forceRefresh = true`）→ 入库 + 拉服务 |
| `ReadBookActivity.kt:762` | `ChapterListDrawer` 调用点（传 `chapters` / `durChapter`） |

## 4. 设计

### 4.1 纯逻辑层：新增 `ChapterSelection.kt`

新建 `module_book/src/main/java/com/ebook/book/reader/ChapterSelection.kt`，零 Compose 依赖，承载本设计的全部可测语义（与同目录 `ChapterLayoutCache` / `SourceSwitchFeedback` 同一分工惯例）：

- `ChapterGroup(index, first, last, count)` 与 `chapterGroups(total, size = DEFAULT_GROUP_SIZE)`：`DEFAULT_GROUP_SIZE = 100`，末组不足一组时如实反映（3050 章 → 31 组，末组 50）。
- `GroupState`（`NONE` / `PARTIAL` / `ALL`）与 `groupState(group, selected)`；`toggleGroup(group, selected)`：`NONE`/`PARTIAL` → 整组加入，`ALL` → 整组移除。
- 倒序换算（两函数互逆）：`originalIndexAt(count, descending, position)`、`displayPositionOf(count, descending, originalIndex)`。
- 组头吸附/滚动定位用的 `rowIndexOfGroup(groups, expanded, groupIndex)`：**排在它前面的每组各占一行组头，已展开的组再多出它的章行**，故等于 `groupIndex + Σ(前置已展开组的 count)`。注意不能简化成"组序号 + 前置展开组数"——一个展开组贡献的是 100 行而非 1 行，那会让滚动目标差出上百行。
- `MAX_DOWNLOAD_SELECTION = 500`、`exceedsSelectionCap(size)`。

### 4.2 目录抽屉：会话内倒序

- **状态位置**：`var descending by remember { mutableStateOf(false) }` 声明在 `ChapterListDrawer` 函数顶层，**不能放进 `AnimatedVisibility` 内部**——放进去每次关抽屉就重置，用户来回开合会被打回正序。放顶层则关抽屉不丢、退出阅读器（组合销毁）自然回正序，正好是"仅本次会话有效"。
- **展示列表**：`remember(chapters, descending) { if (descending) chapters.asReversed() else chapters }`。`asReversed()` 是 O(1) 视图不拷贝，但**必须 `remember`**：每次重组都新建实例会让 LazyColumn 的 item provider 每帧换身份。
- **索引换算**：遍历显示位置 `position` 时取 `originalIndex = originalIndexAt(...)`，`name`、`isCurrent`、`onChapterClick` 一律用**原始索引**。于是倒序后首行仍显示"第 3000 章"（`R.string.chapter_number` 值即 `%d`），点击跳转语义零改动。
- **打开与切换定位**：`LaunchedEffect(visible, descending)` → 可见时 `scrollToItem(displayPositionOf(...))`。把 `descending` 放进 key 是必须的：切模式当刻不重新定位，用户会被甩到与所读章节无关的位置。
- **快速滚动条不动**：`ReaderFastScroll` 按位置比例映射，与排序模式正交。
- **入口**：抽屉标题行内、关闭按钮左侧一枚文字胶囊，显示**当前模式**（正序时弱化显示"正序"，倒序时主色高亮显示"倒序"），点击切换。显示当前态而非"点击后的动作"，避免"点了会变成什么"的歧义。
- **不落盘**：不写 `ReadBookControl`、不写 SP。

### 4.3 下载面板：100 章分组

```
┌──────────────────────────────────────────────┐
│ 离线下载                          已选 137 章 │
│ 共 3000 章 · 已缓存 421 章                    │
│ [ 全选 ] [ 仅未缓存 ] [ 清除 ]                 │
├──────────────────────────────────────────────┤
│ ☑  第 1-100 章      已缓存 12/100        ▴   │  ← 整行点击=展开/收起
│    ☑ 1 第一章   ☑ 2 第二章   ☐ 3 第三章      │  ← Checkbox=整组 100 章
│    ☑ 4 第四章   ...（复用现有行样式 + 徽章）  │
│ ☑  第 101-200 章    已缓存 5/100         ▸   │
│ ☐  第 201-300 章    已缓存 0/100         ▸   │
│ ...                                 共 30 组  │
├──────────────────────────────────────────────┤
│                [ 下载 137 章 ]                │
└──────────────────────────────────────────────┘
```

- **列表结构**：单个 `LazyColumn`，在 DSL 内 `groups.forEach { item/stickyHeader(组头) ; if (展开) items(区间)(子项) }`。**不做嵌套 `LazyColumn`**（无界高度约束会出问题），也**不预构造扁平列表**。
- **key 与 contentType**：组头 `"g$groupIndex"`、子项 `"c$原始章序号"`（主键唯一），并为两类指定不同 `contentType`，展开/收起时才能真正复用而非整段重建。**禁止用显示位置当 key**。
- **组头内容**：`第 %1$d-%2$d 章` + `已缓存 %1$d/%2$d` + 三态 Checkbox + 展开箭头。整行点击 = 展开/收起，Checkbox 独立响应（点整行不慎落到勾选框上就等于选中 100 章，必须分开）。
- **默认展开与定位**：新增入参 `focusIndex: Int`（调用侧传 `shelf.durChapter`）。打开时只展开含 `focusIndex` 的那一组，并 `scrollToItem(rowIndexOfGroup(...))` 把该组头滚到列表顶部。面板本身是"面板关闭即离开组合"的（`when (panel)` 分支），所以每次打开都是全新状态，定位是确定性的；`focusIndex` 越界时全部折叠且不滚动。
- **组头吸附**（**待确认默认值：要**）：用 `LazyListScope.stickyHeader` 把组头钉在可视区顶部。一组展开即 100 行，组头"第 101-200 章 · 已缓存 5/100"是滚动中唯一能表达"我在哪一组"的元素。**已核实：当前依赖（foundation 1.11.3）里 `stickyHeader` 已是稳定 API，无需 `@OptIn`**——早先担心的实验性 API 代价不存在。注意其 content lambda 接收一个 `Int`（该表头的全局 item 下标），写法为 `stickyHeader(key, contentType) { _ -> ... }`。
- **半屏高度限制保留**：分组后仍有 30+ 行，`listHeight = 窗口高 / 2` 的既有约束继续生效（它防的是 `ModalBottomSheet` 被内容无限撑开）。
- **已缓存计数**：组头计数与行内徽章都取自 `cachedIndices`（`DownloadSheetArgs` 的一次性快照），面板打开期间不变，无需实时刷新。

### 4.4 选择语义与 500 软上限

- `selected` 仍是 `Set<Int>`（原始章序号），组头只是新增一个批量入口：`toggleGroup` = 该组 100 个序号一起进/出。
- **缓存存在性只作展示，不进任何决策**：护栏不判缓存，勾选状态不因缓存变化。理由（用户给定）：缓存失败也可能落盘，`hasChapter` 为真代表"有文件"，不代表"内容对"，拿它当判据会得出错误结论。
- **软上限**：`selected.size > MAX_DOWNLOAD_SELECTION(500)` 时，点确认先弹一次 `AlertDialog`（写明"本次将下载 N 章，超过单次 500 章上限"），确认后照常 `onConfirm`；≤ 500 零摩擦。500 恰为 5 组，与分组粒度同拍。
- **下发链路完全不动**：`startChapterDownload` 的 `selected.sorted()`、`forceRefresh = true`、先入库再拉服务、启动被拒不丢任务等既有语义一律保持。
- **「仅未缓存」chip**（**待确认默认值：保留**）：它是全仓唯一以"缓存存在性"为选择输入的地方，用了它就永远修不到"文件在但内容错"的章——与本节口径存在张力。保留的理由是"一键补全还没下载的部分"仍是真实需求，且用户意图明确、结果在组头三态上立即可见。

### 4.5 随本次一并做的两处列表清理

两处都在本设计要改动的行上，且都是"省固定开销"性质，不改变任何行为：

1. **`ChapterRow` 的裁剪层只在当前章那一行建**：现在每行都是 `clip(RoundedCornerShape(12.dp))` + `background(...)`，非当前行的背景是 `Color.Transparent`——为一个不可见的圆角底给每一行都分配一个渲染层。把 `clip` + 圆角背景挪进 `isCurrent` 分支。
2. **目录抽屉去掉 `key = { index, _ -> index }`**（`ReaderPanels.kt:839`）：抽屉每行没有需要跨滚动保留的状态，而"按 index 作 key"与"缺省 key"在身份上是同一件事（缺省值即 `DefaultLazyLayoutKey(index)`，逐位唯一），去掉只少一层 keyFactory、行为不变。**注意**：下载面板的新列表不受此影响，它**必须**带 key（组头与子项两类）——那里展开时才插入子项，身份不能退化成位置。

**这两条不构成本设计对"疯狂滑动卡顿"的承诺**：列表每行计算本就是 O(1)，真正的 per-frame 主瓶颈（每行两个 `Text` 的排版、遮罩+抽屉的合成开销）未实测，定位手段见 §6。

### 4.6 代码落点

| 文件 | 动作 |
| --- | --- |
| `module_book/.../reader/ChapterSelection.kt` | **新增**：分组区间、三态、`toggleGroup`、倒序索引换算、`rowIndexOfGroup`、500 上限判定（见 §4.1） |
| `module_book/.../reader/ChapterDownloadSheet.kt` | **新增**：把 `ChapterDownloadSheet` / `DownloadChapterRow` / `QuickSelectChip` 从 `ReaderPanels.kt`（现 1785 行）搬出。属定向改进（该文件确实过大），不夹带其他重构。全仓调用点只有 `ReadBookActivity.kt:797` 一处；`SourceSwitchSheet.kt` 的 KDoc 链接 `[ChapterDownloadSheet]` 因同包而继续解析，无需改动 |
| `module_book/.../reader/ReaderPanels.kt` | 改 `ChapterListDrawer`（倒序状态、原始索引换算、定位重算、去掉 index key）与 `ChapterRow`（裁剪层收进 `isCurrent` 分支）；移出下载面板相关三个可组合函数 |
| `module_book/.../ReadBookActivity.kt` | `ChapterDownloadSheet` 调用处补 `focusIndex = shelf.durChapter`（`shelf` 已在该作用域可取） |
| `module_book/src/main/res/values/strings.xml` | 新增：目录顺序胶囊两态文案与切换描述、组范围、组缓存计数、上限弹窗正文；确定/取消复用现有资源（落地时 grep 现数） |

### 4.7 文档与 ADR

- **新增 ADR-0034「长目录的浏览与选择形态」**，内容自足（ADR 正文不交叉引用本仓其他 ADR）：
  - 目录倒序**仅会话内有效、不落盘**，以及该取舍（避免"开了倒序后来忘了，找不到最新章节"）。
  - 下载面板**以 100 章分组替代平铺**，以及为什么分组比给下载面板加倒序更贴合"按范围取章"的用途。
  - **500 章软上限**（确认后放行）而非硬截断。
  - **缓存存在性不作任何决策判据**（缓存失败也会落盘，"有文件"≠"内容对"）——这条最容易被后来的实现者忘掉。
  - 目录快速滚动条**不可降级为纯 fling**：上千章目录的纯滑动翻找效率低；该结论此前已定案，此处自足重述以免被"用倒序解决了导航"这个理由顺手推翻。
- **`AGENTS.md`**：其"实战建议"未述及阅读器目录形态，本次不改；若实施中发现有段落与新的选择语义冲突，就地更正（并在提交信息里点出）。

## 5. 测试策略

- **新增 `ChapterSelectionTest`**，放 `module_book/src/test/java/com/ebook/book/reader/`（与 `ChapterLayoutCacheTest` / `ReaderPagerControllerTest` / `SourceSwitchFeedbackTest` 同处），JUnit4、反引号句子式方法名：
  - 分组边界：整除（3000 → 30 组）、有余数（3050 → 31 组且末组 50）、不足一组（37 → 1 组）、0 章（空列表）。
  - 三态判定：全未选 `NONE`、部分 `PARTIAL`、整组 `ALL`。
  - `toggleGroup` 往返：`NONE → ALL → NONE`；`PARTIAL → ALL`（是补齐而不是清空——这是实现最容易写反的一处）。
  - 倒序换算互逆：多组 `(count, position)` 上 `originalIndexAt(displayPositionOf(i)) == i`。
  - `rowIndexOfGroup`：无展开组、前面有展开组两种情形。
  - 上限：500 放行、501 触发。
- **反向实验**：把 `PARTIAL → ALL` 临时写成清空、把上限边界改成 `>=` 之外的错误形式，确认对应用例变红后还原（仓内既有的"区分真锁住行为与恒真断言"手段）。
- **不做 Compose UI 测试**：`reader` 包现有测试全是纯逻辑，仓内无 Compose 测试先例；交互正确性归 §6 人工项。
- **编译**：`./gradlew :module_book:testDebugUnitTest :module_book:assembleDebug`。

## 6. 验证分工

- **Agent 侧**：上述单测与编译、静态检查。
- **人工侧（Agent 未做，不得以"构建通过"暗示已验证）**：
  1. **目录倒序**：切到倒序后首行是最新章且章号仍是绝对号（如"第 3000 章"），当前章高亮位置正确，点击跳章落在正确章节；切回正序位置不错乱；关闭抽屉再打开仍在倒序（会话内保持）；退出阅读器再进回到正序。
  2. **目录性能**：在 3000 章的书上疯狂滑动，抓 `adb shell dumpsys gfxinfo <pkg> framestats` 看掉帧分布——两处清理是否有效、主瓶颈是否另有其处，据此决定是否再投入。
  3. **下载面板默认态**：打开即只展开当前章所在组，且列表已滚到该组头（用户上眼可见）；当前章不在首组时尤其要确认滚动生效。
  4. **组交互**：点组头整行只展开/收起（不误选）；点 Checkbox 选中整组 100 章且组头三态正确；展开组滚动时组头吸附在顶部。
  5. **上限护栏**：选到 501 章点确认出现二次确认弹窗，确认后任务照常下发；选 500 章无弹窗。
  6. **任务落地**：确认后到下载管理页核对任务数与所选章数一致。
  7. **回归**：独立模式（`isModule=true`）下打开阅读器与下载面板无异常。

## 7. 风险与遗留

- **两处列表清理的收益未实测**：它们是确定的无用开销，但"疯狂滑动卡顿"的根因可能不在其中；本设计不承诺卡顿消除，定位手段见 §6-2。
- **依赖 API 已核实**（2026-09-12）：`stickyHeader` 在 foundation 1.11.3 中为稳定 API，`TriStateCheckbox` 在 material3 1.4.0 中签名为 `(state: ToggleableState, onClick: (() -> Unit)?)`；两者均无需 `@OptIn`。若后续升级 Compose 改签，以编译器提示为准。
- **500 是产品阈值**：改一个常量即可调整；当前 UI 只在超限时提示"上限 500"，面板上无常驻的"N/500"反馈——可选增强，本次不做。
- **倒序不落盘**：用户每次进阅读器都要重新切一次（明确选择的取舍，不是遗漏）。
- **「仅未缓存」与"缓存不作判据"并存**：保留即接受"坏缓存不会被它修到"；若将来该张力被判定不可接受，去掉它是纯删除（一个 chip + 一段 `uncachedIndices` 计算）。
- **`focusIndex` 越界**（章节列表为空或 `durChapter` 落在范围外）：全部折叠、不滚动，面板仍可用。
- **已缓存计数是打开面板前的快照**：下载进行中面板已关闭，不存在实时刷新需求；若将来要在面板内触发下载，此处需重新取值。
- **顺序胶囊的触控高度约 22dp**（`labelSmall` + 3dp 竖向内边距），低于 48dp 建议值，且与右侧关闭按钮相邻——误触会关掉抽屉（可恢复、无损状态）。不加大的原因是要与同行的"共 N 章"胶囊保持同一视觉尺寸；若日后被反馈误触，可在不改视觉的前提下单独扩大它的命中区。
- **切模式时"总是重新定位到当前章"是显式决策**：用户在浏览远处章节时切模式会被拽回当前章。若该行为被反馈为干扰，替代方案是翻转前记下顶部条目的原始章序号、翻转后定位到该序号——停在当前章时效果与现方案一致，浏览中则保留锚点。
