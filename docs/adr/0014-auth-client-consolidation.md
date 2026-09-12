# 认证网络客户端归口共享客户端，移除自建 OkHttpClient

`lib_ebook_api` 曾自建认证 OkHttpClient，与 `lib_common` 的共享 `Call.Factory` 双栈并存。决定认证/业务请求统一注入共享客户端、删除自建认证客户端，第三方链路各留一条不带 token 的纯净客户端。这样日志脱敏、超时与拦截器配置回到单一事实源，不同信任域的凭证边界也因此显式化（来源：架构诊断 grill 会话）。

## 动机

- 自建认证客户端不走 `lib_common` 的脱敏日志，debug 构建下认证请求完全没有 HTTP 日志；超时与拦截器配置和库的默认值各写一份、随版本各自漂移。
- `EncodingInterceptor` 曾被误挂到认证链路：它把每个响应的 contentType 强改为 `application/rss+xml;charset=UTF-8`，对 JSON API 是语义错误，只在抓取书源（中文 HTML、服务端不声明编码）时才成立。

## 决策

1. **认证/业务请求统一注入共享客户端**：`RetrofitBuilder` 改注入 `dagger.Lazy<Call.Factory>` + `Retrofit.callFactory`，删除 `lib_ebook_api` 自建认证 OkHttpClient provider——消除双栈并存导致的日志缺失与配置漂移。`dagger.Lazy` 是防 Hilt 循环依赖，不是可选修饰。
2. **不携带登录凭证的链路各用各的纯净客户端，且必须分开**：`@Named("source")` 服务第三方书源抓取（10s 超时 + `EncodingInterceptor`），`@Named("release")` 服务发布检查的公开 Releases API（仅 10s 超时）。不复用同一个命名客户端：两条链路的超时与拦截器演化方向不同，混用会让命名失真。它们与认证链路之间唯一的不变量是**不带 token**——第三方站点永远拿不到用户凭证。
3. **移除 `RetrofitBuilder` 的 `log` 参数与自建 HttpLoggingInterceptor**：共享客户端在 debug 构建自带 `redactHeader("Authorization"/"Cookie")` 脱敏日志——排障能力与 token 安全同时到位，无需自建。
4. **超时接受共享客户端的默认 30s**：认证与业务请求打的是自家后端，30s 在弱网下更稳；需要快速失败的是第三方链路，10s 保留在纯净客户端上——认证与第三方的超时诉求本就不同。将来若要可配超时，应在 `lib_common` 提「超时配置化」，不在本仓库二次自建客户端。
5. **`EncodingInterceptor` 仅挂在书源客户端**：它把每个响应的 contentType 强改为 `application/rss+xml;charset=<encoding>`，只在「抓中文 HTML 且服务端不声明编码」的链路上成立，对 JSON API 是语义错误，故作用面压到最小。（早期实现还靠反射改 OkHttp 私有字段 `RealResponseBody.contentTypeString`，OkHttp 升级或 R8 改名都会让全部书源请求失败；现改为公开 API 等价实现——`ResponseBody.source()` 包装 + `newBuilder`，不需要 keep 规则。脆弱性已随反射去掉，压作用面的理由是上面那条语义代价。）

## 权衡

- **单栈 vs 双栈**：连接池/线程池合并、日志脱敏单一事实（`lib_common` 维护一处）；代价是认证客户端配置跟随 `lib_common` 版本演进，不再由本仓库独立控制超时与拦截器。
- **10s vs 30s**：接受 30s 以换取不引入「超时配置化」的库级改动；第三方链路的 10s 语义不受影响。
- **纯净客户端按信任域分条 vs 合用一条**：分条要多维护一个 provider，换来的是「给谁加了什么拦截器」这件事不会顺着共用对象扩散。

## 下游影响

- `lib_ebook_api/utils/NetworkModule.kt`：provider 集合为 `providesNetworkJson` / `providesTestAssetManager` / `provideAuthAllowedHosts`（认证白名单主机绑定）/ `provideSourceOkHttpClient`（`@Named("source")`）/ `provideReleaseOkHttpClient`（`@Named("release")`），自建认证 `provideOkHttpClient` 已移除。
- `RetrofitBuilder`：注入 `Call.Factory`（`dagger.Lazy`）与共享 `Json`，无 `log` 参数；构造 Retrofit 的只有走认证链路的 `UserNetwork` 与 `CommentNetwork`。
- 纯净客户端的消费方分布在 `lib_book_common`（书源抓取、JS 沙箱的守门客户端）与 `lib_ebook_api`（`ReleaseNetwork`），均按 `@Named` 取值而非自建。
- mock flavor 不受影响（不经过网络栈）。
