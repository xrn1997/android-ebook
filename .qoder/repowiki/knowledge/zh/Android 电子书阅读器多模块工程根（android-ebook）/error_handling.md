## 1. 使用的系统/方式

本仓库采用**基于 Kotlin 标准 `Result<T>` 的统一响应封装**，配合服务端业务代码与自定义异常构成完整的错误处理链路。

- 所有 HTTP 调用收敛在 `lib_ebook_api` 的 [`CoroutineAdapter.safeApiCall`](lib_ebook_api/src/main/java/com/ebook/api/utils/CoroutineAdapter.kt)：把 `RespDTO<T>` 的业务码映射为 `Result.success(RespDTO)` / `Result.failure(ApiException)`；业务码走 `com.xrn1997.common.constant.ErrorCode`（来自 lib_common）。
- 特殊码 `ErrorCode.USER_ERROR_A0230`（access token 过期）在此层静默触发单飞刷新（[`SessionTokenRefresher`](lib_book_common/src/main/java/com/ebook/common/domain/SessionTokenRefresher.kt)），成功则携带新 token 重放请求一次，失败则经 [`SessionEventBus`](lib_ebook_api/src/main/java/com/ebook/api/auth/SessionEventBus.kt) 发射 `SessionExpired` 事件由顶层 Activity 订阅处置（清会话 + 提示 + 跳登录页）。
- 非取消异常的未知错误经 lib_common 的 [`ExceptionHandler.handleException`](com.xrn1997.common.http.ExceptionHandler.handleException) 统一转译为异常对象后放进 `Result.failure`。
- ViewModel 侧通过继承 lib_common 的 `BaseViewModel`/`BaseRefreshViewModel` 并使用其命令通道（`sendToast`、`onFailure` 回调等）消费 `Result`。**禁止在 UI 层直接捕获原始异常做用户可见判断**——业务分支应由仓库层用业务码完成。

除网络外，本地解析错误（书源规则失配、站点改版）由 [`ErrorAnalyzeContentManager.writeNewErrorUrl`](lib_book_common/src/main/java/com/ebook/common/manager/ErrorAnalyzeContentManager.kt) 写外部存储文件（`ErrorAnalyzeUrls.txt` 及其明细），**不在业务主路径上抛错**——它自身被 try/catch 包在协程内，避免影响章节正文降级获取。

## 2. 关键文件与位置

| 职责 | 文件 | 要点 |
|---|---|---|
| 网络错误收口 | `lib_ebook_api/src/main/java/com/ebook/api/utils/CoroutineAdapter.kt` | `safeApiCall`、`ApiException`、`SessionExpiredException`、`isSessionExpiredHandled` |
| token 刷新实现 | `lib_book_common/src/main/java/com/ebook/common/domain/SessionTokenRefresher.kt` | 并发防抖刷新，成功返回新 token 或 null |
| 会话事件总线 | `lib_ebook_api/src/main/java/com/ebook/api/auth/SessionEventBus.kt` | 上层订阅 `SessionEvent.SessionExpired` 做统一退出 |
| 解析失败落盘 | `lib_book_common/src/main/java/com/ebook/common/manager/ErrorAnalyzeContentManager.kt` | 带文件大小上限轮转的调试记录 |
| ViewModel 基类（Toast 通道） | lib_common 的 `BaseViewModel`/`BaseRefreshViewModel` | VM 调 `sendToast` 向页面推 Toast |
| failover 示例（无业务码时） | `module_me/src/main/java/com/ebook/me/repository/ReleaseRepository.kt` | 按源顺序重试，全部失败返回 `null` 由调用方弹“检查失败” |

## 3. 架构与约定

### 3.1 单一入口：`CoroutineAdapter.safeApiCall`
所有模块仓库的网络请求统一经过此方法。它承担三项责任：
1. 切到 `Dispatchers.IO` 执行；
2. 根据 `RespDTO.code` 走三个分支：成功 → 封装为 `Result.success`；A0230 → `handleTokenExpired`；其他码 → `Result.failure(ApiException(resp.code, resp.error))`；
3. 仅 `CancellationException` 原样上抛（允许取消语义穿透），其余异常都经 `ExceptionHandler.handleException` 并记日志后放入 failure。

### 3.2 会话过期两路处置（事件 vs 标记异常）
- 刷新失败 → `sessionEventBus.emit(SessionEvent.SessionExpired)`，由模块主界面（如 `module_main.MainActivity`）集中订阅；同层同时返回 `SessionExpiredException`。
- 调用方的约定：收到 `isSessionExpiredHandled(exception)` 为 true 时只记日志、不再弹 Toast，避免与全局「会话过期」提示重复。（文档在 CoroutineAdapter KDoc 中写明这一约束。）

### 3.3 业务层：`Result` + ViewModel 命令通道
各 ViewModel 调用仓库后得到 `Result<RespDTO<...>>`，典型模式是 `result.onSuccess { ... }` / `result.onFailure { showException(it) }`，再把错误交给 `BaseViewModel.sendToast` 显示。**仓库层必须先把业务码翻译为 `Result.success/failure`**，禁止把裸异常冒到 VM。

### 3.4 非网络错误：静默落盘策略
解析失败 URL 使用 `ErrorAnalyzeContentManager` 追加写入 `/error_analyze_urls_detail.txt` 和去重后的站点级列表，单次写入超过 512KB 轮转为覆盖模式。**不对外抛出异常**，即使文件 IO 出错也吞掉以免阻塞章节加载。

### 3.5 Failover 示例：多后端回退
`ReleaseRepository.checkLatestRelease()` 依次尝试 GitHub、Gitcode，捕获 `SerializationException` 与普通 `Exception` 后记录 warn 日志并切换到下一个端点；全部失败返回 `null`，由调用方统一提示“检查失败”。取消异常保持向上抛出（页面销毁时不再白打备用源）。

## 4. 约定与约束

- **所有网络请求必须经 `CoroutineAdapter.safeApiCall`**。库内注释将其描述为「通用网络请求适配器的统一收口」，跨模块依赖 `lib_ebook_api` 的代码都应如此接入，否则无法共享 A0230 静默刷新与统一异常转译。
- **禁止在 View/Activity 层自行 switch 业务码**。业务码分支集中在 `CoroutineAdapter`；VM 仅消费 `Result`，UI 层只呈现成功数据或通用错误提示。
- **取消 ≠ 失败**：多处逻辑显式区分 `CancellationException` 并原样上抛（`safeApiCall`、`ReleaseRepository`、token 刷新重试），避免取消被误判为业务失败。
- **A0230 会话过期不走逐点分支**：刷新失败一律发 `SessionExpired` 事件，调用方若捕获到 `SessionExpiredException` 只做日志，交由全局订阅方统一清会话+跳转登录。
- **解析失败的「安静降级」**：`ErrorAnalyzeContentManager` 明确声明「失败不往外抛」，调用方处于异常处理路径时不得让调试落盘再抛错。
- **failover 策略置于应用层仓库（如 `module_me`）**：发布源顺序与有效判定属于项目策略，`lib_ebook_api` 仅暴露 DataSource 接口不变。
- **测试契约要求**：新增 DataSource 接口方法需同步更新对应 `XxxNetworkTest` mock；JSON 资产形态与泛型 T 必须一致，否则会被 `CoroutineAdapter` 吞成「未知错误」（见 AGENTS.md Mock 章节的说明）。