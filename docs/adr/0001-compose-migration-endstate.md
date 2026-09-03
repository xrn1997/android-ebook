# Compose 迁移终态：全部页面迁移（含阅读器）

决定将所有 Activity/Fragment（含最复杂的阅读器 ReadBookActivity）全部迁移到 Jetpack Compose，最终移除 ViewBinding 与 XML 布局。阅读器采用分阶段路径：先 AndroidView 嵌入 Compose 外壳，再逐个迁移自定义 View（ContentSwitchView、BookContentView、ChapterListView 等），最后移除 ViewBinding。

这是延续架构改进计划既定终态的决定（规格「未完成 #2：所有 Activity/Fragment 迁移完成后移除 ViewBinding」）：虽然阅读器翻页性能依赖成熟的 View 体系且自定义 View 众多、迁移风险最高，但统一 UI 技术栈的目标明确；核心功能回归风险通过分阶段过渡控制，每个中间状态可发布、可回退。

**已拒绝的选项**：
- 阅读器永久保持 View（AndroidView 嵌入外壳）：性能虽稳但长期保留两套 UI 体系，且阅读器是迁移中最复杂、迁移收益最高的页面
- 就地停止（当前混合状态即终态）：失去统一 UI 技术栈的目标

**下游影响**：阅读器迁移完成后才能执行"移除 ViewBinding 配置"；阅读界面豁免系统深色模式——**整片豁免**（正文阅读背景主题 + 顶/底栏、面板、弹窗均固定浅色，不随系统深色，见 CONTEXT.md「阅读背景主题」），其余界面遵循 Material Design 3 深色适配。

**落地状态**：已全部完成——书评页/导入页/阅读器（含翻页状态机、章节目录、亮度/字体/设置面板）均迁移为 Compose（阅读器实现见 `module_book/reader/`：ReaderPager 三页窗口状态机 + ReaderPanels），module_book 的 `viewBinding` 已关闭，自定义 View/PopupWindow/XML 布局已删除。原计划中的 AndroidView 过渡阶段未采用（直接全量重写，行为一比一移植）。行为对齐的补充说明：章节目录保留快速滚动条（原 RecyclerViewBar → ReaderFastScroll，长目录快速定位）；阅读器 chrome 层在 ReadBookActivity 作用域内固定 `lightColorScheme` 整片豁免系统深色，正文由 `ReadBookControl` 阅读背景主题控制。范围与实现细节见 ADR-0012。
