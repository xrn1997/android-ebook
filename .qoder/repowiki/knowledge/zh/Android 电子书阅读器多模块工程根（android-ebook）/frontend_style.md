## 系统概述

本项目使用 **Jetpack Compose + Material Design 3** 作为唯一的 UI 框架，全仓页面均基于 `MaterialTheme.colorScheme` / `MaterialTheme.typography` 构建。主题配置不在本仓自行定义，而是通过 lib_common 的 `AppTheme.install { ... }` 装配点注入 `MyApplicationTheme`（深浅色 + Android 12+ 动态取色），各业务模块零耦合主题源，品牌色策略可在 `BookApplication` 一处切换。

共享视觉语言集中在 `lib_book_common/src/main/java/com/ebook/common/ui/`：统一卡片、列表项、信息标签、封面等基础组件，并通过 `CommonUiTokens` 对象集中管理圆角、间距等「单一事实来源」的设计常量，避免各模块出现风格漂移。图标仅允许使用 `material-icons-core` 核心集，扩展图标由各业务模块自行声明依赖以控制体积。

## 关键文件与包

- `lib_book_common/.../ui/CommonUiTokens.kt`：设计常量对象（`cardCorner=16.dp`、`cardCornerSmall=12.dp`、`chipCorner=4.dp`、`coverCorner=10.dp`、`pagePadding=16.dp`、`sectionSpacing=12.dp`、`listSpacing=8.dp`、`dividerIndent=64.dp`）。
- `lib_book_common/.../ui/CommonUiComponents.kt`：跨模块共享 Composable —— `CommonCard`（分组容器，16dp 圆角 + surfaceContainer）、`CommonItemCard`（条目卡，12dp 圆角）、`CommonListItem`（彩色图标列表项，36dp 圆角图标容器）、`CommonListDivider`、`SectionLabel`、`InfoChip`。
- `lib_book_common/.../ui/BookCover.kt`、`CommonPainters.kt`：书籍封面绘制与通用 painter。
- `lib_book_common/.../BookApplication.kt`：主题装配点入口（`AppTheme.install { MyApplicationTheme(content = content) }`）。
- `module_main/.../SplashActivity.kt`：启动页示例，展示如何通过 `AppTheme.Content`（而非裸 `MaterialTheme`）获得与基类一致的 App 级主题。
- 各功能模块 Activity/Page（`module_me`、`module_book`、`module_find`、`module_login` 中各类页）作为 Consumer，遵循以下约定。

## 架构与设计约定

1. **主题由单一装配点提供**：所有非阅读器的屏幕必须经 `BaseActivity`（Compose 版）或 `AppTheme.Content` 包裹，禁止在页面内重复包裹 `MaterialTheme`（阅读器整片豁免系统深色是已知例外）。基类负责状态栏 insets、加载覆盖层、Toolbar。
2. **颜色必须走语义色通道**：严格通过 `MaterialTheme.colorScheme.*` 取值（如 `primaryContainer`、`onPrimaryContainer`、`errorContainer`、`background`、`onSurfaceVariant`、`inverseOnSurface` 等），禁止硬编码 Color 常量。业务页面以 ADR-0006 沉淀的「轻卡片 + 语义色 + Material typography」视觉语言为准。
3. **Typography 走 Material 字体阶梯**：标题用 `titleLarge/titleMedium`，正文用 `bodyLarge/bodyMedium/bodySmall`，副文本/描述性文字用 `labelMedium`/`labelSmall`；字号不在页面内手写 dp 值。
4. **共享组件库**：`lib_book_common` 中的 `CommonCard`、`CommonItemCard`、`CommonListItem`、`InfoChip`、`BookCover` 是所有模块复用的基础视觉单元，新增相似形态应优先复用或在此处抽象，而非新建。
5. **设计令牌集中化**：圆角（`cardCorner`/`cardCornerSmall`/`chipCorner`/`coverCorner`）、间距（`pagePadding`/`sectionSpacing`/`listSpacing`）、缩进（`dividerIndent`）等统一取自 `CommonUiTokens`，模块内不可再写同语义的魔法数值。
6. **图标约束**：基础库仅依赖 `material-icons-core` 核心集（如 `Icons.AutoMirrored.Filled.KeyboardArrowRight`），业务模块如需扩展图标自行声明依赖。
7. **预览与测试宿主**：独立模式下的 `src/main/test/debug/*` 测试宿主也必须经 `AppTheme.Content` / `MyApplicationTheme` 包裹，否则 `@Preview` 会显示与正式运行分裂的固定浅色 baseline 配色。

## 约束与规则

- **禁止硬编码颜色**：代码评审约束要求“修改 Compose 页面时遵循 Material Theme 语义色，禁止硬编码颜色”，阅读界面背景主题除外（用于强制浅色阅读环境）。
- **禁止裸 MaterialTheme**：继承 `BaseActivity` 的页面不得在 `PageContent` 里重新包裹 `MaterialTheme`，唯一例外是阅读器整页的浅色豁免（见 ADR-0012）。
- **无 XML/res 布局遗留**：项目概述明确 ViewBinding/XML 已移除，不再存在传统 `<color>`/`<dimen>`/`<style>` 资源；所有视觉样式位于 Kotlin Compose 层。
- **无 CSS/Tailwind/第三方 UI 库**：仓库未见 `*.css`、`tailwind.config.*`、自定义样式主题 XML，UI 完全通过 Material3 Compose 语义 API 驱动。
- **图标来源限定**：文件中注释明确要求本库只允许 core 图标，扩展图标由业务模块各自引入，以保证共享库体积可控。