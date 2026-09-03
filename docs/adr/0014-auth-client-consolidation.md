# 认证网络客户端归口 lib_common 共享客户端

2026-09-01 架构诊断（grill 会话）确认：`lib_ebook_api` 自建认证 OkHttpClient 与 lib_common `NetworkModule` 的共享
`Call.Factory` 并存，自建客户端未使用 common 的脱敏日志（debug 构建下认证请求完全没有 HTTP 日志），超时/拦截器配置与
库的单一事实漂移；且 `EncodingInterceptor` 被误挂到认证链路——它把每个响应 contentType 强改为
`application/rss+xml;charset=UTF-8`（对 JSON API 是语义错误，书源场景才是其使命）。

## 决策

- **认证/业务请求统一注入 common 共享客户端**：`RetrofitBuilder` 改注入 `dagger.Lazy<Call.Factory>` +
  `Retrofit.callFactory`，删除 `lib_ebook_api` 自建认证 OkHttpClient provider；`@Named("source")` 书源纯净客户端保留
  （ADR 要求，不携带 token）。
- **移除 `RetrofitBuilder` 的 `log` 参数与自建 HttpLoggingInterceptor**：common 客户端 debug 构建自带
  `redactHeader("Authorization"/"Cookie")` 脱敏日志，排障能力与 token 安全同时到位。
- **超时接受 common 默认 30s**：ebook-server 为自家后端，30s 弱网更稳；需要严格超时的是第三方书源（10s 保留）——
  认证/书源超时诉求分离。将来如需可配超时，应在 common 提"超时配置化"，不在本仓库二次自建客户端。
- **`EncodingInterceptor` 仅保留在书源客户端**：移除对认证链路的误挂（反射改 `RealResponseBody.contentTypeString`
  依赖 OkHttp 内部实现，脆弱且对 JSON API 无意义）。

## 权衡

- **单栈 vs 双栈**：连接池/线程池合并、日志脱敏单一事实（common 维护一处）；代价是认证客户端配置跟随 common 版本演进，
  不再由本仓库独立控制超时与拦截器。
- **10s vs 30s**：接受 30s 以换取不引入"超时配置化"的库级改动；书源 10s 语义不受影响。

## 下游影响

- `lib_ebook_api/utils/NetworkModule.kt`：删除 `provideOkHttpClient`，保留 `providesNetworkJson` /
  `providesTestAssetManager` / `provideAuthAllowedHosts` / `provideSourceOkHttpClient`。
- `RetrofitBuilder`：注入 `Call.Factory`（`dagger.Lazy` 防循环依赖）、删 `log` 参数。
- `SessionTokenRefresher` / `CommentNetwork` / `UserNetwork`：调用点去掉 `log` 参数。
- mock flavor 不受影响（不经过网络栈）。
