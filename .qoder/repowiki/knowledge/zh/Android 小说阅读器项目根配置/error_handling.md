## 1. 整体方案

本仓库采用「分层错误模型 + Result 传播 + 统一上报」的组合方式，覆盖网络层、书源解析层与 UI 展示层三个层次：

- **网络层**（`lib_ebook_api`）：所有仓库级 API 调用统一走 `CoroutineAdapter.safeApiCall`，把服务端响应码与 IO 异常收口为 `Result<RespDTO<T>>`；A0230 会话过期由该层静默刷新并重放一次，失败则抛出标记型 `SessionExpiredException` 并经由 `SessionEventBus` 全局处置（清会话 + 提示 + 跳登录），调用方不再自行判断 token 过期。
- **业务/解析层**：使用 Kotlin 标准 `Result` 在协程链上传播失败；对需要区分语义的失败场景，定义细分的异常类型（见第 2 节）。
- **UI 层**：ViewModel 通过 `BaseViewModel.reportFailure` 统一弹出 Toast，且自动识别「会话过期已由全局处置」的失败并静默跳过，避免重复提示。

项目中不使用抛异常作为常规控制流（除少数不可恢复状态外），也不使用自定义 `Error` 基类；异常主要承担「诊断信息 + 分类」的职责，而成功/失败的主路径一律用 `Result` 表达。

## 2. 核心错误类型与文件

### 2.1 网络层错误（`lib_ebook_api/utils/CoroutineAdapter.kt`）

| 类型 | 用途 |
|---|---|
| `ApiException(code, message)` | 非 SUCCESS 的业务码或未知异常包装；`message()` 返回服务端原始消息，供 `reportFailure` 直接上屏 |
| `SessionExpiredException(code)` | A0230 刷新失败时的特殊失败；配合 `isSessionExpiredHandled(exception)` 让上层只记日志不弹 Toast |

`safeApiCall` 是仓库层调用的唯一入口：IO 异常经 `com.xrn1997.common.http.ExceptionHandler.handleException` 翻译后放入 `Result.failure`；取消异常 `CancellationException` 原样上抛，不被吞成业务失败。

### 2.2 脚本书源解析错误（`lib_book_source/script/ScriptRuleExceptions.kt`）

以 `internal sealed class ScriptRuleException` 为根，按失败原因细分为六种类型，每种都有明确边界说明和面向用户的消息模板：

| 异常 | 触发时机 |
|---|---|
| `ScriptRuleParseException` | JSON 读不出或形态不是接受的规则对象（装载期失败） |
| `RuleSyntaxException` | 规则串有确定语法错误（括号不配对等），按规格参与 `||` 短路 |
| `JsEvaluationPendingException` | 规则含 JS 段但当前上下文没有沙箱执行器（构造时未装配 / 嵌套求值被刻意阻止） |
| `UnsupportedRuleFeatureException` | 规则使用了本项目不支持的能力（XPath、webJs、@cache 等） |
| `SandboxProtocolException` | 沙箱回了一帧主进程解不出来（版本不一致 / 接线缺陷） |
| `SandboxUnavailableException` | 沙箱此刻不可用（未绑定、进程被杀、.so 加载失败） |
| `JsExecutionFailedException` | 脚本真跑起来了但超时、内存越界、白名单拒绝等 |

注释中反复强调一条约束：**不得把「规则解不动」折叠成空结果**——空结果语义是「这条源没有这条信息」，与「规则读不懂」在排查上是完全不同的两件事。这些异常由 `JsoupSourceReader` 原样上抛给调用方，下游不做字符串匹配，而是按类型分支处理。

### 2.3 书源缺失错误（`lib_book_source/analyze/BookSourceNotFoundException.kt`）

继承 `IllegalStateException`，用于「按 tag 找不到 parser」的场景（用户删除了源、或数据脏）。KDoc 明确其语义：`null` 表示「查不到」，抛不抛由调用方决定；本地书的 `tag`（`loc_book`）永远查不到行，调用方应先排除本地书再抛此异常。消息带内部 URL，仅用于日志排查，不上 UI。

### 2.4 UI 上报口径（`lib_book_common/util/FailureReport.kt` + `module_find/mvvm/viewmodel/AddBookFailure.kt`）

- `Throwable.userMessage()`：纯函数，把 `ApiException` 的服务端消息透出来，其余异常取自身 message，空 message 归空串。
- `BaseViewModel.reportFailure(exception, message?)`：会话过期（`CoroutineAdapter.isSessionExpiredHandled` 为 true）→ 只记日志；否则 → 经 `sendToast` 弹一条 Toast。返回值 `Boolean` 告诉调用方「是否已静默」（可据此决定是否关闭覆盖层等收尾逻辑）。
- `reportAddBookFailure(e)`：针对「加书架失败」这一常见场景的文案选择器——`BookSourceNotFoundException` 映射到 `R.string.book_source_invalid`，其它异常兜底为网络超时，最终仍走 `reportFailure`。

各模块 ViewModel 中的 `onFailure { reportFailure(it) }` 调用点（login、me、book、find 多个 VM）都收敛到这一个实现，不再各自复制「A0230 会话过期已全局处置」的判断。

## 3. 架构与约定

### 3.1 分层职责划分

```
调用方 (ViewModel / Repository)
    ↓ 返回 Result<T>
CoroutineAdapter.safeApiCall
    ↓ 业务码翻译 / 会话过期静默刷新 / handleException
Result.success(RespDTO) | Result.failure(ApiException | SessionExpiredException | 其他异常)
    ↓ 调用方 onFailure { reportFailure(it) }
reportFailure
    ↓ 识别 SessionExpiredException
Toast / 静默日志
```

### 3.2 类型化异常优先于字符串匹配

`ScriptRuleException` 族的设计动机就是「四种完全不同的失败混起来会让用户被支去重导一条本来好的源」。每个子类都有精确的触发条件与消息模板，且通过 internal sealed 限制可见性，防止上游误用更弱的父类型做分支。测试中用「需脚本沙箱执行器」这类片段断言消息不变，改消息会红单测。

### 3.3 取消异常的显式放行

`safeApiCall` 对 `CancellationException` 两次显式上抛（顶层 catch 与 `handleTokenExpired` 内部），确保取消不会被翻译成「网络请求失败」，也不会误发 `SessionExpired` 事件把用户踢到登录页。

### 3.4 解析器的空结果 ≠ 失败

`RuleResult.Miss` 专门表达「未取到值」，与空列表 `Texts(emptyList())` 不同，保证 `||` 组合符的短路语义正确。解析失败（规则语法错、能力不支持、沙箱问题）走异常；真正没数据走 `Miss`。这是「行为差异」而非「性能差异」，注释里多次强调不能混为一谈。

## 4. 关键文件清单

- `lib_ebook_api/src/main/java/com/ebook/api/utils/CoroutineAdapter.kt` — 网络请求统一适配器、A0230 静默刷新、`ApiException` / `SessionExpiredException`
- `lib_book_source/src/main/java/com/ebook/source/script/ScriptRuleExceptions.kt` — 脚本书源解析失败的类型化异常族
- `lib_book_source/src/main/java/com/ebook/source/analyze/BookSourceNotFoundException.kt` — 书源缺失异常
- `lib_book_common/src/main/java/com/ebook/common/util/FailureReport.kt` — `userMessage` + `reportFailure` 统一上报
- `module_find/src/main/java/com/ebook/find/mvvm/viewmodel/AddBookFailure.kt` — 「加书架失败」的文案选择器
- `lib_book_source/src/main/java/com/ebook/source/script/RuleResult.kt` — 解析求值结果的密封接口（`Miss` / `Nodes` / `Texts` / `Matches` / `Jsons`）
- `lib_book_common/src/main/java/com/ebook/common/importer/LocalImportCoordinator.kt` — 导入流程的状态机式错误处理（`ImportDuplicateState`、`ImportNotice` 等 sealed 类型）
- `module_book/src/main/java/com/ebook/book/mvvm/viewmodel/DownloadManageViewModel.kt` — 下载中心的三态装载结果（`Loading` / `Absent` / `Failed` / `Ready`）
- `lib_book_common/src/main/java/com/ebook/common/repository/BookRepository.kt` — 仓库层使用 `withContext(IO)` 包裹 DB 操作，异常由 Room 层上抛并由上层捕获

## 5. 约束与规范（基于代码事实）

- 所有仓库级网络调用必须经 `CoroutineAdapter.safeApiCall`，禁止在 ViewModel 内直接 try/catch Retrofit 调用。
- 会话过期（A0230）不在调用方分支处理：统一由 `CoroutineAdapter` 静默刷新 → 失败发 `SessionEvent.SessionExpired` 全局处置；调用方收到 `SessionExpiredException` 时只记日志、不再弹 Toast。
- 书源解析失败必须抛类型化 `ScriptRuleException` 子类，不得返回空结果或 null 来模拟「规则读不懂」。
- `BookSourceNotFoundException` 仅在「按 tag 找不到 parser」时使用，本地书（`loc_book`）不应走到此处。
- UI 层报告失败统一走 `BaseViewModel.reportFailure`，新增页面不要手写 Toast 分支。
- 解析结果中的「未取到值」使用 `RuleResult.Miss`，不得用空列表代替，否则会破坏 `||` 短路语义。
- 取消异常 (`CancellationException`) 不得被吞掉，必须在 `safeApiCall` 及内部刷新路径中显式上抛。
- 日志统一走 `com.xrn1997.common.util.Logger`，禁止直接调用 `android.util.Log`。
- 任何对异常消息的修改都要先确认是否被单测断言（如 `SandboxScriptJsTest` 对「需脚本沙箱执行器」片段的断言），否则会导致测试失败。