## 体系概述

本仓库采用 **lib_common（android-practice）提供的统一日志抽象**，业务代码不直接调用 `android.util.Log`；在网络层额外通过 OkHttp 的 `LoggingInterceptor` 输出请求/响应明细，用于调试第三方书源或后端接口。

- 应用层日志：统一走 `com.xrn1997.common.util.Logger`，由 AGP 构建产物在 debug/release 自动裁剪（见仓库指南「日志统一走 com.xrn1997.common.util.Logger」）。
- 网络层日志：通过依赖 `okhttp-logging` 并挂载到 OkHttpClient，仅记录 HTTP 层面的原始报文。
- 无自定义封装：仓库中没有 `LogManager`、`ILogger`、`AppLogger` 等包装层，也没有对 `Logger` 级别做全局开关配置；各模块按约定直接使用。这使日志系统的“核心逻辑”实际上外置到了外部依赖 `android-practice/lib_common`。

## 关键文件与位置

| 文件 | 职责 | 证据 |
|---|---|---|
| `lib_ebook_api/src/main/java/com/ebook/api/utils/CoroutineAdapter.kt` | 网络请求收口处统一 `Logger.e` / `Logger.w` 输出异常与会话状态 | import `com.xrn1997.common.util.Logger`；在 `safeApiCall` catch 中 `Logger.e(TAG, "网络请求异常", exception)`，在刷新失败分支 `Logger.w(TAG, "会话过期…")` |
| `gradle/libs.versions.toml` | 通过版本目录声明 `okhttp-logging = { group="com.squareup.okhttp3", name="logging-interceptor" }` | 版本集中管理 |
| `lib_ebook_api/build.gradle.kts` | 声明 `api(libs.okhttp.logging)`，向下游暴露拦截器依赖 | `api(libs.okhttp.logging)` |
| 各业务模块中的 `TAG` 常量（如 `CoroutineAdapter` 的 `private const val TAG = "CoroutineAdapter"`） | 作为 Logger 标签标识来源类 | `companion object` 内以 `TAG` 命名常量传递到 `Logger.e/w` |

## 架构与约定

### 层级划分

```
业务模块 (module_book/module_find/module_me/...) 
    → lib_book_common (Hilt、Repository、UI)
        → lib_ebook_api (Retrofit + OkHttp)  ← 仅在此层输出网络细节
            → okhttp-logging (拦截器打印 req/res)
        → lib_ebook_db (Room)
```
日志调用主要集中在 `lib_ebook_api` 的网络适配层 (`CoroutineAdapter`)，上层 Repository/ViewModel 只通过 Result/Flow 传递成功/失败，不在业务逻辑路径上散打日志。

### 日志级别与语义

根据当前代码中唯一成体系的用法（`CoroutineAdapter`），可归纳出两个经验级别：

- `Logger.e(TAG, message, throwable)` — 用于异常/错误场景：网络请求抛出通用异常时，附带完整异常栈。
- `Logger.w(TAG, message)` — 用于可预期但需警示的业务分支：例如「会话过期且静默刷新失败」后发射 `SessionEvent.SessionExpired` 前，用 WARN 标记事件已转交全局处置。

注意：仓库未发现 `Logger.d` / `Logger.i` 的显式使用，亦无统一的 `DEBUG=true` 开关——debug/release 裁剪由 `lib_common` 侧完成。

### 结构化字段

当前日志**未采用结构化 JSON 字段**。每条日志是一条纯文本消息，上下文通过 `TAG`（固定类级标签常量，如 `"CoroutineAdapter"`）和消息中的嵌入键值（如 `"code=${expiredResp.code}"`）来区分来源与关键参数。没有 `correlationId`、`userId`、`bookId` 等跨请求追踪字段，也不存在 MDC / Trace ID 机制。

### 断言：禁止绕过 Logger 直接打 Android Log

仓库指南文档明确写明：*「日志统一走 com.xrn1997.common.util.Logger（lib_common 提供，级别控制、debug/release 自动裁剪），禁止直接调用 android.util.Log」*。这是约束性规范而非建议；目前 grep 全仓未发现引用 `android.util.Log` 的 Kotlin 源码（build-logic 之外），说明该规则已被遵守。

## 限制与待完善点

1. **无应用启动时的 Logger 初始化**：未在 `BookApplication`、`@Module` 中见到为 `Logger` 设置 tag 过滤、输出目标（文件/远程）的代码。所有配置预期位于 `lib_common` 内部或其注入方式中，当前仓库不掌握实现细节。
2. **日志粒度偏粗**：仅在顶层 `CoroutineAdapter.safeApiCall` 捕获并打 log，中间层的 Retrofit Adapter、OkHttp Interceptor 的具体动作仅由 `logging-interceptor` 输出裸 HTTP 报文，缺少业务语义（如方法名、入口路由）。
3. **无日志采样/限速**：对频繁调用的重试/刷新路径未见节流逻辑，可能产生大量重复日志。
4. **缺乏结构化能力**：日志为纯文本，不便于聚合分析（ELK/阿里云日志服务等无法自动解析 field）。
5. **对外暴露了 `okhttp-logging`**：`lib_ebook_api` 通过 `api(...)` 暴露依赖，下游模块理论上也可自行挂载拦截器；若存在多个客户端应确认是否都挂上了，避免遗漏。