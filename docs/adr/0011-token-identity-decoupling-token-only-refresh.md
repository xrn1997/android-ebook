# token 与身份解耦：刷新仅凭证、access 只驻内存、过期单响

将「认证凭证（credentials）」与「用户身份（identity）」在会话管理里彻底解耦：
刷新端点契约改为**只换双 token、不回填 user**；access token 只驻内存；会话过期改为
**事件唯一出口**（Toast 只响一声）。

## 动机

* **轮换语义被错误复用**：此前 `SessionTokenRefresher` 用「建立会话」的 `saveSession`
  来做「轮换凭证」，把双 token 与身份捆在一个写操作里。一旦刷新响应不含完整用户，
  就会用空身份整段重建会话、抹空昵称/头像/uid——这是 `saveSession` 语义过载的直接隐患。

* **契约与规格漂移**：Android 规格（login-modernization-spec）将 `POST /api/auth/refresh`
  设计为只回 `{token, refresh_token}`；但 ebook-server 因登录/刷新共用
  `issueTokens`，实际把完整 `user` 一并下放。以服务端为线契约基线，需两端统一到
  「刷新只续凭证、登录才建身份」的行业惯例（OAuth2/OIDC refresh 响应不含 profile）。

* **access token 落盘违背最小暴露**：随每个请求携带的短命票写进明文 SP，扩大被攻击面；
  该恢复会话的是 refresh token，access 只需驻内存。

* **过期双响**：刷新失败时既发 `SessionExpired` 事件（MainActivity 统一处置），又向
  请求方返回失败、请求方再 Toast，同一次过期提示两条。

## 决策

1. **`UserSessionManager`** **新增** **`rotateCredentials(accessToken, refreshToken)`**：
   只更 access（内存，经 TokenHolder）与 refresh（落盘），**绝不触碰身份字段**与
   兼容 `SP_*` 键。登录/注册仍走 `saveSession`（身份 + 双 token）。刷新不再复用
   `saveSession` 整段重建会话（ADR-0010 决策 4 更新）。
2. **刷新端点契约收紧为纯凭证**：ebook-server `POST /api/auth/refresh` 改返回
   `{token, refresh_token}`，去掉 `user`；login/register 仍返回 `user`。
   Android 侧 `SessionTokenRefresher` 改调 `rotateCredentials`，不再读取/映射 refresh
   响应中的用户字段（对应 mock 资产 `user_refresh_token.json` 去掉 `user`）。
3. **access token 只驻内存**：`saveSession`/`loadSessionFromSp` 不再落盘/读取
   `KEY_TOKEN`；冷启动 token 为空，首个请求触发 A0230 由 refresh token 静默轮换。
   refresh token（+身份字段）继续持久化。
4. **会话过期事件唯一出口（单响）**：`CoroutineAdapter` 刷新失败时发
   `SessionExpired` + 返回带标记的 `SessionExpiredException`；各调用方
   `onFailure` 见该标记**只记 Logger、不再弹 Toast**，避免与会话过期提示重复。
5. **refresh 落盘加密暂缓**：本轮只做正确性修复；refresh token 加密落盘
   （Keystore + 迁移）另立 ADR 跟进，不复用本次改动边界。

## 被拒方案

* **保留服务端回 user + saveSession 轮换**：厂商界面自愈看似方便，但 refresh 回填身份
  违反 OAuth 惯例，且让「轮换」与「建会话」永久耦合、掩盖 `saveSession` 过载缺陷。

* **调用方自行判 A0230 分支**：把过期特判散布到各 ViewModel，正是本次要消灭的债；
  改用单一标记异常集中识别。

* **refresh 继续走带 AuthInterceptor 的共享客户端**：端点未鉴权，Extra 头无害，
  属卫生问题；本轮不改客户端客户端（不扩散改动面）。

## 下游影响

* `lib_book_common`：`UserSessionManager`/`AndroidUserSessionManager` 增
  `rotateCredentials`；`SessionTokenRefresher` 改用之并移除 `toUserSession` 依赖。

* `lib_ebook_api`：`CoroutineAdapter` 增 `SessionExpiredException` +
  `isSessionExpiredHandled()`；过期路径只发事件 + Logger。

* 各调用方（module\_login / module\_book / module\_me）：`onFailure` 见过期标记时
  静默仅日志，不重复 Toast。

* mock 资产：`user_refresh_token.json` 去掉 `user`。

* 测试：`UserSessionManagerTest` 增 `rotateCredentials` 身份保留/未登录 no-op 用例；
  `FakeUserSessionManager` 实现并记录轮换。

* ebook-server：`Refresh` 改返回纯凭证载荷，登录保持 `TokenPair`（带 user）；
  服务端并在其刷新接口契约文档中补述「refresh 仅凭证」。

## 遗留

* refresh token 加密落盘（Keystore + 迁移）：独立 ADR 跟进。

* access token 只驻内存后，冷启动首帧多一次静默轮换（已接受）。

* 跨设备改昵称/头像不再随刷新自愈（登录时带回最新身份）——B 规范的预期代价。

