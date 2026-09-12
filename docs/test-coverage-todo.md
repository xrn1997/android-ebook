# 测试覆盖待办

来自原《架构改进计划》未完成项的承接清单（2026-08-05 由 /grill-with-docs 会话迁移）。

- [x] 为 `BookRepository` 添加单元测试（loadBookContent / saveBookContent / updateChapterCache / bookShelfEvents）
  —— 已由 `lib_book_common/src/test/.../repository/BookRepositoryTest.kt` 覆盖（手写 Fake DAO，纯 JVM），
  另含 addToShelf/removeFromShelf 级联、getCachedChapterUrls 短路、getAllBooksWithDetails 孤立清理与章节排序
- [x] 为 `UserSessionManager` 补充 token 同步 TokenHolder 的测试用例
  —— 已完成：`FakeUserSessionManager` 已注入并同步 `TokenHolder`，`UserSessionManagerTest` 已含
  `saveSession should sync token to TokenHolder` / `saveSession with empty token should clear TokenHolder` 等用例。
  **注意该测试测的是假件自洽**，真实现 `AndroidUserSessionManager` 一度零覆盖——会话镜像③
  （`ProfileRepository` 内存昵称/头像流）漏清因此没被发现。本轮已补
  `lib_book_common/src/test/.../domain/AndroidUserSessionManagerTest.kt`（Robolectric + 真 SP/TokenHolder，
  断言 `clearSession()` 一次覆盖三处镜像，并用落盘键白名单锁死「密码不落盘」）
- [ ] 为 mock 数据源添加资产契约测试
  —— 评论侧已完成：`lib_ebook_api/src/test/.../comment/CommentNetworkTestTest.kt` 用文件版
  `TestAssetManager` 直读 `src/main/assets` 跑通生产路径，锁死三条契约：资产形态与解码类型一致
  （`RespDTO<CommentPage>` 包裹）、两份评论资产的 `chapter_url` 交叉对齐（「我的评论」点进评论区不为空）、
  新增评论由 mock 赋 id/作者/时间（防撞列表 key 与本人判定失配）。
  用户侧（`UserNetworkTest`）仍待补——已先用同一口径人工比对过资产形态，修掉 `user_modify_pwd.json`
  残留的旧契约 `data: 1`（与 `RespDTO<Unit>` 错配，mock 下改密码必失败）；补测试时把这类
  「成功以业务码为判据、`data` 一律为 null」的形状一并断言
- [x] 为阅读器翻页窗口状态机（`ReaderPagerController`）添加回归测试
  —— 已由 `module_book/src/test/.../reader/ReaderPagerControllerTest.kt` 覆盖（Robolectric 只提供 Context
  与资源，不渲染 View；`Animatable` 走测试内虚拟帧时钟，控制器必须挂前台 TestScope——
  `advanceUntilIdle()` 不推进 backgroundScope 的任务）。锁死的是「提交翻页时目标页非 Loaded」
  的窗口收敛口径：来路页保留为相邻方向、未知方向收敛为 null，既不让 nextKey 自指造成
  翻页空转（滑完仍停在同一页），也不让失败页成为回不去的死页；并断言翻回去再翻过来会对
  失败页自动重发请求（`ensureLoad` 对已注销 job 的 key 必然重发 = 一次隐式重试）；
  另含一条「已 Loaded 的页不被窗口重算重抓」（`ensureLoad` 的短路，防快速回翻时刚读过的页闪加载态）
- [x] 为正文分页跟进判定（`ChapterPageMatcher`）添加回归测试
  —— 已由 `lib_book_source/src/test/.../analyze/ChapterPageMatcherTest.kt` 覆盖（13 例，纯 JVM）：
  判定基准为目录页原始章节 URL、只对候选链接剥一次后缀（保留扩展名 + 去扩展名兜底）。
  锁死两类回归：「章节号写在连字符后」的站点相邻章不得被判为同章（否则串章），
  以及「第 1 页也带后缀」形态当前宁漏页不串章的取舍（将来上书源分页模板时需同步改该断言）
- [x] 为列表（分类页/搜索页）分页判定添加回归测试
  —— 两处纯逻辑已覆盖：`lib_book_source/src/test/.../analyze/ListPageUrlTest.kt`（8 例，纯 JVM）
  锁死页码换算与首页裁剪——以 `/{{page}}` 结尾的模板首页渲染为裸路径（`/xuanhuan`、`/so/关键词`），
  `?page=` 查询式与页码段在中段的模板不裁；`module_find/src/test/.../mvvm/viewmodel/BookPageMergeTest.kt`
  （5 例，纯 JVM）锁死「按 noteUrl 去重」与「本页无新条目 = 到底」（站点越界页以 HTTP 200 重复返回首页
  书目，只看空页判不到底；重复条目还会撞 `LazyColumn` 的 item key 而抛异常）。
  **仍未覆盖**：ViewModel 侧的页码递增、`hasMore` 信号与刷新状态机的接线（需 Robolectric + 假仓库，
  或直接人工装机验证），本轮按人工验证处理
- [x] 为导入判重与处置添加回归测试（ADR-0023）
  —— 判重口径由 `lib_book_common/src/test/.../domain/DuplicateBookDetectorTest.kt` 锁死（6 例，纯 JVM）：
  同名同作者命中、**同名不同作者不命中**（命中即给删除入口，误判等于删掉另一本书）、作者占位词两边归空、
  比对的是当前主键而非 `book_info.name`（改过匹配名后检测跟随）、secondary 键不参与判重、多命中全部返回并按来源标注。
  处置原语由 `BookRepositoryTest` 锁死：`absorbGroupKeys` 吸收含 secondary 且同名键不重复加行；
  `mergeTailChapters` 前缀对齐只补尾 / 序列分叉整笔放弃且不删新条目 / 索引有洞时接末位之后不覆写 /
  目标是网络书时拒绝。`LocalBookImporterTest` 补 `parseMetadata` 两例（解出书名作者、不写任何表）。
  暂停门的语义由 `lib_book_common/src/test/.../importer/LocalImportCoordinatorTest.kt` 锁死（3 例，纯 JVM）：
  门住在 `LocalImportCoordinator`（不在 `BookImportViewModel`——导入循环与任何页面解耦，进程活着它就活着），
  判重命中即置门暂停整批、处置后两本都保留、`resolveCancel` 跳过且不留任何写入。
  **仍未覆盖**：`getAndSet(null)` 的连点幂等（第二次取到 null 无操作，不会把上一个文件的决策灌给下一个门）
  与处置框交互（UI 侧）——需要 Robolectric，本轮按人工装机验证处理
- [ ] 为下载队列的失败重试/出队语义添加测试
  —— `DownloadService.downloading` 的不变式（重试 `RETRY_TIMES` 次耗尽后必须 `deleteTask` 出队、
  暂停中断时不出队、解析占位文案与空正文都不得入库）目前**无自动化覆盖**，只能靠人工装机验证。
  障碍：重试循环与 `Handler.postDelayed`、前台通知、`serviceScope` 绑在 Service 上。补测前需先把
  「取任务 → 重试 → 出队/入库」抽成可注入假仓库与假时钟的纯挂起函数（或改用 Robolectric + 假 `DownloadRepository`）
- [x] ~~为 `CommentRepository` 添加单元测试（当前**零覆盖**）~~
  **已覆盖（2026-09-07 评论分页改造时）**：`CommentRepositoryTest`（纯 JVM，`CoroutineAdapter` 的
  刷新器/事件总线/令牌桶直接构造，假件路子沿用 `SessionTokenRefresherTest`，未引入 Robolectric）
  锁住：空键守卫（**不发请求**直接返回空页——防的是契约（M2 spec §3.2.1）里
  「`comment_keys` 缺失 → 返回全局最新列表」的分支）、整页/短页/空页的 hasMore 推导
  （不依赖服务端 total）、page/pageSize 透传、非成功业务码与数据源异常的失败翻译、
  data=null 兜底。页合并（去重 + 全局时间倒序）由 module_book 的 `MergeCommentPageTest` 锁住；
  mock 聚合查询的切页契约（页间不重叠、并集覆盖全集）补进 `CommentNetworkTestTest`。
  **仍未覆盖**：`BookCommentsViewModel` 的翻页状态机接线（游标推进、在途闸门、信号时序）
  ——需 Robolectric，与既有「VM 接线需 Robolectric + 假仓库」缺口一并处理
- [ ] 为 Compose 页面添加 UI 测试
  —— 首个样例已落地：`module_book/src/test/.../page/DownloadQueueActionRenderTest.kt`，
  锁住书架顶栏下载角标在 1/2/3/4 位剩余数下都被完整画出（回归的是 material3 1.4.0 起
  `IconButton` 容器自带 `.clip(shape)`、把悬浮在锚点之外的角标切成齐边口）。
  可复用的回路是 Robolectric `@GraphicsMode(NATIVE)` + `createAndroidComposeRule<ComponentActivity>`
  + `decorView.draw(Canvas(bitmap))` 数探针色像素；**`captureToImage` 在此不可用**
  （走 PixelCopy + frame commit 回调，paused looper 不驱动，2s 必抛 `ComposeTimeoutException`）。
  **仍未覆盖**：其余 Compose 页面（列表滚动、对话框、手势）同样只能用像素断言锁「画出来没有」，
  按此回路逐页补
- [ ] `AuthInterceptor` 测试归属 lib_common（android-practice 仓库，随认证体系对齐后不再在本仓库维护）

## 本轮（2026-09-03 评审）明确延后的技术债

- [ ] **`module_find` 全量跑偶发 `UncaughtExceptionsBeforeTest`（真实 IO 协程漏过 `resetMain`）**
  —— 症状：`./gradlew test` 整跑时 `SearchViewModelTest` 里某一条失败，异常本身却写着「there were
  uncaught exceptions **before the test started**」，真正的泄漏者是同一 worker 里先跑的某个类；
  stderr 里的现场是 `DispatchedCoroutine{Completed}@…, Dispatchers.IO` 恢复父续体时报
  `Dispatchers.Main was accessed when … the test dispatcher was unset`。
  根因形状：ViewModel 的 `init`/写库路径上有 `withContext(Dispatchers.IO)`，跑在**真实** IO 线程、
  不受虚拟时钟控制，用例结束时可能仍在飞，而 `@After` 的 `Dispatchers.resetMain()` 不等它落地。
  归因难在它是**跨类污染**：单跑该类三次、整模块连跑五次、再加一次全项目 `test --rerun-tasks`
  （331 个 task 全部重新执行）都不复现，只有负载/顺序凑对了才出现。
  两条候选修法（择一，别用 sleep 兜）：① 给测试作用域注入可替换的 IO 派发器（本仓已有先例：
  `BookSourceManagerImpl` 的 `scope` 构造参数就是「唯一为可测性做的让步」），让那趟活落在虚拟时钟上；
  ② `@After` 里先取消 `viewModelScope` 再**有界**排空调度器，并把「不该有在飞协程」写成显式断言。
  本轮不动它：与脚本书源/沙箱执行器无关，且改的是他人模块的测试基建。
- [ ] **`xrn1997.android.compose` 约定插件自带 `isModule` 分支并重复应用基础插件（当前休眠：根 `includeBuild` 为注释态）**
  —— 2026-09-04 两仓对齐后 `xrn1997.android.compose` 已是与 android-practice 一致的**唯一 ID（无别名）**，
  但「同 ID」不等于「同实现」：android-practice 版不自套基础插件、且额外注入 compose ui-test 依赖，
  本仓版按 `findProperty("isModule")` 自行应用 application/library（差异与影响见 ADR-0020）。
  本仓实现里该分支原为 `module_main` 只挂组件插件时的便利，如今 `module_main` 已同时应用
  `xrn1997.android.component`（它本就按 isModule 应用基础插件），compose 插件里的自套是冗余的。
  一旦取消根 settings 的 `includeBuild("lib-common-build")` 注释恢复本地联动，命令行 `-P` 就会渗进去：
  `lib_common` 已自行应用 `xrn1997.android.library`，又被 compose 插件套上 `com.android.application`——
  `'com.android.application' and 'com.android.library' plugins cannot be applied in the same project`。
  根治方向（对齐 android-practice 形态）：compose 插件不再自套基础插件、只加 compose 能力
  （模块先应用 library/application），删掉 `isModule` 分支后该坑从根上消失。
  当前规避方式见 `gradle.properties` 注释（独立调试直接改文件，勿用 `-P`）
- [ ] **零调用方的 Room DAO 方法**（实测确认：`BookShelfDao.getAllBooksFlow`、`getBookFullInfoByUrl`、
  `getBooksByUrls`、`getCount`、`DownloadChapterDao.getFirst`）—— 按 ADR-0015「无任何调用方 → 删除」
  应删，但本轮不动：不影响 schema、无用户可见症状，删除需连带去掉刚补的注释并重跑回归，
  宜单独一次 `refactor(lib_ebook_db)` 提交处理。删前先确认不是为 Flow 化书架预留
  （`getAllBooksFlow` 看着像，但无任何文档这么写）
- [ ] **独立调试宿主绕过 `clearSession()`**：`module_login/src/main/test/debug/MainActivity.kt:73`
  的「退出登录」只 `SPUtil.remove(SP_IS_LOGIN)`，不清 `user_session` SP 与 `ProfileRepository` 内存态——
  与本轮修掉的「会话三处镜像未一并失效」是同一类缺陷（仅影响独立调试宿主，不影响集成构建）。
  修法：改调 `userSessionManager.clearSession()`；同批已把 `module_book` 调试宿主的模拟登录改成走
  `saveSession`（见其类 KDoc）
- [ ] **一次性操作的在途闸门没有被测试锁住**（C1 引入）
  —— `SettingViewModel.runLogout` 的「连点只放行一次」由 `SettingViewModelTest` 在 JVM 下断言
  （provider 是可注入的假件）。`CacheManageViewModel.clearInProgress` 与
  `ModifyViewModel.submitInProgress` 是同一条纪律，仍无对应测试。**构造 VM 已经不是障碍**
  （`CacheModel` 改收 `File` 根后，`CacheManageViewModelTest` 三例已在纯 JVM 下锁住
  「书籍内容单列呈现、不进可清理总量、`clearAll()` 不动书籍文件」）；缺的是**计数点**：
  `CacheModel` 是 concrete 类且清理跑在真实 `Dispatchers.IO` 上，既没法让第二次调用确实落在
  第一次在途期间，也没有「执行了几次」可断言——硬凑时序只会得到一只随机器负载闪的 flaky 测试。
  解锁方向：给清理路径留一个可注入的挂起钩子（或把「一笔清理」收进带状态的小接缝）。
  `ModifyViewModel` 侧同理（要凑 `ModifyRepository` 的 10 方法 `UserDataSource` 假件）。
  本轮按人工装机验证处理（见清单第 8 项）
- [x] ~~**第二份字节格式化实现（`module_book` 的 `convertByte`）暂不收口**~~
  **已收口（2026-09-07）**：`formatSize` 上移至 `lib_book_common/util/FormatSize.kt`，
  `module_book` 的 `convertByte` 删除、改调共享 `formatSize`；
  `module_me` 的本地 `formatSize` 删除、三处 import 改指共享件。
  展示口径统一为 `formatSize`（`Locale.US` + 空格分隔）。
  最终家仍是 `lib_common`，本轮先落 `lib_book_common`，等下次联动窗口上移
- [ ] **「我的评论」页每次配置变更都重拉全量评论**（2026-09-06 从依赖源码证实，未修）
  —— lib\_common 0.3.2 的 Compose `BaseActivity.onCreate` 无条件调 `initData()`
  （`compose/BaseActivity.kt:80`），而 `MyCommentActivity.initData()` 直接 `viewModel.refreshData()`
  （:61-63）。`CommentViewModel` 跨配置变更存活、列表本来还在，却每次转屏都重发一次
  `getMyComments` 并把覆盖层打回 `Overlay.Loading`——症状是转一下屏幕列表闪一次加载态、白跑一个请求。
  **不能直接删掉 `initData()` 覆写**：基类的错误页重试钩子 `onNetworkErrorRetry()` 也走 `initData()`
  （`compose/BaseMvvmActivity.kt:39`），删了重试就没反应了。
  建议修法（幂等而非删钩子）：页面入口调 `viewModel.loadData()`，VM 内
  `fun loadData() { if (list.value.isEmpty()) refreshData() }`——首开拉、有数据时转屏不重拉、
  失败后列表仍空时重试照常生效；`BaseRefreshViewModel` 自带下拉刷新，用户要新数据有显式入口。
  测试为什么没顺手补：`CommentViewModel` 要真 `CommentRepository`，后者的 mock 数据源
  `CommentNetworkTest` 需要 `Json` + `TestAssetManager` 两件套（本仓 `lib_ebook_api` 的
  `CommentNetworkTestTest` 用的是文件版 `TestAssetManager`），搭这套脚手架的成本远超这条
  一行守卫本身，故与既有的「VM 接线需 Robolectric + 假仓库」缺口一并处理

## 人工装机验证清单（本地书导入与评论分页改造，2026-09-06）

自动测试已锁死的不重复列：判重口径与处置原语（`DuplicateBookDetectorTest` / `BookRepositoryTest`
含 `updateMatchMeta` 修键与事务性）、TXT/EPUB 解析与封面、编码探测与规范化、章文件与两层缓存、
md5 短路、mock 评论契约含迁移计数（`CommentNetworkTestTest`）——本轮共 205 例全绿。
以下只列自动化够不到的**设备项**，按风险排序。

### 1. Room v2→v4 覆盖安装（迁移链 `MIGRATION_2_3` → `MIGRATION_3_4`）

前置：装的是改动前版本（v2，仍含 `book_content` 表），书架上同时有 ①至少一本本地导入的
TXT ②至少一本网络书源加进书架的书。

1. 记下网络书的阅读进度与"已缓存 y/z"数字。
2. 覆盖安装改动后的包（不要清数据），迁移自动连跑 v2→v3→v4。
3. 打开书架：本地书应全部消失——设计如此（spec §2 决定 9：可再生数据不背兼容），不是 bug。
   网络书仍在、**阅读进度不变**。
4. 网络书"已缓存"数字预期**归零**：`MIGRATION_3_4` 只删 `book_content` 表与 `has_cache` 列，
   **不把旧正文搬成章文件**（正文属可再生数据，迁移不搬运，与上一步本地书消失同一取舍）。
   重新发起下载即可恢复，之后 `files/books/<noteUrl>/` 下应重新出现 `cNNNNN.txt`。
5. `adb logcat -b crash` 应无 FATAL EXCEPTION。
6. 重新导入那本 TXT（顺带走 §3 的判重链路）：数秒内出现在书架、点开能翻页；
   `adb shell run-as <包名> ls files/books` 应看到以 32 位 md5 命名的目录。

### 2. 外部打开（本轮修复项，`BookImportRepository` 零自动化覆盖）

1. 文件管理器对 `.txt` 用「打开方式」选本应用 → 应导入并直接进阅读器，书名来自**真实文件名**
   （不是 `import-<数字>`），作者按文件名解析或显示占位词。
2. 同样路径打开 `.epub` → 同上。两份清单（`src/main/` 与 `src/main/module/`）都挂了
   `application/epub+zip` 过滤器，content/file 两种 scheme 最好都过一遍。
3. 外部打开同一文件两次 → 第二次不产生重复条目（md5 短路逻辑已由单元测试锁死，
   这里验的是 Uri→暂存链路保真名）。
4. 导入结束后 `adb shell run-as <包名> ls cache` 不应残留 `import-<数字>` 目录
   （暂存目录随成功失败都会清）。

### 3. 强制刷新与两层缓存失效（本轮修复项）

1. 打开一本**网络书**某章 → 菜单「强制刷新缓存」→ 重抓完成后正文为新内容，且**翻页页序不错乱**
   ——旧行偏移配上新正文的症状是"页数没变但内容接不上"，正是本轮修的缺陷。
2. 强刷失败（可断网模拟）→ 提示跳过章数、常驻通知消失，不崩溃也不空转。
3. 阅读中换字体字号 → 立即按新字号重排（`ChapterLayoutKey` 已把字号编进键，
   自动化锁的是缓存行为本身，这里验阅读器接线）。

### 4. 评论链路（M2；空键守卫已由 `CommentRepositoryTest` 锁死）

1. 章评论区**空聚合键**入口应显示空态、不发请求。注意：mock 资产的评论全带 `comment_key`，
   此路径在 mock 下不可达，需连真实后端用 `comment_key` 为 null 的旧数据验
   （契约 §3.3 允许旧数据为 null）。
2. mock 构建（`assembleMockDebug`）下走一遍发表/删除/长按删本人评论。
3. 详情页进「编辑作品信息」（新页面 `EditBookMetaActivity`）：改主匹配名 → toast 的迁移条数
   应等于本人该键的全部行数；返回章评论区，旧键评论仍在（读并集）。
   键重算与切主键逻辑已由 `updateMatchMeta` 两条测试锁死，这里只验 UI 接线与文案。
4. **书评页分页（2026-09-07 分页改造）**：滚动到底应自动追加下一页，列表无重复条目、时间倒序
   不错乱；翻到尽头后触底不再发请求；加载失败（可断网模拟）后停止自动重试，下拉刷新一次即恢复
   自动加载。**mock 构建下即可完整走通**：页大小 20（`CommentRepository.PAGE_SIZE`），
   热章 `ck1:tianqi#0` 种子 45 条（第一章进阅读器 → 打开章评论区 = 3 页），mock 资产
   `chapter_comments.json` 专门为这一演示扩容，无需真实后端。

### 5. 导入判重与处置框

判重口径、处置原语、暂停门语义已分别由 `DuplicateBookDetectorTest`、`BookRepositoryTest`、
`LocalImportCoordinatorTest` 锁死（见上方待办条目）。
**此处原先写作「6 条见 ADR-0023『验证』节」，是不成立的引用**：该 ADR 只有背景/决策/权衡/落地状态四节，
从未有「验证」节——本仓 ADR 一律不挂人工装机验证项。真正够不到的设备面是**处置框本身**：
四个动作各点一遍（继续添加/智能合并/覆盖/跳过）后书架条目数与阅读进度的实际呈现，
以及它跑在真实 Uri/文件链路上的形态（与第 2 项同一条路径）。

### 6. 性能基线回填（spec §6 仍空着）

`./gradlew :module_book:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ebook.book.ImportBaselineTest`
（夹具由测试自建：2000 章 / 约 6MB），从输出 `BASELINE elapsed=… chapters=… fileKb=… memDeltaKb=…`
行回填 spec §6；**改前**侧须切 develop_book 分支跑（夹具随旧链路留在该分支，提交 2f248fa）。
EPUB 手工夹具：`node scripts/generate_test_epub.js` 在仓库根生成 `test_book.epub`（已 gitignore），
push 到设备后供第 2 项的 EPUB 入口使用。

### 7. 既有待办（引用，不重复展开）

- 下载服务失败重试/出队语义（本文件上方待办条目）
- 导入页暂停门与处置框交互（本文件上方待办条目）
- 权限四条回归：拍照/相册/导入/下载通知（ADR-0022）

### 8. module_me 登出收尾、一次性操作闸门与缓存页书籍行（C1、C6，2026-09-06 架构评审轮）

登出的顺序、失败容错、闸门与覆盖层已由 `SettingViewModelTest` 锁死；剩下的正是自动化够不到的
两面——命令通道（`sendToast`/`sendFinish` 在 lib_common 侧是 internal，测试读不到）与旋转本身。

1. 登录后「我的 → 设置」点「退出登录」并确认：期望等待态转起 → Toast「退出登录成功」→ 页面关闭 →
   「我的」页回到未登录态，昵称与头像一并清掉（不留上一身份）。
2. **点完确认后立刻旋转屏幕**：转回来期望登出仍然完成。修复前这里会停在登录态且毫无提示——
   登出原先挂在页面作用域上，转屏取消协程就把 `clearSession()` 一起吞了。
3. 各连点两次：「退出登录」确认、「清理全部缓存」、缓存 Sheet 内「清理图片缓存」、「保存昵称」——
   期望每样只有一条提示、一次结果；清理期间应有等待态而不是「按了没反应」。
4. 后端侧确认该用户 refresh token 已全部作废（拿旧 token 调 `POST /api/auth/refresh` 应被拒）。
5. 独立模式（`gradle.properties` 的 `isModule` 临时改 `true`，勿用 `-P` 覆盖，验完改回）重复第 1 项：
   期望只有本地清理生效、不闪退。
6. 头像裁剪页连点两次「确定」：期望只回传一张图，缓存管理页的临时文件里不堆第二份 `cropped_*.jpg`。
7. 「设置 → 清除缓存」页看**书籍内容**行（值形如「6.8 KB · 2 本」）：期望数字与书架藏书量级相符
   （一本 2000 章约 6 MB）、册数与书架一致，且明显大于「缓存总占用」；点「清理全部缓存」后期望
   该行数值不变、回书架书都还在、点开还能读；集成态该行应带右箭头，点一下期望直接落回书架
   （设置与缓存两页一并出栈，返回不再回到设置页）；独立模式（`isModule=true`）重看一次，期望无箭头、不可点。
   另：导入中途杀掉进程再进本页，期望那本**不计入册数**（`.tmp` 暂存是半成品，等对账回收）。

## 人工装机验证清单（书库缓存重构，2026-09-08）

缓存策略（TTL/SWR/强刷/回写守卫/按源分区/取消原样上抛）与文件形态（含**残留 `.tmp` 被下次写入
消费掉**）已由 `LibraryDiskCacheTest`（8 例）、`BookSourceRepositoryLibraryCacheTest`（10 例）、
`LibraryViewModelTest`（10 例，含真文件缓存路径）锁死，共 28 例全绿。以下只列自动化够不到的**设备项**。

### 1. 下拉刷新为真刷新（本轮修复的主缺陷）

1. 进书城等书目出来 → 下拉刷新：期望转圈数秒（真在逐分类抓页）后收口，可对着站点实际内容
   变化确认拉到的是新数据；同源刷新期间列表**不闪空**。
2. 断网下拉刷新：期望转圈收口、列表保留旧书目（不清空、不误报「书源已失效」）。

### 2. SWR 双发射与 TTL

1. 首次进书城（无缓存）走网络出书目；杀进程重进：TTL（6 小时）内**秒显**且 logcat 无解析请求。
2. 调设备时间 +7 小时重进书城：期望先秒显旧书目、数秒后自动换成新书目（双发射肉眼可辨）。

### 3. 缓存管理页与迁移

1. 进过书城后打开「设置 → 清除缓存」：期望「其他」档出现 `library_cache` 条目并计入占用。
2. 单独清理「其他」（或「清理全部」）后重进书城：期望触发重拉；「书籍内容」行不受影响。
3. 从旧版本覆盖安装（设备上已有 `ACache` SP 文件）：进书城正常拉取；可选
   `adb shell run-as <包名> ls shared_prefs` 核验 `ACache.xml` 已被清掉。

### 4. 换源与坏源

1. 两个源互切：已缓存侧秒显（不发请求），未缓存侧走网络。
2. 书源管理页删除当前源后回书城：期望显示「当前书源已失效」引导（换源/重导），无列表闪烁。

## 人工装机验证清单（脚本书源阶段一，2026-09-08）

出身判别、三项警示与出身标记、双后端路由与 `addScriptSource` 的列覆盖策略已由 `BookSourceValidatorTest`
（17 例）、`BookSourceViewModelTest`（22 例）、`BookSourceManagerImplTest`（63 例）、`SourceStorageJsonTest`
（4 例）锁死；`AppDatabaseSchemaTest` 把 schema 的**结构**锁在「差集恰好是那一列、迁移 SQL 与建表语句
同字」这一层。手写迁移的**行为**如今也有覆盖：`BookSourceMigration6To7Test` 用裸 `SQLiteConnection` 直接
驱动 `DatabaseModule.MIGRATION_6_7.migrate(...)`，跑在**生产同款**引擎上——`BundledSQLiteDriver` 的 JVM
变体（`androidx.sqlite:sqlite-bundled-jvm`）已作为 `lib_ebook_db` 的 `testImplementation` 依赖引入，实测
引擎为 SQLite 3.50.1，故 `ALTER TABLE … DROP COLUMN …` 真能跑（该迁移为此声明为 `internal` 而非 `private`）；
用例断言「内置行被清、用户导入行保留、列已消失」，并以 `SELECT sqlite_version()` 守卫引擎 ≥ 3.35。
**其余历史迁移（`MIGRATION_1_2` … `MIGRATION_5_6`）仍是 private、未被行为驱动**，只有 schema 结构侧断言
与人工装机验证兜着。以下只列自动化够不到的**设备项**。

### 1. 覆盖安装 v5→v6→v7 迁移（链上的 `ALTER`/`DELETE` 仍要在真库上跑完）

前置：装的是改动前的版本（v5），书架有藏书、书源页有若干源（当时含随包下发的内置源）。

1. 覆盖安装本分支的包（**不要清数据**）。
2. 打开书架与「设置 → 书源管理」：藏书与用户导入的书源都应在；**随包下发的内置源已被 v6→v7 清掉**，
   当时绑着它的书按既有语义显示「书源已失效」——这是预期结果，不是回归。
3. 存量行的 `format` 应全为小写 `native`：`adb shell run-as <包名> exec sqlite3 databases/ebook.db "select url, format from book_source"` 逐行看。
   设备上没有 sqlite3 时退一步——每个存量源都照常解析得出内容，就说明没有一行被路由到脚本桩
   （**当时的判据**：脚本行求值必抛「解释器尚未实现」；解释器落地后桩异常已删除，这条退路随之失效，见第 7 项的作废说明）。
4. `adb logcat -b crash` 无 FATAL EXCEPTION（本项目未开破坏性迁移，迁移失败只会崩、不会静默清库）。

### 2. 导入含 `<js>` 的脚本书源

1. 用「设置 → 书源管理 → 导入」选一条规则里带 `<js>…</js>`（或字段以 `@js:` 取值）的社区 JSON。
2. 预览行应带「脚本」出身标记 + 「含可执行脚本代码」警示行，且状态仍是**通过**（`校验通过`）、
   计入「确认导入 N 条」。
3. 确认后重进书源页：该源在清单里、启用开关可拨、可删除。

### 3. 导入纯声明式脚本书源（原文里没有脚本）

1. 一条只有声明式字段的脚本格式源：预览只有「脚本」标，**一句警示都没有**。
2. 入库后该行**不出现「导出该书源」**（脚本行的 `rule` 只是按实体列合成的展示空壳，导出会产出一份
   看着成功实则空规则的文件）；「导出全部」的产物里也不含它。

### 4. 混合包里一条脚本 JSON 字段类型错

1. 整包混排三条：一条好的原生规则 + 一条好的脚本书源 + 一条 `bookSourceType` 装了非数值的脚本源。
2. 期望只坏第三条：报「脚本书源 JSON 无法解析…这一条不会被导入（其余条目不受影响）」且**不给警示**
   （解不出就不拿模型默认值猜字段），另两条照常通过并入库。
3. 收尾统计应为「导入成功 2 条 / 覆盖 0 条 / 失败 1 条（校验未通过）」，而不是整包回滚。

### 5. 同 URL 跨出身重导的文案

1. 先导入一条原生规则源（url = X），再导入一条同 X 的脚本书源：预览标记应是**「将覆盖原生规则书源」**，
   不是光秃秃的「将覆盖」。
2. 反向再来一次（用 X 的原生规则覆盖那条脚本行）：标记应是**「将覆盖脚本书源」**，
   且覆盖后该行恢复「导出该书源」、能正常解析（一主键一行，不残留脚本形态）。

### 6. 原生源全链路回归（解析器一族换了模块，行为必须零变化）

1. 书城首屏出书目 → 下拉刷新是真刷新（转圈数秒、不换页不闪空）。
2. 搜索 → 详情 → 目录 → 正文，其中正文挑一本**分章跨页**的书（验分页跟进）。
3. 阅读器内换源、离线下载整本、下载完断网读正文。
4. 任一环节内容错乱都是回归：本轮迁移只搬代码位置（`lib_book_common` → `lib_book_source`），没动语义。

### 7. 脚本书源求值给出的是「解释器未实现」（已由 2d 作废）

> **2026-09-09 作废**：解释器（Plan 2 之 2a~2d）已落地，桩解析器与桩异常删除，脚本行不再报
> 「引擎未实现」——它现在会真去求值。本项判据被文末「人工装机验证清单（脚本书源阶段二）」的第 1、3
> 项取代（那里的口径换成：失败要报**真**根因，而不是任何「还没实现」的兜底话术）。留文不删，是为了
> 让读者知道这条曾经是唯一可行的处置提示。

1. 书架挑一本网络书，导入一条与该书归属源同 URL 的脚本书源覆盖掉原源（即第 5 项那一步），再打开它。
2. 期望提示是「脚本书源解释器尚未实现…待引擎升级后自动生效」这一类明确说法（正文、目录、搜索任一路径同理）。
3. **不是**静默的空列表/永久加载态，**也不是**「当前书源已失效」——后者会把用户支去重导一条本来好的源
   （重导一百次引擎还是没有）。

## 人工装机验证清单（脚本书源阶段二，2026-09-09）

解释器四段（2a 词法 / 2b HTML 求值 / 2c JSONPath 与取文 / 2d 装配与接线）已全线落地：`ScriptBookParser`
实现 `BookParser` 五面与 `ScriptContentParser` 正文接缝，桩解析器与桩异常删除，聚合搜索候选含脚本行。
上述行为由 `:lib_book_source`（330 例，含 `ScriptSourceEndToEndTest` 的三型合成源全链）与
`lib_book_common` 的 `JsoupSourceReaderTest`（脚本分支 3 例）、`BookSourceManagerImplTest`（工厂换真
解析器 + 脚本行为候选）锁住。**真实源语义的金标准已就位**：`ScriptRealSourceGoldenTest` +
`src/test/resources/scripted_real/` 冻结了四条声明式源（无极书院/手机看书/阅读书屋/网阅小说）的真规则串
与真响应，合成源退为编排层补充、不再顶位（旧的语料债由此偿还）；含 JS 的重源仍走真机临时验证
（见文末第 7 项），不转永久夹具。真机上的 HTTP
行为（真实 headers、重定向、gbk 编码、软 404）与网络竞态不在 JVM 测试覆盖范围内，因此以下七条只能
人工确认。

### 1. 纯声明式真实脚本源可搜索

前置：导入一条**不含任何 JS**（原文里没有 `<js>`、`@js:`、`,{"js":…}`）的真实脚本书源。

1. 搜索页输入关键词 → 聚合进度里**看得到这条源**（阶段一它不进候选）。
2. 结果条目可点进详情页，书名/作者/封面与站点网页一致。
3. 反例判据：结果全空**且零提示**是错的——规则解不动必须抛类型化失败并显示出来（规格 §12）。

### 2. 脚本源加书架 → 目录 → 正文全链路

1. 从第 1 项的搜索结果「加入书架」→ 目录加载完成。
2. 目录挑一个**多页目录**的站点，翻到最后一章：章节总数与站点目录页合计相符（验 `nextTocUrl` 链，
   含数组形态）；触顶截断（`MAX_TOC_CHAPTERS`）应只在真超上限时出现，且日志记下截断。
3. 翻开一章正文：分页站点（`nextContentUrl`）必须**拼完整**，不缺后半、不串进下一章。
4. 反例判据：正文只有第一页 = 翻页链断；正文一路涨到 50 页且内容重复 = 回环防护失效。

### 3. 含 JS 的脚本源报的是「需沙箱」这句真话

> **2026-09-09 起限定适用**：执行器已在仓（见下一段清单），装配齐的路径不再报这句——本条现在只对
> 「没装配执行器」的构建成立；报「需沙箱」与报「脚本执行失败」的分界改由下一段第 1、3 条验。

1. 导入一条含 `<js>` 或 `@js:` 的脚本源，搜索并翻开它给出的那一章/那一个字段。
2. 期望提示属于「需要脚本沙箱执行器」这一类（`JsEvaluationPendingException` 的消息），
   **不是**「当前书源已失效」（会把用户支去重导一条本来好的源），**也不是**静默空结果。
3. 同一源里不含 JS 的字段仍应正常解出（声明式部分可用是本轮的验收口径）。

### 4. gbk 站源不乱码（charset 选项显式字节解码）

1. 挑一条 `,{..., "charset":"gbk"}` 的老站源（或自己补一条）搜索并读正文。
2. 期望书名与正文**无乱码**；`EncodingInterceptor` 把响应头强改成 UTF-8 不影响这里（取文走
   `bytes()` + 显式 `String(bytes, charset)`）。
3. 反例判据：整页生僻字乱码、或替换字符 `?` 连片 = 走了 `body.string()` 的隐式解码。

### 5. `<,{{page}}>` 分页真实源验证（规格 §6.4 / §11-7 的 2c 债）

1. 找一条页码写成 `https://x/s<,{{page}}>.html` 这类形态的源，翻到第 2、第 3 页。
2. 期望请求的 URL 与站点实际分页地址逐字相符（首页不带页码段、其余页带）。
3. 这是本仓规定而非上游实证：若真实源形态与 §6.4 的读法不符，改**规格 + 实现 + 用例**三处，别只改代码。

### 6. 原生源回归（桩删除不碰原生路径）

1. 书城首屏 → 下拉刷新 → 搜索 → 详情 → 目录 → 正文 → 离线下载，全部与改动前一致。
2. 阅读器内换源、断网读已下载章节正常。
3. 任一环节异常都属回归：2d 只删了脚本侧的桩并新增分岔，原生分支的语义零改动。

### 7. 书城接线（2e）的装机验证项

1. 导入一条含 exploreUrl 的脚本书源 → 书城右上角切换器出现它 → 选中：分类胶囊换成该源的
   exploreUrl 条目（原生源的 kinds 不再显示）。
2. 点脚本源的分类胶囊 → 分类选书页出书（走 `ScriptBookParser.getKindBook` 的 URL 规则串渲染）；
   该页翻页与「到底」判定同原生。
3. 该源成为默认源后杀进程重启：首帧可能先显示内置原生源（assets 猜测），随后被纠正回脚本源
   ——纠正前后的书库内容都各自成立，不闪退、不混源。
4. 「已启用 Y 个」计数与切换器条数一致（2e 起无差集）。

## 人工装机验证清单（:js 沙箱执行器，2026-09-09）

> **设备侧已跑通两轮**：`:lib_book_source:connectedDebugAndroidTest` 16 例（`QuickJsBridgeTest` 12 +
> `SandboxConnectionTest` 4）在 Pixel_8 AVD / Android 17 上全绿；**2026-09-10 在 IR-Device 实机 /
> Android 11 复跑一轮，16 例同样全绿**（真内核 + 真跨进程在另一 OS 版本上复证）。它锁住的是「真内核 + 真跨进程」这一层：
> 死循环被中断器打断、堆超限按「内存超限」归类而不是撞成普通运行时失败、深递归以栈耗尽结束而**进程还在**、
> 白名单外能力就地拒绝、bind → execute → 回调 → 断连自动重启，以及 host 回调发起方的 uid 确实落在
> 隔离区间。JVM 侧锁住的是「协议与判据」：`:lib_book_source` 全量用例覆盖跨进程契约的编解码、限值与
> 失败档案、host 白名单导出、守门客户端的准入与限流、回调路由与嵌套求值、纯计算的真实向量。
> **两侧都锁不住下面六条**（与小节 1~6 一一对应；小节 7 是临时验证的登记项，不属此列）：release 混淆下的 `.so` 加载、真机型上病态脚本的
> 退化路径、Hilt 接线（转子与客户端是否同一对象）、抓包层面的凭证与私网边界、真实站点的脚本行为与
> 请求频度，以及 `:js` 的常驻与功耗。

### 1. release 包里 .so 能加载（混淆 + 仅 ARM64）

装 `assembleRelease` 产物（构建期已核对：release 包内只有 `lib/arm64-v8a/libebook_js.so`，debug 另带
`lib/x86_64/`；**2026-09-10 复跑 `assembleRelease` 再次核实**：APK 内 `libebook_js.so` 仅 `arm64-v8a`
一份，dex 内四个 native 方法名与 `HostDispatcher` 描述符在，llvm-nm 确认 release `.so` 导出四个 JNI
符号与 `JS_Eval`），打开一条含 `@js:` 的脚本源并搜索。期望出结果。失败形态有两种，必须分清：
`UnsatisfiedLinkError` / 脚本段全报执行失败 = keep 规则缺；报「需脚本沙箱执行器」= Hilt 没装配上（第 3 条）。

**产物是未签名的**（`module_app/build.gradle.kts` 没给 release 配 `signingConfig`，
`assembleRelease` 出 `module_app-real-release-unsigned.apk`，直接 `install` 会被系统拒），
装机前先自签：`apksigner sign --ks ~/.android/debug.keystore module_app-real-release-unsigned.apk`。

混淆侧的名字面已在构建期核过，不必重复验证：release 的 `mapping.txt` 里 `HostDispatcher` 与
`QuickJsNative` 类名未改，`handle(String,String)String` 仍是原名，`nativeCreate`/`nativeEval`/
`nativeReset`/`nativeDestroy` 四个名字也在 release dex 内（`FindClass` 用的
`Lcom/ebook/source/sandbox/HostDispatcher;` 描述符同样在）。复核时别只查 `mapping.txt`：
四个 native 方法**不在**里面——AGP 自带的 `-keepclasseswithmembernames class * { native <methods>; }`
不改名就不写映射行，dex 里有名字为准。**本条剩下的只有真机上「`.so` 装不进进程」
这一种失败**——它没有任何构建期信号，只有装机能判。

### 2. 恶意/病态脚本不打穿 App

三类失败档案本身已由 `QuickJsBridgeTest` 在设备上锁住（死循环→超时、一路分配→内存超限、
深递归→栈耗尽**且进程还活着**，后两条各追加一次真求值断言进程未亡）。这里要人工确认的是
**用户看到的那句话**与真机型行为：

自己写一条源：`content` 规则为 `@js:while(true){}`、再一条 `@js:let a=[];while(1)a.push(a);`、
再一条 `@js:function f(){f()}f()`。期望：分别得到「执行超时」「内存超限」「栈溢出」三类失败提示，
App 不闪退、书架与其余源照常可用。`adb logcat` 里能看到 `:js` 进程被销毁后重新拉起。

isolated 不再需要 `dumpsys` 手工核对：`SandboxConnectionTest` 在 host 回调里读发起方 uid
（`Binder.getCallingUid()`），Android 14+ 用 `Process.isIsolatedUid` 判是否落在隔离区间。
**剩下的机型项是退化路径**：机型不认 isolated 时的处理**必须日志可见**，静默降级算不合格。

**恶意脚本测试集（四类攻击）的设备侧归属（登记项）**：JVM 只能断言协议与判据，跑不了真内核，
故死循环、内存耗尽、无限递归三类在**真内核 + `isolatedProcess`** 下的执行属
`./gradlew :lib_book_source:connectedDebugAndroidTest`（或人工装机）项——`QuickJsBridgeTest` 已分别
以「超时中断」「MEMORY 归类」「STACK 且进程还在」锁住；代理滥用一类不依赖内核，其脚本面向入口的
拒绝判据由 JVM 锁住（`JsCallbackProxyTest`，以及生产装配 `SandboxScriptJs` 真入口的
`SandboxScriptJsTest.白名单外的 host 被拒且不发出请求`——白名单外 host 一律被拒、零外呼、不烧配额），
地址判据（内网/混淆写法/DNS 重绑）见 `JsNetworkGuardTest`，真机抓包层面的「不出私网、不带凭证」
按本节第 4 条验。四类均已有既有断言覆盖，本仓不另立重复用例。

### 3. 装配转子是同一个对象（JVM 测锁不住的那条）

在含 JS 的源上跑通一次详情→目录→正文。期望：**没有**「主进程没有正在进行的脚本任务」这句。
出现它即 `provideJsSandboxHost` 里转子与客户端不是同一份（不是脚本或站点的问题，改规则没用）。

### 4. 沙箱请求不带凭证、不出私网

抓包（或 `adb shell dumpsys netstats`）看脚本 `java.ajax` 发出的请求。期望：无 `Authorization` 头、
无本 App 的 cookie；对 `127.0.0.1`/`192.168.x`/`10.x` 的脚本请求被拒且日志给出拒绝原因。
顺带确认源 host 白名单：脚本请求一个规则里没出现过的第三方 host，应被拒并明示。

**本条只验脚本自己发起的外呼**（`java.ajax`/`load`/`post`/`responseCode`）。URL 选项 `js` 改写出的地址
由取文层按原路径发出，不经这道守门（已登记为遗留项），不要指望本条把它一并验掉；要验它就单独记一条
「脚本经 `js` 选项让取文层去打内网」的实验，把结果写回结论里。

### 5. 真实源覆盖：62% 含 JS 的那一批到底能跑多少

挑 5~10 条**已知含 JS** 的社区源（登录类除外）逐个搜索 + 打开一本书读正文。记录：成功数、失败原因分布
（超时/内存/白名单/内核 ReferenceError——`jsLib` 缺失会落在这里）。已知必然跑不动的一类：依赖
`java.ajaxAll`/`java.connect` 的源（批量外呼与「跟随重定向取真实地址」未实现，垫片只留了可诊断的
拒绝桩，一调即 `UNSUPPORTED_API`）。
这是白名单定版与限值调校的唯一实测反馈来源，结论回填规格 §11 对应条目。

### 6. 进程与功耗

读 20 章正文，观察 `:js` 进程是否常驻、有无反复冷启动（`dumpsys activity processes`）。
若冷启动开销在慢机型上明显，登记为「执行器复用策略待调校」，不就地改成常驻。

### 7. JS 重源真机临时验证（登记项：验完即删，不落永久夹具）

**2026-09-10 已执行并跑通。** 从社区语料（642 条）取 `📂网阅小说`（`bookSourceUrl = https://book15.net/`）
的 `exploreUrl`——一个完整的 `<js>…</js>` 发现页脚本（函数式构建条目数组、字典查表、循环拼接、
读外层注入的 `infoMap`、无任何 host 调用）——经 `JsRuntimeBridge` 在真机（IR-Device / Android 11）
上交给真 QuickJS 内核求值。两条断言全绿：`infoMap` 指定「男生频道」时产出 1 个频道选择器 + 7 个
分类条目（`default` 为男生频道、首个分类 `玄幻奇幻` → `/books/list-t-3.html?page={{page}}`、末个
`历史军事`），`infoMap` 无该键时回落「小说排行」产出 1 + 3 条（`总人气榜`…`周人气榜`）；
原始返回值与规则预期逐字相符。该用例是**临时**的：跑通后即删除，未提交、未留夹具。

**这类源一律走「真机临时验证」，不转金标准离线夹具**：JS 重源的实测站会失效（域名过期、规则随
站点改版），把载荷冻成永久夹具只会在若干月后变成一条永远红、没人能修的用例。可复现的那一半
（协议、判据、限值与垫片语义）已在 JVM 侧锁住；真源 JS 的独特价值只在「真内核跑得动这一类脚本」，
那是一次性证据，不是回归资产。需要重跑时照本节的法子重写一份临时用例即可（`QuickJsBridgeTest` 的
`assumeTrue(QuickJsNative.loaded)` + `JsRuntimeBridge` 形状；用例名不带空格）。

## 人工装机验证清单（书城分类区块与脚本形态发现页，2026-09-10）

> 同日两处修复各有一半已被 JVM 锁住（`ExploreUrlFormatTest` 断言 `<js>` 串切出**空清单**，
> `ScriptRealSourceGoldenTest` 用真番茄载荷同时锁「切零条目」与「词法登记 `EXPLORE_URL_SCRIPT`」）。
> 装机要确认的是**另一半**：这条源在真 UI 上现在长成什么样——切分与登记都正确，页面仍可能以另一种
> 方式难看（整页没有分类胶囊 vs. 一堆空区块），而这只有 Compose 真渲染才知道。

1. **导入 `番茄（发现）`（`bookSourceUrl = https://fanqienovel.com`）并在书城选中它**：期望分类区
   **一条胶囊都没有**，页面进「该源暂无分类入口」一类的空态，不闪退、不转圈不停。
   修复前的真实表现是 105 条空标题条目 → 105 次 `404：https://fanqienovel.com/<JS 源码行>` →
   `LazyColumn` 抛 `IllegalArgumentException: Key "" was already used` 崩在主线程。
   顺带确认其余四条链路没被连带砍掉：同一本书在该源下仍可搜索、开详情、看目录、读正文。

2. **重复/空 `kindName` 不再撞 key**（`BookstorePage` 的分类区块已改成
   `itemsIndexed(kindBooks, key = { index, _ -> index })`）：找一条 `exploreUrl` 里两个条目都没写
   `名称::` 的源（文本格式的裸 URL 行即如此），书城分类区应照样渲染。期望：区块正常出书，
   滚动与换源/下拉刷新无异常。
   **本条同时是第 1 条的回归保险**——key 含位置之后，空标题条目本身不再崩，因此若哪天 `<js>`
   识别被改动、条目重新漏进切分，症状会从「崩溃」降级成「一堆空区块」，只能靠肉眼发现。

3. **该源的导入报告**：`EXPLORE_URL_SCRIPT` 这条能力警示**目前不会出现在任何 UI**（`ScriptUnsupported`
   是 `internal`，逐项上 UI 是已登记的遗留项）。导入后不必去找它，也不要因为「没提示」判本轮修复失效。
