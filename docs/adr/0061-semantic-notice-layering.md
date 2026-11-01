# 宿主只发语义结论：文案映射归页面，登录发码倒计时收单主

Status: accepted

宿主（ViewModel / 控制器）原先既判断「发生了什么」、又直接取字符串资源说给用户听，登录两处还各演一套 60 秒倒计时。
决定把用户可见的句子与计时从宿主里移出：宿主只发**语义结论**，页面用**穷尽 `when`** 映射成句子（优先进
`strings.xml`，通用失败沿用异常自带的用户可读消息）；登录发码的 60 秒窗口收进 `module_login` 的
`CodeResendCountdown` 单主。
约定**前向生效**：本决策点名的宿主照此写，其它存量不动。

## 背景

- **文案在宿主里既不可测、也不可译**：失败提示写成 `context.getString(...)` 后经基类的一次性命令通道下发，
  纯 JVM 测试观测不到那个通道（lib_common 里是 internal），「哪一类失败说哪一句话」只能装机看；切语言时
  宿主里缓存的旧句子也不会跟着刷新，带参模板（追加章数、迁移条数）散在 Kotlin 字符串里，`strings.xml` 收不到。
- **同一失败的口径会漂移**：搜索页与分类选书页原先各自逐字抄一遍「异常类型 → 文案」的分支，改一处忘一处
  不会编译失败，只会让两个页面说两种话。
- **登录倒计时逐字重复**：注册发码与忘记密码发码各写一套递减循环；一处改秒数、另一处没改就让某条路径绕过
  服务端 60 秒频控（A0241）。倒计时若放 Compose 局部状态，旋转即丢；两套任务并行还会双倍递减。
- **既有先例**：书源管理页早有「语义结论 + 页面映射」形状（`BookSourceViewModel.Notice` +
  `BookSourceManageActivity.noticeMessage`），本决策把它推广为约定，不发明新接缝。

## 决定

1. **宿主只发结论，句子归页面**：`FindNotice`、`BookDetailNotice`、`EditBookNotice`、`DownloadCenterNotice`
   与阅读器滚屏的 `ScrollEdge` 只带语义、数字与异常；页面侧 `findNoticeText` / `bookDetailNoticeText` /
   `editBookNoticeText` / `downloadNoticeText` / `onScrollEdge` 回调用不带 `else` 的 `when` 映射。
   理由：新增一档结论时映射处编译不过，不会退化成运行时静默少弹一条；页面句子优先进资源文件（可翻译），
   通用失败档沿用异常自带的用户可读消息、取不到才用资源兜底——这一档不随语言切换，换来的是不给每一类
   解析/网络异常各造一句资源。
2. **带 cause 的失败档走 `reportFailure`，其余回显走命令通道**：带 cause 的失败档在页面调共享的
   `reportFailure(cause, 文案)`（ADR-0025），由它收口「A0230 会话过期只记日志、不重复提示」；
   `ChaptersAppended` / `Saved` / `CommentsMigrated` / `StartRestricted` 这类非失败回显，以及
   `AddShelfFailed` / `RemoveShelfFailed` / `MigrateFailed` 这类无 cause 的固定失败/半成功，仍走基类
   `sendToast` 或页面 Toast，不混进失败上报。理由：异常还在页面手上，会话过期的判据才成立；固定失败没有
   异常、启动受限也没有会话语义，套 `reportFailure` 是语义污染。
3. **承载件是 `SingleNoticeSlot`**：StateFlow 让页面在重组、转屏重建后仍能读到未消费的结论；相等值（含同一
   实例）在 `consume()` 之前重复 emit 会被 StateFlow 判等吞掉，所以页面展示后必须立刻消费。理由：共享 Flow
   无重放时晚订阅即丢，而「转屏回来还该看见那条提示」是这类一次性结论的硬要求。
4. **登录发码倒计时收进 `CodeResendCountdown`**：`module_login` 内部的 `internal` 类，两个 VM 各持一个实例；
   `start(scope)` 同步置 60 再逐秒递减，重启先取消旧任务，状态留在 ViewModel 侧。理由：两处消费者同在
   `module_login`，放模块内共享处就是最小依赖；升 `lib_book_common` 只有出现跨模块消费者时才值得（YAGNI）。

## 边界

- **前向生效、不追溯**：只对点名的宿主生效，全仓其它 `context.getString` 存量在各自下次被改动时补齐。
  当前未迁移的存量清单不在本文件复述，以 [`docs/architecture.md`](../architecture.md) §3.5 为唯一事实源。
- **login 只抽倒计时**：`RegisterViewModel` / `ModifyPwdViewModel` 的其它提示仍在宿主里取资源，不在本决策范围。

## 后果

- 文案映射只住在各页面：搜索/分类选书共用 `findNoticeText`，详情、修键、下载中心各一处 `when`，阅读器滚屏在
  `ReadBookActivity` 组合期解析两句文案后交给边界回调。
- 结论可断言：`AddBookFailureTest` 在纯 JVM 锁分类与 cause 原样携带，`SingleNoticeSlotTest` 锁槽的
  可见/清零/判等吞掉/consume 后再发，`BookDetailViewModelSourceTest` 与 `CodeResendCountdownTest` 锁相关宿主的
  结论与时间口径；`BookDetailViewModelSourceTest` 与 `SourceSwitchViewModelTest` 因此不再需要 Robolectric。
- 页面展示后必须消费：漏调 `consumeNotice()` 的症状是重组时同一条提示重复弹，而不是丢提示。

## 被否方案

- **留在宿主里取 `getString`，只把句子搬进 `strings.xml`**：被否。命令通道仍然不可观测，分类错与少弹一条都
  只能装机发现；带参模板也仍然要宿主拼。
- **宿主直接弹 Toast，不经状态**：被否。旋转后命令随旧 ViewModel 一起丢；且基类命令通道在测试里观测不到，
  「哪类失败说哪句」无法回归。
- **倒计时放 Compose 局部状态**：被否。旋转丢进度，用户回来可以立刻再点，等于绕过频控。
- **把 `CodeResendCountdown` 升进 `lib_book_common`**：不必要。两个消费者都在 `module_login`；没有跨模块复用
  证据前上浮只会扩大共享面。

锚点：`lib_book_common` 的 `SingleNoticeSlot`（承载件）、`SingleNoticeSlotTest`（槽语义）；`module_find` 的
`FindNotice` / `findNoticeOf` / `FindNoticeSlot` / `findNoticeText`、`AddBookFailureTest`；`module_book` 的
`BookDetailNotice` / `bookDetailNoticeText`、`EditBookNotice` / `editBookNoticeText`、`DownloadCenterNotice` /
`downloadNoticeText`、`ScrollEdge` 与 `ReadBookActivity` 的 `onScrollEdge` 映射、`BookDetailViewModelSourceTest`；
`module_login` 的 `CodeResendCountdown` / `CodeResendCountdownTest`；`docs/architecture.md` 的「一次性结论与文案分层」
与「认证与会话」两节；同族先例 ADR-0025（失败上报唯一出口）。
