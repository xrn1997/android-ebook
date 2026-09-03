# 静默刷新接缝与会话过期事件收口

access token 过期（业务码 A0230）的处置落地为：**网络层收口检测 → 单飞静默刷新 →
成功重放原请求一次；刷新失败发「会话过期」事件 → 订阅方清会话 + 提示 + 跳登录页**。
触发点选在业务码层（`CoroutineAdapter`）而非 OkHttp 层；刷新能力经 `TokenRefresher`
接缝由上层注入；事件经 `SessionEventBus`（SharedFlow）统一收口，调用点零分支。

## 动机

- 服务端 JWT 过期返回 **HTTP 200 + 信封内 A0230**（ADR-0007 信封约定），
  OkHttp `Authenticator` 只看得到 401 类状态码，看不到业务码——触发点只能在业务码层。
- 刷新同时需要「刷新端点」（lib_ebook_api，下游）与「会话持久化」（lib_book_common，
  上游）：依赖方向相悖，直接实现会产生反向依赖。
- access 2h 过期是高频事件，逐调用点处理（旧 A0230 分支）必然遗漏且行为不一致；
  延续 ADR-0004 的事件收口模式。

## 决策

1. **`TokenRefresher` 接口定义在 lib_ebook_api，实现在 lib_book_common**
   （`SessionTokenRefresher`，经 SessionModule @Binds 注入）——底层定接缝、上层给实现，
   反转依赖方向而不违反分层。
2. **单飞互斥**：实现内 `Mutex` 串行；进锁后先比对「触发过期时的 token」与
   `TokenHolder` 当前 token，不同即并发请求已完成刷新，直接复用——避免 N 个并发
   过期打 N 次刷新（服务端轮换语义下旧 refresh 已作废，重复刷新反而误伤）。
3. **刷新旁路 `CoroutineAdapter`**：`SessionTokenRefresher` 直调 `UserService`，
   否则刷新失败（A0230）会再次触发刷新，形成死循环。
4. **轮换只更凭证（`rotateCredentials`）**：刷新成功调用新增的
   `UserSessionManager.rotateCredentials`（新 access 同步 TokenHolder、新 refresh
   落盘），**不再复用 `saveSession` 整段重建会话**——token 与身份解耦，
   refresh 端点契约改为只回 `{token, refresh_token}` 不含 user（见 ADR-0011）。
   旧 refresh 服务端已作废，不立即替换则下次刷新必失败。
5. **`SessionEventBus` 定义与发射在 lib_ebook_api**（`MutableSharedFlow`，
   tryEmit + 1 缓冲，重复过期事件允许丢弃，绝不阻塞请求线程）；
   **订阅方是 module_main 的 `MainActivity`** 而非规格原文写的
   `SplashActivity`——Splash 启动后即结束，撑不起长时订阅；
   MainActivity 是登录后最长驻留的宿主。处置三件套：清会话 + Toast + TheRouter 跳登录页。
   **过期单响**：`CoroutineAdapter` 刷新失败时发事件 + 返回 `SessionExpiredException`
   标记；各调用方 `onFailure` 见标记只记日志、不重复 Toast（见 ADR-0011）。
6. **重放仅一次**：重放结果不再参与刷新判定（再次 A0230 直接透传失败），避免刷新风暴。
7. **配套修复**：`CoroutineAdapter` 由 `object` 改为 @Singleton 类（静态形态无法携带
   接缝依赖），三个仓库（User/Comment/Modify）调用点改注入；`RetrofitBuilder` 显式注册
   `converter-kotlinx-serialization`（此前只挂 Scalars，`RespDTO<T>` 在真机链路根本
   无法解析——mock 开发期掩盖了该缺陷）。

## 被拒方案

- **OkHttp Authenticator**：看不到业务码（见动机），出局。
- **刷新逻辑放 module_login 的 UserRepository**：只有登录模块的调用受益，评论/书架
  等其它模块的请求过期无人接管；且 module 层无法被下层网络代码引用。
- **逐调用点 A0230 分支**：本次改造前的形态——已在 LoginViewModel 删除，债不重建。
- **拦截器读响应体判断过期**：需重包装响应流，且清会话/跳转属 UI 层职责，
  塞进网络层违反分层（沿用 ADR-0008 权衡结论）。

## 下游影响

- `lib_ebook_api`：新增 `auth/TokenRefresher.kt`、`auth/SessionEventBus.kt`；
  `CoroutineAdapter` 类化 + A0230 处置 + 过期单响（`SessionExpiredException`）；
  `RetrofitBuilder` 注入 Json 并注册 kotlinx 转换器。
- `lib_book_common`：新增 `SessionTokenRefresher`、`rotateCredentials`；
  `SessionModule` 增 @Binds（token 与身份解耦见 ADR-0011）。
- `module_main`：`MainActivity` 订阅会话过期事件；新增 `session_expired` 文案。
- 已知边界：未登录/无 refresh token 时请求收到 A0230 会再发一次过期事件，
  处置幂等（再清一次会话 + 再跳登录页），可接受，不做防抖。
