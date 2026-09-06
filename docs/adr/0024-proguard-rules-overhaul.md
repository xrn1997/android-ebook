# ADR-0024: 混淆规则体系重构（proguard-rules overhaul）

日期：2026-09-06
状态：已采纳

## 背景

全仓 9 份 `proguard-rules.pro` 从未生效（所有模块 release `isMinifyEnabled = false`）。
规则内容存在三类问题：

1. **机制错误**：5 个功能模块用 `proguardFiles` 声明规则，集成态（library）下该机制
   不生效——R8 只在 `module_app` 执行，功能模块的规则文件不参与构建。
2. **死规则**：7 份文件含 EventBus keep 规则（全仓无该依赖，事件总线早已是 SharedFlow，
   见 ADR-0004）；`lib_ebook_db` 含 `$Properties`/`sqlcipher`/`rx.**` dontwarn（无对应依赖）。
3. **重复规则**：6 份文件手写 androidx `@Keep` 块，与 androidx-annotations AAR 自带规则重复。

同时，`EncodingInterceptor` 通过反射访问 OkHttp 私有字段 `RealResponseBody.contentTypeString`，
R8 重命名后必崩。

## 决策

### 1. 双态规则机制（一份文件、双声明）

每个模块一份 `proguard-rules.pro`，同时挂两种机制：

```kotlin
defaultConfig {
    consumerProguardFiles("proguard-rules.pro")   // 集成态：随 AAR 传播
}
buildTypes {
    release {
        isMinifyEnabled = isModule                 // 仅独立态开启
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )                                          // 独立态：自己是 R8 执行者
    }
}
```

**关键发现**：AGP 9.2.1 下 `isMinifyEnabled = true` 对 library 模块**同样执行 R8**
（与早期假设"仅 application 执行"不符）。集成态 5 个功能模块若开 minify，R8 会提前
剥离类，导致 `module_app` 的 R8 找不到依赖（Missing class 错误）。因此
`isMinifyEnabled` 必须按 `isModule` 条件取值——独立态开启以尽早暴露规则缺口，
集成态关闭以让类完整传播给 `module_app`。

### 2. 模块归属原则

| 模块 | 规则内容 |
|---|---|
| 5 个功能模块 | TheRouter 官方规则块 + `@ServiceProvider` keep |
| lib_book_common / lib_ebook_api / lib_ebook_db | 注释头说明无自有反射面；第三方库自带 consumer 规则覆盖 |
| module_app | TheRouter 块（自身 `@Route MainActivity`）+ 行号属性 keep |

**判据**：只写本模块反射面需要的规则；第三方库（kotlinx-serialization、Retrofit、Room3、
Hilt、Coil、Compose、AndroidX）自带 consumer 规则已覆盖，不重复、不凑 `-dontwarn`。

### 3. EncodingInterceptor 去反射

原实现反射 `okhttp3.internal.http.RealResponseBody.contentTypeString`（OkHttp 私有字段），
改为公开 API 等价实现：

```kotlin
val mediaType = "application/rss+xml;charset=$encoding".toMediaTypeOrNull()
response.newBuilder()
    .body(body.source().asResponseBody(mediaType, body.contentLength()))
    .build()
```

收益：删除对库私有内部类名的反射依赖（跨 OkHttp 版本脆弱 + 混淆下必炸），不需要任何
keep 规则。

### 4. 行号属性与崩溃栈

`module_app` 保留 `-keepattributes SourceFile,LineNumberTable` 与
`-renamesourcefileattribute SourceFile`，配合 `mapping.txt` 还原 release 崩溃栈。

## 后果

### 正面

- release 混淆正式开启，APK 体积缩小、代码保护生效。
- 规则归属清晰：每个模块管自己的反射面，新增反射面时先查依赖是否自带规则。
- 独立态 release 构建尽早暴露规则缺口，不等到集成组包。
- `EncodingInterceptor` 不再依赖 OkHttp 私有字段，跨版本稳健。

### 负面

- 功能模块独立 release 构建此前从未跑过，首轮可能暴露与混淆无关的问题（如独立清单
  release 语义），需逐项处理。
- `mapping.txt` 需人工归档（`build/` 不入库），归档方式由发版流程决定。

### 风险

- EventBus 规则删除依据是全仓依赖零命中；若有遗漏（如传递依赖引入）会在 R8 阶段暴露
  （missing class），属可观测失败。
- kotlinx-serialization 自带规则覆盖 `JsonUtils.parseJson` 的 `Class<T>` 入参；若未来
  出现反射构造类的用法需重查。
