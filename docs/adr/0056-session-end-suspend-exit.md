# 清会话契约收进 UserSessionManager 的挂起出口 endSession

Status: accepted

`clearSession()` 的三条调用契约（同步 SP `commit` 不落主线程、清理段不可取消、返回 false 表示旧 refresh token 可能残留）此前只写在 KDoc 里，登出/改密/会话过期/调试宿主四个调用点各自复制八行 `withContext(Dispatchers.IO + NonCancellable)` + 失败警告模板。决定在 `UserSessionManager` 接口上加一个挂起出口 `endSession()`，把这三条纪律收进会话模块一处；`clearSession()` 仍是唯一的清会话实现，`endSession()` 只包不改。

## 背景

- 契约写在被调方 KDoc 里、由调用方逐处兑现，是「文档说一套、每处复制走样一套」的典型形态：四个调用点的模板各写各的注释与警告文案，新增第五个调用点没有任何东西阻止它漏包 `NonCancellable` 或把同步 commit 落在主线程。
- 调用方真正想要的语义是「清会话并继续我的收尾」——它们既不消费 `clearSession()` 的布尔返回值，也不该各自重试落盘失败；布尔门控只有换址流程（ADR-0052）真正需要。
- 旧债一处：module_login 调试宿主的退出按钮只 `SPUtil.remove(SP_IS_LOGIN)`，`user_session` 文件与内存态里的旧凭证一直残留（测试缺口台账登记在案）。

## 决定

1. **接口新增 `suspend fun endSession()`**，KDoc 即契约：内部派发 `Dispatchers.IO`（同步 commit 不落主线程）、清理段包 `NonCancellable`（页面/ViewModel 在收尾期间被销毁不得取消清会话）、`clearSession()` 返回 false 时只 `Logger.w`（点名「旧 refresh token 可能残留」）不抛不返回值——补清重试归启动兜底标记（ADR-0052），不在出口中断调用方的收尾。
2. **`clearSession()` 保持唯一清会话实现**（AGENTS.md 硬约束不变）：`endSession()` 只是在它外面包一层线程/取消/日志纪律，不新增任何清理动作；三处镜像（`user_session` SP、`spUtils` 兼容键、ProfileRepository 身份流）仍由 `clearSession()` 一并覆盖。
3. **需要布尔门控的调用方继续直调 `clearSession()`**：换址/清除配置流程要按 false 分支「失败不清会话、不催重启」并保留 `session_reset_pending`；启动兜底（`BookApplication.applyPendingAddressSessionReset`）必须赶在首个请求前同步完成，同为直调。这两处不经 `endSession()`。
4. **调用点迁移**：登出（SettingViewModel）、改密（ModifyPwdViewModel）、会话过期（MainActivity）、module_me 调试宿主登出四处的八行模板收敛为一行 `userSessionManager.endSession()`；module_login 调试宿主退出按钮从只删兼容键改为 `endSession()`，清掉旧债。

## 边界

- `endSession()` 不吞异常：`clearSession()` 抛出的异常照旧上抛（启动兜底对抛异常同样保留标记重试，语义不变）。
- 线程/取消契约的**测试断言**只住 `AndroidUserSessionManagerTest`（取消后仍清干净、commit 不落调用线程、false 不抛）；调用方测试只断言编排（先作废服务端再清本地、连点闸门、覆盖层）。

## 后果

- 新增「清完即走」的调用点只需一行 `endSession()`，三条纪律不再可能漏包；接口 KDoc 是契约的唯一事实源。
- SettingViewModelTest 的三遍重复线程断言收敛为零，线程断言集中在真实现一处。
- 会话模块外再无「自己包 IO + NonCancellable 再调 clearSession」的写法。

## 被否方案

- **继续只靠 KDoc 约束调用点**：被否。四个已复制的模板就是约束失败的证据；第五个调用点没有编译期或类型层面的提醒。
- **给 `clearSession()` 直接加协程包装**：被否。它是同步 API，换址流程与启动兜底都依赖其布尔返回且已在自己的上下文里；改签名会把两处不需要挂起的调用方一并拖进协程语义。
- **`endSession()` 返回布尔或抛异常**：被否。调用方无一消费该值；抛异常会把登出/改密的用户可见收尾炸断在半路，落盘失败的重试责任在启动兜底标记而不在调用方。

锚点：`lib_book_common` 的 `UserSessionManager.endSession`（接口契约 KDoc）、`AndroidUserSessionManager.endSession`（IO + NonCancellable 实现与 false 日志）、`AndroidUserSessionManagerTest`（取消完成/线程/false 不抛三条读数）、`SettingViewModel.launchAddressChange`（仍直调 `clearSession()` 的布尔门控路径）、`BookApplication.applyPendingAddressSessionReset`（启动兜底直调）；`docs/architecture.md` §5。
