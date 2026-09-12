# 静默刷新接缝与会话过期事件收口

access token 过期（业务码 A0230）的处置落地为：网络层收口检测 → 单飞静默刷新 → 成功重放原请求一次；刷新失败发「会话过期」事件 → 订阅方清会话 + 提示 + 跳登录页。触发点选在业务码层而非 OkHttp 层；刷新能力经 `TokenRefresher` 接缝由上层注入；事件经 SharedFlow 统一收口，调用点零分支。这样做是因为服务端 JWT 过期返回 HTTP 200 + 信封内业务码，OkHttp 层看不到业务码，且刷新同时需要下游端点与上游会话持久化，依赖方向相悖，需接缝反转。

## 动机

- 服务端 JWT 过期返回 HTTP 200 + 信封内 A0230（服务端响应统一包信封、成败由五位业务码承载、HTTP 恒 200），OkHttp `Authenticator` 只看得到 401 类状态码，看不到业务码——触发点只能在业务码层。
- 刷新同时需要「刷新端点」（lib_ebook_api，下游）与「会话持久化」（lib_book_common，上游）：依赖方向相悖，直接实现会产生反向依赖。
- access 2h 过期是高频事件，逐调用点处理必然遗漏且行为不一致；用 SharedFlow 事件统一收口，调用方零感知。

## 决策

1. **`TokenRefresher` 接口定义在 lib_ebook_api，实现在 lib_book_common**（`SessionTokenRefresher`，经 SessionModule @Binds 注入）——底层定接缝、上层给实现，反转依赖方向而不违反分层。
2. **单飞互斥**：实现内 `Mutex` 串行；进锁后先比对「触发过期时的 token」与 `TokenHolder` 当前 token，不同即并发请求已完成刷新，直接复用——避免 N 个并发过期打 N 次刷新（服务端轮换语义下旧 refresh 已作废，重复刷新反而误伤）。复用判定**不得要求触发 token 非空**：access token 只驻内存、冷启动恒为空，而并发 A0230 恰恰只发生在冷启动这条路上，加这道非空守卫会让它在唯一需要它的场景里永不生效。串行化本身也是正确性屏障——服务端刷新时硬删旧 refresh，两个请求拿同一份旧值并发刷新，后者必失败。
3. **刷新旁路 `CoroutineAdapter`**：`SessionTokenRefresher` 直调 `UserDataSource.refreshToken` 拿裸 `RespDTO`——否则刷新失败（A0230）会再次触发刷新，形成死循环。仍走 DataSource 抽象而不是自建 Retrofit：基址拼接不在这里重复一份，独立调试宿主的 mock 绑定也才覆盖得到这条链路。
4. **轮换只更凭证（`rotateCredentials`）**：刷新成功调用新增的 `UserSessionManager.rotateCredentials`（新 access 同步 TokenHolder、新 refresh 落盘），不再复用 `saveSession` 整段重建会话——token 与身份解耦，refresh 端点契约改为只回 `{token, refresh_token}` 不含 user（刷新只续凭证、登录才回填身份）；旧 refresh 服务端已作废，不立即替换则下次刷新必失败。
5. **`SessionEventBus` 定义与发射在 lib_ebook_api**（`MutableSharedFlow`，tryEmit + 1 缓冲，重复过期事件允许丢弃，绝不阻塞请求线程）；**订阅方是 module_main 的 `MainActivity`** 而非 SplashActivity——Splash 启动后即结束，撑不起长时订阅；MainActivity 是登录后最长驻留的宿主。处置三件套：清会话 + Toast + TheRouter 跳登录页。**过期单响**：`CoroutineAdapter` 刷新失败时发事件 + 返回 `SessionExpiredException` 标记；调用方一律经 `lib_book_common` 的共享 `reportFailure` 上报失败，由它内部识别该标记并只记日志，其余情况才弹提示。会话过期提示以事件为唯一出口，同一次过期只响一声——调用方不得再自己 `isSessionExpiredHandled` 分支补 Toast：那是一条会被逐个 ViewModel 抄写并逐渐写歪的不变量。
6. **重放仅一次**：重放结果不再参与刷新判定（再次 A0230 直接透传失败）——避免刷新风暴。
7. **配套修复**：`CoroutineAdapter` 由 `object` 改为 @Singleton 类（静态形态无法携带接缝依赖），三个仓库（User/Comment/Modify）调用点改注入；`RetrofitBuilder` 显式注册 `converter-kotlinx-serialization`（此前只挂 Scalars，`RespDTO<T>` 在真机链路根本无法解析——mock 开发期掩盖了该缺陷）。

## 被拒方案

- **OkHttp Authenticator**：看不到业务码（HTTP 恒 200 + 信封内业务码），无法作为触发点。
- **刷新逻辑放 module_login 的 UserRepository**：只有登录模块的调用受益，评论/书架等其它模块的请求过期无人接管；且 module 层无法被下层网络代码引用。
- **逐调用点 A0230 分支**：本次改造前的形态，散布到各 ViewModel 必然遗漏，债不重建。
- **拦截器读响应体判断过期**：需重包装响应流，且清会话/跳转属 UI 层职责，塞进网络层违反分层。

## 下游影响

- `lib_ebook_api`：新增 `auth/TokenRefresher.kt`、`auth/SessionEventBus.kt`；`CoroutineAdapter` 类化 + A0230 处置 + 过期单响（`SessionExpiredException`）；`RetrofitBuilder` 注入 Json 并注册 kotlinx 转换器。
- `lib_book_common`：新增 `SessionTokenRefresher`、`rotateCredentials`；`SessionModule` 增 @Binds（token 与身份解耦：`rotateCredentials` 只更凭证、不触碰身份字段）。
- `module_main`：`MainActivity` 订阅会话过期事件；新增 `session_expired` 文案。
- 已知边界：未登录/无 refresh token 时请求收到 A0230 会再发一次过期事件，处置幂等（再清一次会话 + 再跳登录页），可接受，不做防抖。
