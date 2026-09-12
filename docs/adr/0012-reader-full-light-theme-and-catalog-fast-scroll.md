# 阅读界面整片豁免系统深色，保留目录快速滚动条

阅读器迁移到 Compose 后，需对"阅读界面豁免系统深色模式"作范围收口，同时决定是否保留原章节目录快速滚动条。决定整片豁免——正文用「阅读背景主题」（`ReadBookControl`），chrome 层（顶/底栏、章节目录、亮度/字体/设置面板、弹窗）在 `ReadBookActivity` 作用域内固定 `lightColorScheme`，均不随系统深色；同时保留快速滚动条的 Compose 移植。这样做既对齐原实现始终浅色的菜单，也让「豁免系统深色」这条约束的作用域与它的措辞一致（整片，而非只正文），且不放弃长篇小说章节列表的核心定位交互。

## 背景

迁移一度只豁免正文、chrome 层沿用 `MaterialTheme.colorScheme`，系统深色开启时菜单区深色、正文仍为读书主题浅色，偏离原实现且与整体豁免措辞不一致；代码评审据此提出，grill 会话收敛范围。

## 决策

1. **整片豁免系统深色**：正文用阅读背景主题（`ReadBookControl`），chrome 层在 `ReadBookActivity` 作用域内固定 `lightColorScheme`——对齐原实现的全白菜单，也让豁免的作用域与「阅读界面豁免系统深色」这条约束本身的措辞一致。

2. **保留目录快速滚动条**：原 `RecyclerViewBar` 移植为 Compose `ReaderFastScroll`——右侧可拖手柄按 y 比例 `scrollToItem` 定位长目录，拖动与列表滚动都显示并各自重启 1 秒无操作隐藏倒计时（fling 期间同样可见）。上千章节的目录纯 fling 翻找效率低，放弃等于可视功能降级。

## 被拒方案

- **仅正文体豁免、菜单/面板跟随系统深色**：偏离原实现全白菜单，且让同一条豁免出现两种口径。
- **目录快速滑块接受降级**（`LazyColumn` fling 可满足）：上千章节的目录纯 fling 翻找效率低，属可视功能降级。
- **快照菜单跟随系统深色并改写规格文档**：为免改动而放宽规格承诺，不可取。

## 下游影响

- 正文配色仍由 `ReadBookControl` 阅读背景主题独立控制，不受该浅色主题影响。
- 状态栏色随正文背景自适应（`setStatusBarColor(textBackground.detectColor())`），与 chrome 层的固定浅色互不干涉。
- 主题装配由基类经 `lib_common` 的 `AppTheme` 提供，AGENTS.md 的 Compose 约定因此规定子类不在 `PageContent` 里重复包裹 `MaterialTheme`。阅读器整片豁免系统深色必须有自己的主题作用域，故它是该规则被点名列出的唯一例外：`ReadBookActivity.PageContent` 内以 `MaterialTheme(colorScheme = ReaderLightColorScheme)` 固定浅色——内部所有 `colorScheme.*` 解析到固定浅色，不逐组件硬编码颜色。其余场景照旧禁止。

## 范围与连带约束

豁免与快速滚动条这两条决定带出若干阅读器特有的形态，各自都有真实取舍，改动前先看清理由：

- **目录载体是全高左侧滑入抽屉（`ChapterListDrawer`），不是底部弹层**：上千条目的目录在半屏 `ModalBottomSheet` 里浏览体验差，全高侧栏才容得下快速滚动条与长章名；它是自绘覆盖层，返回键处置统一收在 `ReadBookScreen` 的 BackHandler。
- **阅读器关掉基类的 insets 处理**（`enableFitsSystemWindows() = false`）：正文背景要一直铺到系统栏底下，因此顶/底栏与目录抽屉自行避让系统栏，且避让 padding 写在 `background` 的内层——写反了就会背景不延伸或内容被系统栏遮挡。
- **每次进入阅读器都重新应用已持久化的手动亮度**（`applyReaderBrightness`）：窗口亮度不跨 Activity 生命周期，不重应用就是"设置里存着 30%，进阅读器又变回满亮"。
- **顶栏返回箭头与硬件返回键语义不同**：箭头 = 退出阅读器（未加入书架时先弹确认），硬件返回键 = 走 BackHandler 处置链（菜单可见时先收菜单）。两者曾共用同一路径，结果是箭头被"关菜单"分支吃掉，只隐藏控制界面不退出。
- **章节/亮度滑条是自绘 `ReaderSlider`，不用 Material3 `Slider`**：M3 新版滑条的竖条手柄在这两条紧凑行里过重、与同行文字图标抢视线，且取值停在最小端时轨道另一端会露出端点圆点，读起来像控件坏了。手势语义与替换前一致（按下即定位、拖动实时回调、抬手取整跳章）；触点→数值映射固定以静止态旋钮尺寸为基准，否则按下放大瞬间数值会抖。
- **字号档位走点选胶囊，不走滑条**：每次改字号都要重排全文分页，拖动产生的连续回调代价不可接受。阅读背景主题的色卡需要展示主题名，故 `ReadBookControl.TextDrawable` 携带 `labelRes`——配色值本身表达不了"这是哪一档"。
