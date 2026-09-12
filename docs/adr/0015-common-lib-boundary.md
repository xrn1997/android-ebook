# 共享层分界：通用性与构建形态两条判据决定归属

lib_common（android-practice 仓库的通用脚手架）与 lib_book_common（本项目专属 common）长期存在归属模糊——同一件能力可能被误放进任一模块，或在 Compose 迁移后作为死代码滞留；本仓又长出了带原生代码与隔离进程的专项库，归属问题从「两边分」变成「三处放」。评审定下两条可操作判据：以「换一个与书籍领域完全无关的 Android 项目能否原样开箱复用」分开通用件与项目专属件，以「构建形态是否与共享层同源」决定项目专属件该并入 lib_book_common 还是自立模块。

## 决策

1. **lib_common 归属判据**：一件能力，换一个与「书籍/书城/评论」领域完全无关的 Android 项目，能否原样开箱复用？能则归 lib_common（通用脚手架，任何项目可复用）；不能（受 ebook 域耦合，或只对本仓库有价值）则归 lib_book_common（项目专属 common）。理由：用「无关项目能否复用」做严苛准绳，避免模糊地带两边都收或都不收。

2. **单模块件不归共享库**：不能复用、且当前仅单一业务模块使用的能力，归属该业务模块。lib_book_common 只收被 ≥2 个模块共用的项目专属件，单模块件放共享库会让下游模块跟着编译它、也让「谁在用」失去可读性。

3. **带独立构建约束的能力自立专项库，不回填 lib_book_common**：一件能力若要求与本仓 common 层不同的构建栈——带 NDK 原生代码、带隔离进程组件、刻意不接依赖注入与注解处理器——那就单独建库，即使它的唯一消费方就是 lib_book_common。判据是「构建形态是否同源」而不是「谁在用它」：把它折进 lib_book_common，等于让整层共享库跟着带原生代码与注入图，而这两样恰恰是绝大多数共享件不要的负担。实例是 `lib_book_source`（书源解析器一族 + 脚本解释器 + 零权限 `:js` 沙箱执行器）：只挂通用库与原生两套约定插件、无 Hilt/KSP，沙箱 Service 写在它自己那份库清单里随模块走，由清单合并进应用。代价是装配点外移——该库不带注入图，凡是要把它的件接进依赖注入的装配代码都落在 lib_book_common，为此它的部分类型必须从 internal 上浮可见。

4. **零调用方即死代码，删除不迁移**：无任何调用方的组件（含 View 体系残留、页面 Compose 化后无人引用的组件），直接清理，不迁移到任何共享库。

5. **迁移方向只沿单方向收敛**：lib_book_common → lib_common（上移归口）、lib_book_common → 业务模块（下沉给唯一使用者）、或 → 删除（死代码清理）；不得向共享库回填只服务单一模块的代码——该约束防止共享库重新变成垃圾场，不是把单模块代码钉死在共享库里。

### 单模块件下沉实例

三个类原在 lib_book_common，按决策第 2 条判据移到各自唯一使用方；它们本就不满足「项目专属共享件」的收录条件，属正确归属而非「反向搬回」：

| 类 | 现位置 | 判定依据 |
| --- | --- | --- |
| `ReadBookControl` | `module_book` 阅读器链路 | 阅读设置的单例状态件（字体/字号/颜色/背景/点击与按键翻页开关，内存缓存 + SP 持久化），使用方全在 module_book 的阅读器链路；受阅读器域耦合，换一个无关项目不能原样复用，而本仓也只有它需要 |
| `BitIntentDataManager` | `module_book` | 仅 module_book 的 `BookDetailActivity`、`BookShelfPage`、`ReadBookActivity` 三个文件使用，跨不出该模块 |
| `ClipImageActivity` | `module_me` | 头像裁剪页，仅 module_me 的 `ModifyInformationActivity` 以 Intent 打开一处，无跨模块消费者；页面自身只用 Compose Canvas + 手势，共享库层不留任何多余依赖 |

判据覆盖的典型分类：

- 通用工具（显示、日志、位图、主题装配）→ lib_common（如 `DisplayUtil`/`Logger`/`BitmapUtil`/`AppTheme`）
- ebook 共享 UI（书架卡片、书籍封面、`InfoChip`、`CommonCard` 等）→ lib_book_common
- 项目专属且只有一个业务模块在用的组件 → 该业务模块
- 带原生代码或隔离进程组件、且刻意不接依赖注入的解析/执行能力 → 自立的专项库（本仓即 `lib_book_source`），消费方只有 lib_book_common 也照样独立成模块

## 权衡

- **通用性 vs 脚手架膨胀**：通用件一律上移会让 lib_common 层面更全（新项目开箱即用，无需各自复制），代价是 lib_common 体积增长；反之会退回「各项目自己在子模块复制粘贴」，违背脚手架定位。选择前者，以「无关项目能否复用」为严苛准绳，宁多勿漏地上移。

## 下游影响

- 删除/迁移 lib_book_common 组件前，先按此判据判定归属；仅 lib_common 持有通用能力，lib_book_common 不再收录通用件。
- 专项库的依赖面：`lib_book_source` 的唯一 Gradle 消费方是 `lib_book_common`，且以 `api(project(...))` 暴露——业务模块经 lib_book_common 的门面拿到解析器，不直接依赖该模块，专项库因此不会顺着业务模块的编译类路径扩散。
- 迁移前后同步维护 AGENTS.md 的模块职责描述与本文档，保持一致。

## 遗留

以下通用件经判据应归 lib_common，但上移依赖 android-practice（lib_common）侧的收纳与发版，暂留在 lib_book_common：

- `com.ebook.common.util.DateUtil`（日期格式化，调用方只有 lib_book_common 内部的 `CommentTime`——用它算评论的相对时间，页面侧是「书评」与「我的评论」；阅读器链路不引用它。`FileTree` 的 KDoc 把它列为同一批待迁移项，但不调用它。类上的 `@Suppress("unused")` 早已失效，随上移一并清）
- `com.ebook.common.util.SPUtil`（SharedPreferences 封装，会话持久化、登录拦截与阅读设置多处依赖）

上移时同步：改 import 到 lib_common 坐标、清理失效注解、更新 AGENTS.md。
