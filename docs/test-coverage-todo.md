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
  —— 已由 `lib_book_common/src/test/.../analyze/source/ChapterPageMatcherTest.kt` 覆盖（13 例，纯 JVM）：
  判定基准为目录页原始章节 URL、只对候选链接剥一次后缀（保留扩展名 + 去扩展名兜底）。
  锁死两类回归：「章节号写在连字符后」的站点相邻章不得被判为同章（否则串章），
  以及「第 1 页也带后缀」形态当前宁漏页不串章的取舍（将来上书源分页模板时需同步改该断言）
- [x] 为列表（分类页/搜索页）分页判定添加回归测试
  —— 两处纯逻辑已覆盖：`lib_book_common/src/test/.../analyze/source/ListPageUrlTest.kt`（8 例，纯 JVM）
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
  **仍未覆盖**：`BookImportViewModel` 的暂停门（IO 线程置门、主线程 `getAndSet` 了结、连点幂等）与处置框交互
  ——需要 Robolectric + 假 importer，本轮按人工装机验证处理（清单见 ADR-0023「验证」）
- [ ] 为下载队列的失败重试/出队语义添加测试
  —— `DownloadService.downloading` 的不变式（重试 `RETRY_TIMES` 次耗尽后必须 `deleteTask` 出队、
  暂停中断时不出队、解析占位文案与空正文都不得入库）目前**无自动化覆盖**，只能靠人工装机验证。
  障碍：重试循环与 `Handler.postDelayed`、前台通知、`serviceScope` 绑在 Service 上。补测前需先把
  「取任务 → 重试 → 出队/入库」抽成可注入假仓库与假时钟的纯挂起函数（或改用 Robolectric + 假 `DownloadRepository`）
- [ ] 为 `CommentRepository` 添加单元测试（当前**零覆盖**）
  —— 本轮补的空聚合键守卫无自动化覆盖：`getComments(emptyList())` 必须**不发请求**直接返回空结果，
  否则会命中契约（M2 spec §3.2.1）里「`comment_keys` 缺失 → 返回全局最新列表」的分支，
  章评论区在旧数据 `commentKey` 为 null 时会显示全站最新评论。该分支此前正是靠一条与契约相反的
  注释掩盖着（`CommentNetwork` 的 `.ifEmpty { null }` 翻译把空列表送进了这个分支）。
  障碍：构造 `CoroutineAdapter` 需要 `TokenRefresher`/`SessionEventBus`/`TokenHolder` 三个假件，
  仓内已有先例刻意不为此引入 Robolectric（见 `SessionTokenRefresherTest` 的类注释）。
  补测时优先锁「空键不发请求」（守卫在触碰 adapter 之前短路，假件可为惰性构造），
  再考虑 `queryCommentPage` 的空 data 兜底与 `migrateMyComments` 的条数透传
- [ ] 为 Compose 页面添加 UI 测试
- [ ] `AuthInterceptor` 测试归属 lib_common（android-practice 仓库，随认证体系对齐后不再在本仓库维护）

## 本轮（2026-09-03 评审）明确延后的技术债

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

## 人工装机验证清单（本轮未提交改动，2026-09-06）

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

### 4. 评论链路（M2；空键守卫为本轮修复、无自动化覆盖）

1. 章评论区**空聚合键**入口应显示空态、不发请求。注意：mock 资产的评论全带 `comment_key`，
   此路径在 mock 下不可达，需连真实后端用 `comment_key` 为 null 的旧数据验
   （契约 §3.3 允许旧数据为 null）。
2. mock 构建（`assembleMockDebug`）下走一遍发表/删除/长按删本人评论。
3. 详情页进「编辑作品信息」（新页面 `EditBookMetaActivity`）：改主匹配名 → toast 的迁移条数
   应等于本人该键的全部行数；返回章评论区，旧键评论仍在（读并集）。
   键重算与切主键逻辑已由 `updateMatchMeta` 两条测试锁死，这里只验 UI 接线与文案。

### 5. 导入判重与处置框

6 条见 ADR-0023「验证」节，不在此重复。

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
