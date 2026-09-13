# 阅读器翻页方式选择：新增上下滚屏模式 — 设计

状态：**已实施（2026-09-13）**，但 §6「单章滚动 + 章末 footer，不做跨章无缝」已被同日的后续决策撤销——
滚屏列表改成**跨章连续**（章段 = 标题项 + 块项，进入某章即物化相邻两章，章界只留标题项），
「上一章/下一章」链接项与 `prev_chapter_format`/`next_chapter_format` 两条字符串已删除；
连带 §7 的「保留集裁剪」也改成两端都做（翻页按三页窗口、滚屏按章距 ±2）。
本文其余部分（§4 块内不带标题与页码、§5 块高取排版实测、§7 抽出 `ReaderPageStore`、
§8 按行号换算落点、§9 设置项与持久化、§10 输入语义）仍然成立。
现行事实源：`docs/adr/0037-reader-turn-mode.md` 与 `ReaderScrollController` 类 KDoc。
日期：2026-09-13
模块：`module_book`（阅读器）

## 1. 背景与目标

阅读器目前只有一种翻页方式：横向拖拽的三页窗口（`reader/ReaderPager.kt`）。
「更多设置」面板里那一节虽然已经叫「翻页方式」（`reader_section_turn`），
装着的却是两个开关（音量键翻页 / 点击翻页），并没有「方式」本身的选择。

目标：在阅读器里增加翻页方式选择，新增**上下滚屏**一档——正文竖向连续滚动，
手指滚到哪读到哪，而不是左右一屏一屏地翻。

非目标见 §11。

## 2. 现状（实施前必读的锚点）

分块链（与翻页方向无关，是本设计能低成本落地的原因）：

- `reader/ReaderTypesetter.kt:52` `lineStartOffsets(text, widthPx)` —
  用渲染引擎自身的断行结果给出整章每一「渲染行」在原文中的起始偏移
- `reader/ReaderTypesetter.kt:65` `fitRenderLineCount(widthPx, heightPx)` —
  正文区高度放得下几行
- `ReadBookActivity.kt:236` `rePaginate()` — 算出 `viewModel.pageLineCount`
- `ReadBookActivity.kt:267` `loadPage(chapterIndex, pageIndex)` —
  哨兵页码解析 + 按 `pageLineCount` 切出原文的连续子串，产出 `ReaderPageUi.Loaded`
- `reader/ChapterLayoutCache.kt` — 同章翻页不重复整章重排

容器与状态机（横向专属）：

- `reader/ReaderPager.kt:112` `ReaderPagerController` — 三页窗口状态机：
  `ensureLoad` 的 job 去重、`refreshWindow` 的前后页推算、`commitNext`/`commitPrev`
  的窗口收敛规则、`prune` 的窗口外清理，外加横向 `Animatable drag`、
  `pageWidthPx`、30dp 成功阈值、`isMoving` 动画锁
- `reader/ReaderPager.kt:360` `ReaderPager` — `awaitEachGesture` 横向手势 +
  左右三分区点击翻页 + 三个 `ReaderPageSlot`（布局期 `offset` 位移）
- `reader/ReaderPager.kt:530` `ReaderPageCard` — 单页三态卡片：
  章节标题 + 正文 + 30dp 常驻页码行（`ReaderPageTokens.pageNumberRowHeight`）

进度与设置：

- 进度 = `(durChapter, durChapterPage)`，落在 `book_shelf.dur_chapter_page`（INTEGER）；
  `mvvm/viewmodel/BookReadViewModel.kt:24` `updateProgress`、:31 `saveProgress`。
  `durChapterPage` 可取哨兵 `DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN/END`
- 设置 = `view/ReadBookControl.kt` 单例，SP 文件 `"CONFIG"`，内存缓存 + SP 持久化；
  既有键 `textKindIndex` / `textDrawableIndex` / `canClickTurn` / `canKeyTurn`
- 面板 = `reader/ReaderPanels.kt:1601` `MoreSettingPanel`，
  行组件 `PanelSwitchRow`（:1187，private），尺寸口径 `ReaderChromeTokens`（:140，private）

## 3. 核心决策：两种模式共用同一条分块链

§2 的分块链回答的问题其实只是「第 N 屏的正文是原文的哪一段」。它与翻页方向无关，
因此**两种模式共用**，差别只在承载容器：

| | 左右翻页（现状） | 上下滚屏（新增） |
| --- | --- | --- |
| 容器 | 3 屏窗口 + 横向 `Animatable` 拖拽 | 整章屏块的 `LazyColumn`，竖向自由滚动 |
| 可见块数 | 1（另 2 块在窗口里待命） | 视口内的 1~2 块（滚动中跨块） |
| 块高 | 铺满正文区 | 恰好 `pageLineCount` 行的实测高度（见 §5） |
| 进度 | `(durChapter, durChapterPage)` | 同左，**语义不变** |

由此得到三条直接收益，它们是本设计的主要理由：

1. **零 schema 改动、零迁移**。`dur_chapter_page` 仍是「章内第几屏」，
   不必为滚屏另造一套偏移量字段（AGENTS.md：改实体必须接迁移链，能不动就不动）
2. **`loadPage` / `ReaderPageUi` / `ChapterLayoutCache` / `pageLineCount` 全部复用**，
   不出现第二套「切几行」的判定——「分页与渲染必须同源」这条既有契约
   （`ReaderTypesetter` 类 KDoc、`readerBodyTextStyle` KDoc）原样成立
3. **模式切换的落点可换算**（§8），不会因为换了方式就把读者扔到章首

## 4. 决策 ①：滚屏模式的块内不带标题与页码行

`ReaderPageCard` 每页画「章节标题 + 10dp 间距 + 正文 + 30dp 页码行」。
直接拿它当 `LazyColumn` 的 item 会有两个可见缺陷：章节标题每屏重复一遍；
相邻两块正文之间出现约「标题高 + 10dp + 30dp」的纸张色空带。

**决定**：滚屏模式的块**只含正文**，章节标题与位置指示各自另置：

```
Column(fillMaxSize)
  .windowInsetsPadding(WindowInsets.systemBars)      // 与 ReaderPageCard 同一口径
  .padding(start = 15.dp, top = 5.dp, end = 15.dp, bottom = 5.dp)
  ├─ LazyColumn(Modifier.weight(1f))                 // ← 视口，onSizeChanged 回报宽高
  │    ├─ item: 章首「上一章」header（见 §6，与章末 footer 对称）
  │    ├─ item: 章节标题 header（每章只画一次，随内容滚走）
  │    ├─ items(blockCount): 正文块，高 = blockHeightPx
  │    └─ item: 章末「下一章」footer（见 §6）
  └─ Box(height = ReaderPageTokens.pageNumberRowHeight)  // 位置指示，常驻不滚动
```

前导固定 **2 个** item（上一章 + 章节标题），块序号与 `firstVisibleItemIndex`
之间恒差 2——§7 的进度换算依赖这个常数，改动前导 item 数量必须同步改那里。

位置指示行**留在滚动区之外**（不是覆盖层），因此正文永远不会滚到它底下被压住——
覆盖层方案要么遮住最后一行，要么得做半透明/自动隐藏，都是额外的取舍。
文案复用现有 `page_indicator_format`（`%1$d/%2$d`），语义为「本章第 x/y 屏」。

**代价（明确接受）**：滚屏模式的正文视口比翻页模式高出一个「标题行 + 10dp」
（标题变成了会滚走的 header），因此两模式的 `pageLineCount` 不同。
落点换算见 §8。

被否掉的替代方案：让滚屏块保留与翻页块完全相同的 chrome 高度（标题行与页码行
只占位不绘制），这样 `pageLineCount` 两模式一致、切换无需换算——但代价是每屏之间
一条约 70dp 的空带，比换算逻辑显眼得多。

## 5. 块高必须由排版器实测给出，不能用 `lineCount × lineHeight` 心算

`fitRenderLineCount` 返回的是「实测底部位置不越界的最大行数」，
因此 `pageLineCount` 行的实测总高 ≤ 视口高，差值小于一个行高。
若块高直接取视口高，块内会多出不到一行的空白；若用 `pageLineCount × lineHeight`
（sp 换算 px）心算，就又一次拿度量去猜渲染几何——正是 `fitRenderLineCount` KDoc
里记着的那条老病根（每行差零点几像素、25 行累计近 20px）。

**决定**：给 `ReaderTypesetter` 增一个一次测量同时给出两者的入口：

```kotlin
/** 一次测量同时给出「视口放得下几行」与「这几行的实测总高」。 */
fun measureBlock(widthPx: Int, viewportHeightPx: Int): ReaderBlockMetrics?

data class ReaderBlockMetrics(val lineCount: Int, val heightPx: Int)
```

`heightPx` 取 `layout.getLineBottom(lineCount - 1)`（探针文本，与 `fitRenderLineCount`
同一把尺）。块高 = `heightPx`，于是块与块**精确无缝拼接**，滚动中不会出现空隙或叠行。
`fitRenderLineCount` 保留（翻页模式仍只需行数），二者共用同一份探针与 `measure()`。

## 6. 决策 ②：单章滚动 + 章末 footer + 预取，不做跨章无缝

**决定**：`LazyColumn` 只承载**当前章**的块。

- 章末一个 footer item，内容为「下一章：<章名>」；滚进视口或点一下即切到下一章并滚到顶
- 章首一个对称的 header item「上一章：<章名>」（位于章节标题之上，见 §4 布局）；
  滚进视口或点一下即切到上一章并滚到其末尾
- 章切换即重建该章的块列表（`setInitData` 语义），不做两章块列表的拼接
- 滚到倒数第 2 块时提前 `viewModel.loadChapter(next)` 预热（章正文已有内存缓存与
  `ChapterLayoutCache`，预热后切换基本无感）；章首方向对称预热上一章
- **整本书的首末**没有相邻章：第一章不画「上一章」header、最后一章不画「下一章」footer，
  与翻页模式 `prevKey`/`nextKey` 为 null 的判据同源（`ReaderPager.kt:209-216`），
  此时点击/按键翻页走 `no_prev_page` / `no_next_page` 提示（§10）

被否掉的替代方案：多章无限滚动的单一 `LazyColumn`。它要求 item 总数已知，
而每章的块数要先排版才知道；往前追加章节还会整体平移 item 索引与滚动位置
（需要额外的锚点补偿）。预取已能把接缝压到几乎无感，剩下的复杂度不划算。
**若用户要的是「一路滚下去永不断开」，这一条要改，其余设计不受影响。**

## 7. 决策 ③：抽出共享的 `ReaderPageStore`，两个前端各管一种容器

`ReaderPagerController` 里「加载去重 / jobs 登记与身份比对 / 三态 / prune」
是两种模式都需要的，「drag 轴 + prev/next 偏移 + pageWidthPx + 30dp 阈值 + 动画锁」
是横向专属的。往现有类里塞 mode 分支会把两套语义搅在一起——那个类的 KDoc
记着一整套三页窗口的竞态不变量（窗口三键互不相同、不得双向同时为空），
滚屏模式下这些不变量根本不成立，混在一个类里两边都变难读。

**决定**：

```
ReaderPageStore            // 新增：key → ReaderPageUi、jobs 去重、ensureLoad/reload、
                           //       按保留范围 prune（翻页保留 3 屏，滚屏保留可见 ±2 屏）
  ├─ ReaderPagerController // 收窄为：三页窗口 + 横向拖拽/动画（行为不变）
  └─ ReaderScrollController// 新增：章内块列表 + 滚动落点 + 章末接章 + 进度回报
```

硬约束：**现有 `ReaderPagerControllerTest` 的 7 个用例必须原样通过**。
它们锁的是窗口收敛规则与「已 Loaded 的页不重抓」，属于 `ReaderPageStore` +
窗口前端的联合行为；抽取过程中若某个用例需要改，说明翻页模式的既有行为被改动了，
那是回归而不是重构。

`ReaderScrollController` 的进度口径：`firstVisibleItemIndex` 减去**前导 item 数**
映射回块序号。前导 item 数不是常量：中间章是 2（上一章 + 章节标题），
第一章只有 1（无上一章，见 §6）。故由控制器持有「本章前导 item 数」一处算出，
块渲染侧按同一个值排布 item，两侧不得各算一次——算错的表现是进度整体偏移一屏，
恢复时停在上一屏，不崩不报错，很难发现。
上报经与翻页模式同一个 `onProgress(chapterIndex, pageIndex)` 回调，落库路径完全共用。

## 8. 模式切换的落点换算

切换发生在阅读器内（设置面板），此刻两个 `pageLineCount` 都能拿到：

```
lineOffset = oldPageIndex × oldLineCount          // 当前读到的行号（近似，取该屏首行）
newPageIndex = lineOffset / newLineCount          // 新模式下含该行的屏
```

随后按新模式重启容器并滚到 `newPageIndex`。换算点只此一处（切换回调），
不在冷启动路径上——冷启动时 `durChapterPage` 与已持久化的模式天然同口径。

时序：改 `ReadBookControl` → 自增页面级 mode 版本号 → 重组 →
新容器回报新的视口尺寸 → `rePaginate` 按新视口算 `pageLineCount` → 换算并定位。
这与既有「字号变化由 `LaunchedEffect(typesetter)` 接力、不在面板回调里直接重分页」
是同一条时序纪律（`ReadBookActivity.kt:535-540` 的注释即此意）：
新样式/新视口要等重组后才成形，在回调里立刻重算会拿旧尺寸量新布局。

## 9. 设置项与持久化

`ReadBookControl` 增加：

```kotlin
/** 翻页方式列表（顺序即设置面板的展示顺序，index 落 SP） */
private val turnModeList = listOf(TurnMode.PAGE, TurnMode.SCROLL)

var turnModeIndex: Int        // 读取时越界回落 DEFAULT_TURN_MODE = 0
val turnMode: TurnMode        // = turnModeList[turnModeIndex]

fun updateTurnModeIndex(index: Int)   // 越界直接 return，与 updateTextKindIndex 同口径
```

- SP 键 `"turnModeIndex"`，文件仍是 `"CONFIG"`
- **默认 0 = 左右翻页**：老用户升级后行为不变，不会一觉醒来变成滚屏
- 存 Int 索引而不是枚举 `name`，沿用本文件 `textKindIndex` / `textDrawableIndex`
  的既有写法（含「读取时越界回落默认」的防御）；`TurnMode` 枚举只活在内存里
- 与 `textKindIndex` 一样是**全局**设置，不按书记忆

面板：`MoreSettingPanel` 的「翻页方式」一节里，在现有两个开关的 `CommonCard`
**之上**再加一个 `CommonCard`，装两行单选（左右翻页 / 上下滚屏）。
新增 private `PanelChoiceRow`，与 `PanelSwitchRow` 同一套尺寸口径
（`ReaderChromeTokens.iconBox` / `iconGap` / `switchRowPadding`）与同样的
「图标块 + 标题 + 说明 + 尾部控件」结构，尾部换成 `RadioButton`
（两档互斥，用 Switch 会读成「开/关某个功能」而不是「二选一」）。

不改 `reader_section_turn` 的既有文案（已定案的「翻页方式」正好是这一节的标题），
只新增：`turn_mode_page`（左右翻页）、`turn_mode_scroll`（上下滚屏）
及各自的 `_desc` 说明、`next_chapter_prefix`（章末 footer 文案）。

即时回调：仿现有 `onClickTurnChanged` → `clickTurnEnabled` 镜像那套，
`MoreSettingPanel` 增 `onTurnModeChanged: (Int) -> Unit`，`ReadBookScreen`
把模式镜像成页面级 Compose State。理由与原注释一致：`ReadBookControl` 的属性
不是 Compose State，写入不触发重组，不能让「换模式是否生效」依赖 panel 变化恰好重组。

## 10. 手势、点击分区与音量键在滚屏模式下的语义

| 输入 | 左右翻页 | 上下滚屏 |
| --- | --- | --- |
| 拖拽 | 横向跟手 + 30dp 阈值判成败 | 竖向自由滚动（`LazyColumn` 自带惯性/fling） |
| 点击左/右三分区（`canClickTurn` 开） | 上一屏 / 下一屏 | 上滚一屏 / 下滚一屏（`animateScrollBy ∓blockHeightPx`） |
| 点击中间三分区 | 唤出菜单 | 唤出菜单（不变） |
| 音量上/下（`canKeyTurn` 开） | 上一屏 / 下一屏 | 上滚一屏 / 下滚一屏 |

「一屏」= 一个块 = `blockHeightPx`，与滚动手势的粒度一致。
两个开关的含义不变（仍是「点击/按键能不能翻页」），只是滚屏模式下动作变成滚动一屏。
`ReadBookActivity.onKeyDown/onKeyUp`（:342/:352）改为按当前模式分派到对应控制器。

章边界上的点击/按键与滚动**语义一致**：在最后一屏按「下一屏」= 切到下一章章首
（等价于点章末 footer），在第一屏按「上一屏」= 切到上一章末尾。
只有整本书的首末才弹 `no_prev_page` / `no_next_page`（与翻页模式同判据，见 §6）。
即：点击/按键在滚屏模式下是「跨章连续」的，不会因为到了章末就停下来要用户去点 footer。

## 11. 错误与加载态

块的三态与翻页模式同源（都来自 `ReaderPageStore` 的 `ReaderPageUi`）：

- `Loading`：块位置画转圈 + 「加载中」，配色由正文色按透明度派生
  （正文层豁免深浅色切换，ADR-0012 口径，与 `ReaderPageCard` 一致）
- `Error`：块位置画云离线图标 + 「加载失败」+ 重试胶囊，点重试走 `store.reload(key)`
- 书源失效（`BookSourceNotFoundException`）仍由 `loadPage` 经 `viewModel.reportFailure`
  弹用户可见提示，滚屏模式不另开一条错误处置路径

因为块高在排版期就已确定（`blockHeightPx` 与内容无关），Loading/Error 块照样占一屏高，
滚动位置不会因某块加载失败而塌陷——这与 `ReaderPageCard`「正文区高度与页面状态无关」
的既有占位契约是同一个道理。

## 12. 明确不做（YAGNI）

- 仿真翻页 / 覆盖翻页 / 无动画翻页等其它翻页动画
- 「竖向整页翻」（保留分页、只把滑动方向换成上下的第三种容器）
- 跨章无缝的无限滚动（见 §6）
- 按书记忆翻页方式（全局一项，与字号/背景同口径）
- 滚屏模式下的自动滚动（朗读/挂机）

## 13. 测试策略

JVM 单测（Robolectric，沿用 `ReaderPagerControllerTest` 的 `FakeBook` +
`VirtualFrameClock` 手法）：

- `ReaderPageStoreTest`：job 去重与身份比对、已 Loaded 不重抓、`retain` 保留范围、
  失败态与 `reload` 重发、`clear` 让 Loaded 短路失效
- `ReaderBlockMetricsTest`：`fitLines` 纯函数（块高取实测行底部、一行都放不下返回 null）

（`ReadBookControl` 不写单测：它是 `object`，类初始化即读 `BaseApplication.context` 并调
`DisplayUtil.dp2px`，JVM/Robolectric 下拿不到应用图，测试会卡在单例初始化而不是被测逻辑上；
其越界回落是一行，且与已在线上的 `textKindIndex` 同写法。）
- `ReaderScrollControllerTest`：块总数由首个加载结果落定、哨兵落点解析、越界钳到章内、
  前导 item 数随首末章变化、章间衔接（末块下一屏 = 下一章章首 / 首块上一屏 = 上一章末尾）、
  整本书首末才提示「没有上一/下一页」、进度经与翻页模式同一回调上报、失败块不改变块总数
- `ReaderTurnModeRowRenderTest`：翻页方式单选行的图标块底色随深浅色调板翻转
  （探针 `ReaderRenderProbe` 与 `ReaderChromeThemeRenderTest` 共用）
- `ReaderPagerControllerTest`：**原样通过，一个用例都不改**（§7 的硬约束）

模式切换换算（`ReadBookActivity.convertPageIndex`）的边界（章首、章末、`newLineCount`
比 `oldLineCount` 大/小两侧）由上述控制器用例间接锁住，未单独建测试类。

渲染测（沿用 `ReaderChromeThemeRenderTest` 的既有 Compose 渲染回路）：
设置面板新增的「翻页方式」单选卡在浅色与深色下都能正确渲染并反映选中态。

## 14. 人工装机验证清单（Agent 止于编译 + 单测）

按 AGENTS.md 的分工，以下各项**未在设备上验证**，需人工确认：

1. 滚屏手感：竖向滚动顺滑、fling 正常、块与块之间无缝隙也无叠行
2. 章末 footer：滚到底出现「下一章」，接章后落在下一章章首；上拉回上一章落在其末尾
3. 预取是否真的让接章无感（未下载章节走网络时的表现）
4. 模式切换：左右翻页读到第 5 屏 → 切滚屏，落点是否在同一段字附近；切回来同样
5. 字号/背景/亮度在滚屏模式下照常生效，且改字号后重新分块、落点不丢
6. 点击左右三分区与音量键在滚屏模式下滚一屏；中间三分区唤菜单
7. 杀进程后重进，滚屏模式的进度恢复到上次那一屏
8. 深浅色切换：设置面板新增的单选卡随 chrome 层走深浅色，正文层不受影响
9. 两种模式的「没有上一页/没有下一页」提示分别在书首、书末出现
10. 独立模块模式（`isModule=true`）下阅读器能正常起来（两份 Manifest 口径未动，应无影响）

## 15. 影响面（文件级）

新增：

- `module_book/src/main/java/com/ebook/book/reader/ReaderPageStore.kt`
- `module_book/src/main/java/com/ebook/book/reader/ReaderScrollController.kt`
- `module_book/src/main/java/com/ebook/book/reader/ReaderScroll.kt`（滚屏容器 + 块渲染）
- `module_book/src/test/java/com/ebook/book/reader/ReaderPageStoreTest.kt`
- `module_book/src/test/java/com/ebook/book/reader/ReaderScrollControllerTest.kt`
- `module_book/src/test/java/com/ebook/book/reader/ReaderBlockMetricsTest.kt`
- `module_book/src/test/java/com/ebook/book/reader/ReaderRenderProbe.kt`（渲染取色探针，两条渲染测试共用）
- `module_book/src/test/java/com/ebook/book/reader/ReaderTurnModeRowRenderTest.kt`

修改：

- `reader/ReaderTypesetter.kt` — 增 `measureBlock` / `ReaderBlockMetrics`
- `reader/ReaderPager.kt` — `ReaderPagerController` 的加载部分让位给 `ReaderPageStore`
- `reader/ReaderPanels.kt` — `MoreSettingPanel` 增单选卡 + `PanelChoiceRow` + `onTurnModeChanged`
- `view/ReadBookControl.kt` — `TurnMode` / `turnModeIndex` / `updateTurnModeIndex`
- `ReadBookActivity.kt` — 按模式分派容器、按键分派、模式切换与落点换算
- `res/values/strings.xml` — 新增 §9 列出的字符串

不动：`lib_ebook_db`（零迁移）、`lib_book_common`、`BookReadViewModel` 的进度接口。

文档同步（AGENTS.md 要求）：若最终落地与 §4/§6 的取舍一致，`agents.md` 的
「MVVM 架构约定 → Compose 体系」一节关于阅读界面分层的描述需要补一句滚屏模式；
是否需要新开一篇 ADR 见下方判据。

## 16. 是否需要 ADR

按 `docs/adr/ADR-FORMAT.md` 的判据（难回退 / 无上下文令人惊讶 / 有真实权衡）：
**「两种模式共用分块链、进度仍按屏记」这一条值得开 ADR**——它无上下文会令人惊讶
（滚屏模式居然还保留「页」的概念，且 `dur_chapter_page` 在滚屏下是「第几屏」），
有真实权衡（§4 被否掉的「保留 chrome 高度换取零换算」、§6 被否掉的跨章无缝），
且一旦落地就难回退（进度语义已被两种模式共用）。
实施时新开一篇，编号取 `docs/adr/` 当前最大值 +1。
