# 测试覆盖待办

来自原《架构改进计划》未完成项的承接清单（2026-08-05 由 /grill-with-docs 会话迁移）。

- [x] 为 `BookRepository` 添加单元测试（loadBookContent / saveBookContent / updateChapterCache / bookShelfEvents）
  —— 已由 `lib_book_common/src/test/.../repository/BookRepositoryTest.kt` 覆盖（手写 Fake DAO，纯 JVM），
  另含 addToShelf/removeFromShelf 级联、getCachedChapterUrls 短路、getAllBooksWithDetails 孤立清理与章节排序
- [x] 为 `UserSessionManager` 补充 token 同步 TokenHolder 的测试用例
  —— 已完成：`FakeUserSessionManager` 已注入并同步 `TokenHolder`，`UserSessionManagerTest` 已含
  `saveSession should sync token to TokenHolder` / `saveSession with empty token should clear TokenHolder` 等用例
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
- [ ] 为下载队列的失败重试/出队语义添加测试
  —— `DownloadService.downloading` 的不变式（重试 `RETRY_TIMES` 次耗尽后必须 `deleteTask` 出队、
  暂停中断时不出队、解析占位文案与空正文都不得入库）目前**无自动化覆盖**，只能靠人工装机验证。
  障碍：重试循环与 `Handler.postDelayed`、前台通知、`serviceScope` 绑在 Service 上。补测前需先把
  「取任务 → 重试 → 出队/入库」抽成可注入假仓库与假时钟的纯挂起函数（或改用 Robolectric + 假 `DownloadRepository`）
- [ ] 为 Compose 页面添加 UI 测试
- [ ] `AuthInterceptor` 测试归属 lib_common（android-practice 仓库，随认证体系对齐后不再在本仓库维护）
