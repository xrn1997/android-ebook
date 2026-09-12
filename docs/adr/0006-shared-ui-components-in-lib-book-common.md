# 跨模块共享 Compose 组件收敛到 lib_book_common

module_me 重设计沉淀了一套「轻卡片 + 语义色 + Material typography」视觉语言，但组件留在模块内无法复用，导致 module_find 等模块仍沿用旧观感，App 内视觉割裂。决定把跨模块复用的 Compose 组件与设计常量统一归口 `lib_book_common` 的 `com.ebook.common.ui`（`CommonUiTokens` 设计常量 + 卡片/列表项/分组标题/标签/封面/头像等组件），原模块级组件库 `MeUiComponents.kt` 整体迁移并删除、不留兼容壳；module_find（书城/搜索/分类选书）按该设计语言重设计。

**收录判据**：组件语义属于本应用（书籍类 App 的卡片/标签/封面），不属于通用库 lib_common（领域无关、独立发版），故停在 `lib_book_common` 这一档；被 ≥2 个业务模块用到才上浮，只在单模块用的留在该模块。

**兜底用的默认图与其组件同处**：封面占位 `img_cover_default` 与默认头像 `img_avatar_default` 都住在 `lib_book_common/src/main/res`，业务模块不再各留一份副本——同名位资产的重复副本就是「改一处漏一处」的现成事故源。两张图一律放 `drawable-xxhdpi`（与该库既有资产同档）：为一张图另开一个只含它的密度目录，会凭空引入一对互相指认的 `IconDensities` 告警（放 `drawable-xhdpi` 时该库告警数从 1 条涨到 3 条，回到 `drawable-xxhdpi` 才复原）。

## 动机

- 书城页等仍是旧 View 布局 1:1 迁移的观感（硬编码字号、重阴影、实底色块）——组件留在模块内、module_find 无从复用的直接后果。
- `lib_book_common` 本就是「共享 UI 组件、工具类、基类」层，且已 `api` 暴露 Compose material3/foundation，是天然落点；同时它已有封面占位图 `rememberCoverPlaceholderPainter()`，封面组件顺带收敛。

## 权衡

- **共享组件仅限 material-icons-core 图标**：基础库体积由全部下游模块分担，引入 iconsExtended（数 MB）收益为零；约束固化在 `CommonUiComponents.kt` 文件头 KDoc，业务页需要扩展图标时由各自模块声明 `libs.androidx.compose.material.iconsExtended`。
- **lib_book_common 以 `api(libs.coil.kt.compose)` 暴露 Coil**：`BookCover`/`Avatar` 需要；与文件内既有大量 `api` 声明风格一致，业务模块本就各自直接使用 Coil，显式 api 暴露避免传递依赖断裂。
- **`BookCover` 的 contentScale 取 Crop 而非隐式 Fit**：Fit 下非 3:4 封面被拉伸变形；Crop 改为裁切填充，个别非常规比例封面观感微变，属有意修正。
- **`InfoChip` 以可配参数（shape/颜色/排版/内边距/行数）覆盖「小标签」与「胶囊」两类形态**，而非拆两个组件——两者结构完全同构，仅样式参数不同。边界：两类形态都是**展示型标签**（可附带点击），不含**可选中分段控件**与**全宽居中按钮型胶囊**——前者需要固定高度且内容垂直居中（如阅读器字号刻度），后者需要整宽命中区与水平居中（如下载面板的快捷选择胶囊），而 `InfoChip` 的内容默认顶对齐、文本左对齐、宽度随内容包裹，硬套会退化成"给它再加高度/铺满/对齐参数"，那已是另一种控件的职责，留在调用方自绘。
- **头像的三态取法收在 `Avatar` 内**：URL 为空直接给默认图（不发请求）、加载中给 `surfaceVariant` 中性色块、加载失败回落默认头像。加载中不给默认头像是因为头像内容必然是「人」，先闪一张陌生剪影再换成真人比色块突兀。手写版只判「URL 是否为空」，**URL 非空但取不到**（上传文件被删、CDN 失效、设备离线）时渲染成一个空白圆、默认头像永不登场——这是收口顺带修掉的缺陷。登录态判断与光环/描边一类装饰留在调用方（各只有一处使用者，收进参数等于把一个调用点的复杂度摊给所有调用点）。

## 下游影响

- 新增跨模块 UI 件先按上面的判据定归属，再落 `com.ebook.common.ui`；条目卡一律走 `CommonItemCard`、头像一律走 `Avatar`、封面一律走 `BookCover`，不在业务模块内手写重复容器。反过来，通用工具（与书籍领域无关、换个项目能原样复用的件）不进本包——它属于更外层的通用脚手架。
- `module_me`：页面与独立运行宿主改用共享组件（视觉零漂移），评论页内联章节 chip 改 `InfoChip`。协议类页面的文本不写进代码字符串，走 `res/raw/privacy_policy.txt`、`res/raw/user_agreement.txt` + 纯函数解析（`parseDocSections`），解析逻辑可单测。两份 Manifest（`src/main/AndroidManifest.xml` 与 `src/main/module/AndroidManifest.xml`）的 Activity 声明同步增删是模块级通则，不在此处复述。
- `module_find`：书城页、搜索页、分类选书页按共享语言重设计（卡片/胶囊/typography/语义色），特有动效（圆形揭示、抖动、粒子爆炸）不变。
- `module_book`：书架页、书籍详情、评论区、导入页按共享语言重设计（`TopAppBar` 文字标题顶栏 + 12dp 圆角条目卡 + `BookCover`/`InfoChip`/`CommonCard`/`SectionLabel` + typography）。顶栏两种形态并存且均为共享语言成员：**无操作项的页面用基类顶栏（居中标题，默认插槽即可）**；**带 actions 的页面（如书架页的导入/下载入口）自绘左对齐 `TopAppBar`**——基类顶栏无 actions 插槽，带操作项的页面必须自绘，两者视觉同源、不构成分裂。
