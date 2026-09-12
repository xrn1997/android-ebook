# API 契约以客户端生态为准：RespDTO 信封 + 五位业务码 + HTTP 恒 200

ebook-server 原设计了自己的 `{code: Int(200/400/401), message, data}` 信封，与客户端既有管道不一致。决定弃用服务端原生信封，改说客户端 lib_common 的既有契约：`{code: String, error, data}`（字段名逐字对齐 `RespDTO`），成败由五位业务码承载（`ErrorCode` 体系，如 "00000" 成功、"A0210" 密码错误）；HTTP 状态码恒为 200，仅当业务层无法响应（进程崩溃/网关故障）时才出现非 200。

## 动机

- 客户端管道已锁死在这套契约上：`CoroutineAdapter.safeApiCall` 以 `ErrorCode.SUCCESS.code`（"00000"）为唯一成功判据，其余码包成 `ApiException` 抛出并**把五位码原样保留在异常上**——契约把码留全，调用方要按细粒度码分支随时可以，但目前尚无这样的分支点：用户面一律取服务端下发的原文文案展示，码只用于日志排查。A0230（access token 过期）由这一层集中处置（单飞静默刷新 / 会话过期事件），不在各 ViewModel 里散判。改客户端等于重构 lib_common + 全部调用方，且 lib_common 走迷你构建联动，改动成本跨两个仓库。
- ebook-server 是未发布的新代码，改信封只是改一个 `model.Response`，成本不对称。
- 废弃的是 Spring Cloud 技术栈，不是契约——`RespDTO`/`ErrorCode` 住在 lib_common，与后端实现语言无关。

## 权衡

- **HTTP 恒 200（业务码派）而非正典 REST 状态码派**：Retrofit 对非 2xx 直接抛 `HttpException`，响应体信封到不了 `CoroutineAdapter`，服务端错误文案无法直达 Toast（用户会看到"操作未授权"而非"密码错误"）。代价：curl/网关/监控按状态码看不到业务成败，需解析 body——对单一移动客户端 + 自维护小后端的场景可忽略。
- **客户端保留两套异常词汇不合并**：`ApiException`（String 业务码，服务端文案可直达用户）与 `ResponseException`（Int 传输码，友好兜底文案），分工正对应两层语义。
- **Go 端不发明新错误码**：建 `pkg/errcode` 镜像 `ErrorCode` 认证相关子集（A0111/A0201/A0210/A0230/A0240/A0132/A0153 等），保持两端词汇表同源。

## 下游影响

- `ebook-server`：`model.Response` 改 `{code: string, error: string, data}`；`BadRequest/Unauthorized/InternalError` 等 helper 全部改为 HTTP 200 + 业务码；新增认证端点一律引用 `pkg/errcode`。
- `android-ebook`：`lib_ebook_api`/`CoroutineAdapter`/`ErrorCode` 零改动；仅需对齐路径与 `data` 载荷字段（登录响应补 userId/nickname/avatar）。
