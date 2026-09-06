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

## Room v2→v3 覆盖安装验证（M1a）

前置：装的是改动前的包，且书架上同时有 ①至少一本本地导入的 TXT ②至少一本网络书源加进
书架的书。

1. 记下网络书的阅读进度与"已缓存 y/z"数字。
2. 覆盖安装改动后的包（不要清数据）。
3. 打开书架：本地书应全部消失，网络书仍在、进度与缓存数字不变。
   —— 本地书消失是设计如此（spec §2 决定 9：可再生数据不背兼容），不是 bug。
4. 重新导入那本 TXT：应在数秒内出现在书架上，点开能翻页。
5. `adb logcat -b crash` 应无 FATAL EXCEPTION。
6. `adb shell run-as <包名> ls files/books` 应看到以 32 位 md5 命名的目录，里面是
   c00000.txt、c00001.txt …
