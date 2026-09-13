## 1. 使用的系统与框架

仓库统一通过 `com.xrn1997.common.util.Logger`（来自外部共享基类库 `lib_common`，即 android-practice）进行日志输出。该 Logger 提供按级别调用的 API（`Logger.d/i/w/e(TAG, msg, throwable?)`），由 lib_common 负责级别控制与 debug/release 自动裁剪。

仓库中**禁止直接调用 `android.util.Log`**——这是构建约定中的硬性规则（见仓库指南「日志统一走 `com.xrn1997.common.util.Logger`……禁止直接调用 `android.util.Log`」）。所有业务模块、共享库都遵循这一约束，证据覆盖 `module_app`、`module_main`、`module_book`、`module_find`、`module_login`、`module_me`、`lib_book_common`、`lib_book_source`、`lib_ebook_api` 等全部模块的源码。

## 2. 关键文件

- **日志入口使用方（跨模块示例）**：
  - `lib_book_common/src/main/java/com/ebook/common/BookApplication.kt`：应用启动进程门处记录沙箱隔离信息（`Logger.i(TAG, "沙箱进程：跳过应用级初始化")`）。
  - `lib_ebook_api/src/main/java/com/ebook/api/utils/CoroutineAdapter.kt`：网络层统一异常处理，在通用 catch 中记录 `Logger.e(TAG, "网络请求异常", exception)`；会话过期静默刷新失败时记录 `Logger.w(TAG, "会话过期且静默刷新失败，已转交全局处置：code=..." )`。
  - `lib_book_common/src/main/java/com/ebook/common/util/FailureReport.kt`：`reportFailure` 对会话过期路径统一记录 `Logger.w(..., "会话过期已由全局处置，本调用点静默（仅日志）：...")`，是用户提示与日志的唯一归口。
  - `module_book/src/main/java/com/ebook/book/mvvm/viewmodel/BookDetailViewModel.kt`：详情页各异步操作失败时统一 `Logger.e(TAG, ...)` 记录原因，成功或静默场景用 `Logger.d` 或仅日志。
  - `lib_book_source/src/main/java/com/ebook/source/sandbox/SandboxService.kt`、`JsSandboxConnector.kt`、`JsCallbackProxy.kt`、`BinderJsChannel.kt`：沙箱进程间通信全程用 Logger 记录 binder 连接、回调转发等调试信息。
  - `lib_book_common/src/main/java/com/ebook/common/analyze/source/JsoupSourceReader.kt`、`BookSourceManagerImpl.kt`、`LibraryDiskCache.kt`：书源解析与缓存命中/失效日志。
  - `module_login/src/main/java/com/ebook/login/mvvm/viewmodel/LoginViewModel.kt`、`RegisterViewModel.kt`、`ModifyPwdViewModel.kt`：登录/注册/改密流程的失败日志。
  - `module_me/src/main/java/com/ebook/me/repository/ReleaseRepository.kt`、`ReleaseStateStore.kt`：版本更新检查日志。
  - `module_book/src/main/java/com/ebook/book/service/DownloadService.kt`：前台下载服务状态变更日志。

- **构建侧约束**：`build-logic/convention/AndroidLibraryConventionPlugin.kt` 在单元测试配置中对 `android.util.Log` 返回默认值，防止测试误用原生 Log 绕过级别过滤。

## 3. 架构与约定

- **单一日志门面**：全仓不引入 SLF4J、Logback、Timber 等第三方日志框架，也不存在本地封装的 Logger 实现。所有模块直接依赖 lib_common 提供的 `com.xrn1997.common.util.Logger`。
- **日志级别策略**：依据代码中的实际用法可归纳为：
  - `Logger.d`：开发期诊断性信息（如 `addToBookShelf` 成功、章节追加条数等）。
  - `Logger.i`：重要业务流程入口/出口（如沙箱进程门、导入跳过等）。
  - `Logger.w`：可恢复或需关注但不阻断的流程（如会话过期已由全局处置、静默刷新失败）。
  - `Logger.e`：异常、错误分支、不可恢复失败（网络请求异常、书源失效、目录重抓失败等），通常附带 Throwable。
- **结构化字段**：未定义统一的 JSON/结构化日志格式。每个调用点自行维护一个 `TAG`（通常为类名常量 `TAG` 或 `this::class.java.simpleName`），消息体以字符串拼接为主，异常作为可选参数传入。
- **下沉到 UI 的失败报告**：`FailureReport.reportFailure` 把「会话过期 → 只记日志不弹 Toast」这条业务规则固化在 `BaseViewModel` 扩展函数中，ViewModel 侧不再手写 `isSessionExpiredHandled` 判断，避免重复逻辑散落各处。
- **网络层集中落点**：`CoroutineAdapter.safeApiCall` 是网络异常的集中记录点，其他上层只需消费 `Result` 并通过 `reportFailure` 上报，形成「网络层记错 + 业务层记提示」的分层模式。

## 4. 约定与约束

- **强制约束**（来源：仓库指南中的明确规则）：
  - 日志统一走 `com.xrn1997.common.util.Logger`，**禁止直接调用 `android.util.Log`**。
  - 构建时对单元测试的 `android.util.Log` 注入默认返回值，从构建侧拦截直连原生 Log 的行为。
  - A0230 会话过期路径必须经 `CoroutineAdapter` 静默刷新并统一发事件，调用方一律通过 `reportFailure` 上报，不得再手写「是否已处理过会话过期」的分支。
- **观察到的惯例**（非强制，但全仓一致）：
  - 每个类持有 `private companion object { const val TAG = "ClassName" }`，日志调用以该类名作为 TAG。
  - 异常分支优先用 `Logger.e(TAG, "描述", exception)` 附带堆栈，成功或中性分支用 `Logger.d`。
  - 用户可见的错误文案统一通过 `sendToast`（MvvmBinder 命令通道）而非 `Toast.makeText`，错误日志与用户提示分离。
  - 会话过期相关日志统一标记为 `w` 级别，并附带 `code` 字段便于排查。
- **约束范围**：上述规则适用于当前仓库的全部 Kotlin 源码（包括 `module_*`、`lib_*`、`build-logic` 中涉及日志的部分）。由于 `lib_common` 是外部仓库，其内部 Logger 的具体实现细节不在本仓范围内；本仓仅约束调用方式与级别选择。