# lib\_book\_common 与 lib\_common 的分界判定

2026-09-01 评审（grill 会话）确认：lib\_common（`io.github.xrn1997:common`，android-practice 通用脚手架）
与 lib\_book\_common（本项目专属 common，`com.ebook.common`）长期存在归属模糊——同一件能力可能被误放进任一模块，
或作为 Compose 迁移后残留**死代码**滞留在 lib\_book\_common。需一条可操作的判据界定「谁进谁的」。

## 决策

**lib\_common 归属判据**：一件能力，**换一个与「书籍/书城/评论」领域完全无关的 Android 项目，能否原样开箱复用**？

- **能** → 归属 lib\_common（通用脚手架，任何项目可复用）。

- **不能**（受 ebook 域耦合，或只对本仓库有价值）→ 归属 lib\_book\_common（项目专属 common）。

- **不能，且当前仅单一业务模块使用** → 归属**该业务模块**。lib\_book\_common 只收被 **≥2 个模块**共用的项目专属件，单模块件放共享库会让下游模块跟着编译它、也让「谁在用」失去可读性。

- **两者都不沾**（无任何调用方）→ 死代码，删除，不迁移。

迁移方向只沿一个方向收敛：lib\_book\_common → lib\_common（上移归口）、lib\_book\_common → 业务模块（下沉给唯一使用者）、或 → 删除（死代码清理）；**不得向共享库回填只服务单一模块的代码**——该约束的本意是防止共享库重新变成垃圾场，不是把单模块代码钉死在共享库里。
判据覆盖的典型：

- 通用工具（显示 、日志、位图、主题装配）→ lib\_common（如 `DisplayUtil`/`Logger`/`BitmapUtil`/`AppTheme`）。

- ebook 共享 UI（书架卡片、书籍封面、`InfoChip`、`CommonCard` 等）→ lib\_book\_common。

- 项目专属且只有一个业务模块在用的组件 → 该业务模块，逐个判定见下「单模块件下沉实例」。

- View 体系残留、页面 Compose 化后无人引用的组件 → 直接删除。

### 单模块件下沉实例

三个类原在 lib\_book\_common，按第四支判据移到各自唯一使用方；它们本就**不满足**「项目专属共享件」的收录条件，属正确归属而非「反向搬回」：

| 类 | 现位置 | 判定依据 |
| --- | --- | --- |
| `ReadBookControl` | `module_book/src/main/java/com/ebook/book/view/ReadBookControl.kt` | 阅读设置的单例状态件（字体/字号/颜色/背景/点击与按键翻页开关，内存缓存 + SP 持久化），使用方全在 module\_book 的阅读器链路（`ReadBookActivity`、`reader/ReaderPager`、`reader/ReaderPanels`、`reader/ReaderTypesetter`）；受阅读器域耦合，换一个无关项目不能原样复用，而本仓也只有它需要 |
| `BitIntentDataManager` | `module_book/src/main/java/com/ebook/book/manager/BitIntentDataManager.kt` | 仅 module\_book 的 `BookDetailActivity`、`page/BookShelfPage`、`ReadBookActivity` 三个文件使用，跨不出该模块 |
| `ClipImageActivity` | `module_me/src/main/java/com/ebook/me/view/profilePhoto/ClipImageActivity.kt` | 头像裁剪页，仅 module\_me 的 `ModifyInformationActivity` 以 Intent 打开一处，无跨模块消费者；页面自身只用 Compose Canvas + 手势，共享库层不留任何多余依赖 |

## 权衡

- **通用性 vs 脚手架膨胀**：通用件一律上移会让 lib\_common 层面更全（新项目开箱即用，无需各自复制），
  代价是 lib\_common 体积增长；反之会退回「各项目自己在 lib\_book\_common / 子模块复制粘贴」，违背脚手架定位。
  选择前者，以「无关项目能否复用」为严苛准绳，宁多勿漏地上移至 lib\_common。

## 下游影响

- 删除/迁移 lib\_book\_common 组件前，先按此判据判定归属；仅 lib\_common 持有通用能力，lib\_book\_common 不再收录通用件。

- Compose 迁移（见 ADR-0001）遗留的 View 组件（`CircleImageView`、`DeleteDialog`、`CommentDifferCallback`、ViewBinding 相关）按「死代码」清理。

- 迁移前后同步维护 AGENTS.md 的模块职责描述与本文档，保持一致。

## 待迁移登记（未完成）

以下通用件经判据应归 lib\_common，但上移依赖 android-practice（lib\_common）侧的收纳与发版，
暂留在 lib\_book\_common，登记待迁移：

- `com.ebook.common.util.DateUtil`（日期格式化，3 处业务调用方，类上 `@Suppress("unused")` 已失效待清）
- `com.ebook.common.util.SPUtil`（SharedPreferences 封装，7 处业务调用方）

上移时同步：改 import 到 lib\_common 坐标、清理失效注解、更新本 ADR 与 AGENTS.md。

