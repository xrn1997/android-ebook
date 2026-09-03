# 阅读界面整片豁免系统深色并保留目录快速滚动条

阅读器迁移到 Compose（ADR-0001）后，对"阅读界面豁免系统深色模式"作范围收口：**整片豁免**——正文用「阅读背景主题」（`ReadBookControl`），chrome 层（顶/底栏、章节目录、亮度/字体/设置面板、弹窗）在 `ReadBookActivity` 作用域内固定 `lightColorScheme`，均不随系统深色。这既对齐原实现的始终浅色菜单（原 `ll_menu_top`/`ll_menu_bottom` 固定 `#ffffff`），也忠于 ADR-0001 的"阅读界面豁免系统深色"表述。

触发背景：迁移一度只豁免正文、chrome 层沿用 `MaterialTheme.colorScheme`，系统深色开启时菜单区深色、正文仍为读书主题浅色，偏离原实现且与 ADR 整体措辞不一致；代码评审（Spec 轴）据此提出，grill 会话收敛范围。

**同时决定**：章节目录快速滚动条**保留移植**——原 `RecyclerViewBar` → Compose `ReaderFastScroll`（右侧可拖手柄按比例 `scrollToItem` 长目录定位、1 秒无操作自动隐藏），不放弃该长篇小说章节列表的核心定位交互，忠于"行为一比一移植"。

**已拒绝的选项**：
- 仅正文体豁免、菜单/面板跟随系统深色：偏离原实现全白菜单与 ADR 整体豁免表述
- 目录快速滑块接受降级（`LazyColumn` fling 可满足）：上千章节的目录纯 fling 翻找效率低，属可视功能降级
- 快照菜单跟随系统深色并改写 ADR：为免改动而放宽规格承诺，不可取

**实现说明（关键陷阱）**：仓库规则"子类不在 `PageContent` 重复包裹 MaterialTheme、禁止硬编码颜色"。阅读器是**已记载的豁免上下文**，故在 `ReadBookActivity.PageContent` 内以 `MaterialTheme(colorScheme = ReaderLightColorScheme)` 做**作用域固定浅色**——内部所有 `colorScheme.*` 解析到固定浅色，不逐组件硬编码颜色。这是对"不在 PageContent 包裹 MaterialTheme"规则的唯一、有文档依据的例外，豁免依据由本 ADR 单独记录（ADR-0001 下游影响同步表述）。

**下游影响**：
- ADR-0001 下游影响与落地状态同步表述（AGENTS.md 不再承载已知问题，豁免决策以本 ADR 为唯一权威记录）
- 正文配色仍由 `ReadBookControl` 阅读背景主题独立控制，不受该浅色主题影响
- 状态栏色随正文背景自适应逻辑（`setStatusBarColor`）保持不变

**落地状态**：已实现——`ReaderFastScroll` 接入章节目录抽屉；`ReadBookActivity` 作用域固定 `lightColorScheme`；同步 ADR-0001。同批代码质量整理（LightPanel 亮度改单一事实源、硬编码字符串资源化、`currentTextPaint` 去重）系可回退的重构，不作为架构决策单列。

**后续修订**（用户反馈驱动的 UI 收口，不改变本 ADR 的豁免/快速滚动条决策）：
- 目录载体由底部 `ModalBottomSheet`（`ChapterListSheet`）改回左侧滑入抽屉（`ChapterListDrawer`，对齐原 `ChapterListView` 侧滑面板）：长目录在半屏弹层内浏览体验差，全高侧栏更符合阅读器目录浏览习惯；`ReaderFastScroll` 随之接入抽屉，返回键处置收口到 `ReadBookScreen` 的 BackHandler
- edge-to-edge 避让：阅读器 `enableFitsSystemWindows=false`，顶/底栏与目录抽屉自行避让系统栏（避让 padding 写在 background 内层：背景延伸到系统栏后、内容不被遮挡）
- 亮度修复：复选框显式接线（此前点框体无反应）、取消"跟随系统"立即应用手动亮度、进入阅读器恢复已持久化的手动亮度（`applyReaderBrightness`）
- 返回语义区分：顶栏返回箭头 = 退出阅读器（未加入书架先弹确认）；硬件返回键仍走 BackHandler 处置链（菜单可见时先收菜单），不再共用同一入口（此前箭头走 dispatcher 被"关菜单"分支拦截，只隐藏控制界面不退出）
- chrome 层共享设计语言对齐（ADR-0006）：底栏四入口位图图标改 Material 矢量，亮度/字体/设置面板改 `CommonCard` 分组 + `SectionLabel` + typography，背景选中描边硬编码改 `colorScheme.primary`；均位于固定浅色作用域内用语义色，豁免决策不受影响
- 控制器（chrome 层）整轮视觉美化：用户反馈"这套 UI 很多地方不好看"，逐项重做顶栏/底栏/目录抽屉/三面板/下载面板/加书架弹窗与正文页加载·失败态。全部改动仍在固定浅色作用域内取 `colorScheme` 语义色、间距圆角取 `CommonUiTokens`，阅读器专属尺寸收口到 `ReaderChromeTokens`——**不新增硬编码颜色、不改动豁免与快速滚动条决策**。要点：
  - 顶栏：`surfaceContainer` 底色 + 下沿投影（替代扁平同色），标题居中并补书名副标题，"更多"菜单项补前置图标
  - 底栏：「上一章/下一章」由裸文字热区（不足最小可点击尺寸）改为 44dp 圆形图标按钮，新增章节进度文本行使拖动反馈跟手，四入口改 `secondaryContainer` 胶囊并以面板状态高亮当前打开项（`ReaderPanel` 枚举随之从 Activity 文件移到 `reader` 包，供底栏共用）
  - 滑条：章节滑条与亮度滑条统一改用自绘 `ReaderSlider`（4dp 轨道 + 圆旋钮 + 按下光晕，触点→数值映射以静止态旋钮尺寸为基准以免按下放大时数值抖动）——Material3 新版滑条的竖条手柄在紧凑行里过重、与同行文字图标抢视线，且取值处于最小端时轨道另一端露出端点圆点，读起来像控件坏了；手势语义保持替换前一致（按下即定位、拖动实时回调、抬手取整跳章）
  - 目录抽屉：右侧 28dp 大圆角 + `surfaceContainerLow`，条目改圆角行卡（序号列 + 当前章整行底色 + 主色圆点），标题栏补关闭按钮；快速滚动条改「常显轨道 + 按可视占比算高的滑块」（自动隐藏语义不变）
  - 亮度面板：补当前亮度百分比（跟随系统时显示"自动"），"跟随系统"从卡外裸行收进同一张卡的图标开关行并补说明
  - 字体面板：字号由「A-/数值/A+/默认」改 8 档等宽数字胶囊（恢复默认移到标题行），仍走点选不用滑条——每次变更需重排全文分页，拖动连续回调代价不可接受；阅读背景由纯色圆点改为「主题底色纸片 + 正文色预览字 + 主题名」色卡，`ReadBookControl.TextDrawable` 因此新增 `labelRes`（四档定名同步进 CONTEXT.md）
  - 设置面板：开关行补图标块与说明文案（交代音量键/点击区域的作用范围）
  - 下载面板：标题右侧补"已选 N 章"计数胶囊（列表限半屏，滚动后确认文案会脱离视野），快捷选择改等宽胶囊，行选中加底色，"已缓存"徽章复用共享 `InfoChip`
  - 加书架弹窗：补图标并区分主/次按钮形态（原两个 `TextButton` 并排易误触退出）
  - 正文页加载/失败态：补环形指示器与失败图标、重试改胶囊按钮，配色仍由正文色按透明度派生（该层属阅读背景主题，改用 Material 语义色会与四色正文打架）