# 跨模块共享 Compose 组件收敛到 lib_book_common

跨模块复用的 Compose 组件与设计常量统一归口 `lib_book_common` 的 `com.ebook.common.ui` 包：`CommonUiTokens`（圆角/间距设计常量）、`CommonCard`（容器卡，16dp）、`CommonItemCard`（条目卡，12dp）、`CommonListItem`、`CommonListDivider`、`SectionLabel`、`InfoChip`、`BookCover`（书籍封面）、`Avatar`（用户头像）。module_me 原模块级组件库 `MeUiComponents.kt` 整体迁移至此并删除，不留兼容壳；module_find（书城/搜索/分类选书）按该设计语言重设计。

**兜底用的默认图与其组件同处**：封面占位 `img_cover_default` 与默认头像 `img_avatar_default` 都住在 `lib_book_common/src/main/res`，业务模块不再各留一份副本——同名位资产的重复副本就是「改一处漏一处」的现成事故源。两张图一律放 `drawable-xxhdpi`（与该库既有资产同档）：为一张图另开一个只含它的密度目录，会凭空引入一对互相指认的 `IconDensities` 告警（实测：放 `drawable-xhdpi` 时该库 3 条密度告警，放 `drawable-xxhdpi` 时回到既有的 1 条）。

## 动机

- module_me 重设计时沉淀了「轻卡片 + 语义色 + Material typography」的视觉语言（`MeCard`/`MeListItem`/`MeListDivider`/`SectionLabel`），但组件留在模块内，module_find 等模块无法复用，书城页仍是旧 View 布局 1:1 迁移的观感（硬编码字号、重阴影、实底色块），App 内视觉割裂。
- 组件语义属于本应用（书籍类 App 的卡片/标签/封面），不属于通用库 `io.github.xrn1997:common`（仍在迭代、走迷你构建联动），故不上提到外部库。
- `lib_book_common` 本就是「共享 UI 组件、工具类、基类」层，且已 `api` 暴露 Compose material3/foundation，是天然落点；同时它已有封面占位图 `rememberCoverPlaceholderPainter()`，封面组件顺带收敛（`BookCover`）。

## 权衡

- **共享组件仅限 material-icons-core 图标**：基础库体积由全部下游模块分担，引入 iconsExtended（数 MB）收益为零；约束固化在 `CommonUiComponents.kt` 文件头 KDoc，业务页需要扩展图标时由各自模块声明 `libs.androidx.compose.material.iconsExtended`（module_me 已声明）。
- **lib_book_common 新增 `api(libs.coil.kt.compose)`**：`BookCover` 需要；与文件内既有大量 `api` 声明风格一致，业务模块本就各自直接使用 Coil，显式 api 暴露避免传递依赖断裂。
- **`BookCover` 的 contentScale 由隐式 Fit 改为 Crop**：旧实现未指定，非 3:4 封面被拉伸变形；Crop 改为裁切填充，个别非常规比例封面观感微变，属有意修正。
- **`InfoChip` 以可配参数（shape/颜色/排版/内边距/行数）覆盖「小标签」与「胶囊」两类形态**，而非拆两个组件——两者结构完全同构，仅样式参数不同。边界：两类形态都是**展示型标签**（可附带点击），不含**可选中分段控件**与**全宽居中按钮型胶囊**——前者需要固定高度且内容垂直居中（如阅读器字号刻度），后者需要整宽命中区与水平居中（如下载面板的快捷选择胶囊），而 `InfoChip` 的内容默认顶对齐、文本左对齐、宽度随内容包裹，硬套会退化成"给它再加高度/铺满/对齐参数"，那已是另一种控件的职责，留在调用方自绘。

## 下游影响

- `module_me`：`MeUiComponents.kt` 删除，页面 + 独立运行宿主改用共享组件（视觉零漂移）；`MyCommentActivity` 内联章节 chip 改用 `InfoChip`。该模块现有 **10 个页面**（`MePage` + 9 个 Activity：`Setting`/`CacheManage`/`About`/`Doc`/`Licenses`/`MyComment`/`ModifyInformation`/`ModifyNickname`/`ClipImage`，后增的 4 个页面在 `src/main/AndroidManifest.xml` 与 `src/main/module/AndroidManifest.xml` 中同步声明）。协议类页面的文本不写进代码字符串，走 `res/raw/privacy_policy.txt`、`res/raw/user_agreement.txt` + 纯函数解析（`parseDocSections`），解析逻辑可单测。
- `module_find`：书城页、搜索页、分类选书页按共享语言重设计（卡片/胶囊/typography/语义色），特有动效（圆形揭示、抖动、粒子爆炸）不变。
- `module_book`：书架页、书籍详情、评论区、导入页按共享语言重设计（`TopAppBar` 文字标题顶栏 + 12dp 圆角条目卡 + `BookCover`/`InfoChip`/`CommonCard`/`SectionLabel` + typography）。顶栏两种形态并存且均为共享语言成员：**详情页、评论区经 lib_common 基类 `CenterAlignedTopAppBar`（居中标题，无 actions，默认插槽即可）**；**书架页、导入页因带 actions（导入/下载、加入书架按钮）自绘左对齐 `TopAppBar`**——基类顶栏无 actions 插槽，带操作项的页面必须自绘，两者视觉同源、不构成分裂。阅读器整页豁免系统深色（页面内固定浅色作用域）的约定不变，但 chrome 层已完成共享语言对齐：底栏四入口改 Material 矢量图标，亮度/字体/设置三面板改 `CommonCard` 分组 + `SectionLabel` + typography，背景选中描边硬编码 #F3B63F 改 `colorScheme.primary`（浅色作用域内语义色自动兼容豁免主题）。
- **12dp 条目卡收口**：原先散落在各模块手写的重复条目卡（书架条目、搜索结果条目、评论条目、本地文件条目）统一由 `CommonItemCard` 承载，四处调用点为 `module_find/src/main/java/com/ebook/find/view/SearchBookItem.kt`、`module_book/src/main/java/com/ebook/book/page/BookShelfPage.kt`、`module_book/src/main/java/com/ebook/book/BookCommentsActivity.kt`、`module_book/src/main/java/com/ebook/book/ImportBookActivity.kt`；新增条目卡一律走该组件，不再在模块内手写容器。
- **头像收口（2026-09-06 架构评审轮）**：三处各自手写的头像（「我的」页 72dp、编辑资料行 36dp、评论区条目 30dp）统一改调 `Avatar`；`module_me` 与 `module_book` 里两份字节相同的 `image_default.jpeg` 合并为 `lib_book_common` 的 `img_avatar_default.jpeg`，独立运行的调试宿主也改走该组件。顺带修掉的缺陷：手写版的回退只判 URL 是否为空，**URL 非空但取不到**（上传文件被删、CDN 失效、设备离线）时渲染成一个空白圆、默认头像永不登场。三态取法：空 URL 直接默认图（不发请求）、加载中 `surfaceVariant` 中性色块、失败回落默认头像——加载中不给默认头像是因为头像内容必然是「人」，先闪一张陌生剪影再换成真人比色块突兀。新增头像展示一律走 `Avatar`；登录态判断与光环/描边一类装饰留在调用方（各只有一处使用者，收进参数等于把一个调用点的复杂度摊给所有调用点）。
