# 混淆规则体系排查修复重构设计（proguard-rules overhaul）

日期：2026-09-06
状态：已评审通过（brainstorming 会话定稿）
后续：实现计划见 writing-plans 产物；架构决策沉淀为 `docs/adr/0024-*`

## 1. 背景与问题

对全仓 9 份 `proguard-rules.pro` 与各模块构建脚本做了全面排查，结论（均有实证）：

### P0 混淆从未开启

所有模块 release 均 `isMinifyEnabled = false`（`module_app/build.gradle.kts:29` 等）。全部规则文件当前是不生效的死文件。

### P1 规则机制与架构错误

1. **8 个库/功能模块用 `proguardFiles` 声明规则**。该机制只在模块**自己作为 application** 构建时生效；集成态（`isModule=false`）下 R8 只在 `module_app` 执行一次，其余模块的规则文件根本不参与构建。仅 `lib_ebook_db` 用了 `consumerProguardFiles`（唯一正确示范，但内容是垃圾规则）。
2. **7 份文件是同一份模板复制**：EventBus keep 规则（`org.greenrobot.eventbus`）——全仓已无该依赖（grep 零命中；事件总线早已是 SharedFlow，见 ADR-0004），纯死规则。
3. 6 份文件手写 androidx `@Keep` 规则块与 TheRouter `@Autowired` keep——前者与 androidx-annotations AAR 自带的 `META-INF/proguard/androidx-annotations.pro` 重复，后者是 TheRouter 官方 README 要求的规则（正确，但只写在了不生效的位置）。

### P2 垃圾规则

`lib_ebook_db/proguard-rules.pro` 的 `-keep class **$Properties`（仓库无 `$Properties` 结尾类）、`-dontwarn net.sqlcipher.database.**` 与 `-dontwarn rx.**`（无对应依赖）。

### 真实反射面核查（开混淆的生死点）

| 反射点 | 证据 | 结论 |
|---|---|---|
| TheRouter 运行时按类名字符串反射路由类 | 反编译 router-1.4.0-rc1 字节码：`RouteMapKt`/`FragmentFactoryKt`/`DefaultServiceParser` 命中 `forName` | Activity 路由有 manifest aapt 规则兜底（两个 manifest 均声明全部路由 Activity）；**`@ServiceProvider` 实现类不在 manifest，无兜底，必须显式 keep**；`@Autowired` keep 是官方 README 要求 |
| `EncodingInterceptor` 反射 `okhttp3.internal.http.RealResponseBody.contentTypeString`（OkHttp 私有字段） | `NetworkModule.kt:55` 挂在 `@Named("source")` 书源客户端；反编译 okhttp-android 5.3.0 确认该字段存在 | R8 重命名后 `NoSuchFieldException` → IOException → **全部书源请求失败** |
| `JsonUtils.parseJson` 反射 `clazz.kotlin.serializer()` | kotlinx-serialization-core-jvm 1.11.0 jar 内置 `META-INF/proguard/kotlinx-serialization-common.pro`（已读内容，覆盖 Companion/`serializer()` 反射查找） | **自带规则已覆盖，无需手写** |
| lib_common `FileUtil` 反射 `android.os.storage.StorageVolume` | 平台类，R8 永不重命名 | 无需规则 |
| Retrofit / Room3 / Hilt / Coil / Compose / AndroidX | 已逐一验证 jar/AAR 内嵌 consumer 规则（retrofit2.pro、room3-runtime proguard.txt、hilt proguard.txt 等） | 无需手写 |
| TheRouter AAR 自带 proguard.txt 为 0 字节；lib_common AAR（0.3.1）无 proguard.txt | 实测 Gradle 缓存内产物 | 两者所需规则必须由本项目手写 |

## 2. 目标与非目标

**目标**

1. 开启 release 混淆：`module_app` release `isMinifyEnabled = true`；**功能模块在独立态（`isModule=true`）打 release 包时同样开启 R8**——规则缺口在模块级尽早暴露，不等到集成组包。
2. 规则架构按"模块管自己的混淆"重构，双态（集成 lib / 独立 app）自洽。
3. 消灭全部死规则与无效声明；每份规则文件保留的每条规则都有证据支撑。
4. release 崩溃栈可读：保留行号属性，配合 mapping.txt。

**非目标**

- 不开启 `shrinkResources`（资源收缩另行评估）。
- 不改 `lib_common`（android-practice 仓）：无反射面、不发布规则，跨仓不动。
- 不处理功能模块 debug 构建（永不混淆）。
- 不引入新的 `-dontwarn`/`-keep` 兜底"凑规则"。

## 3. 设计

### 3.1 双态规则机制（核心）

本项目功能模块是组件化的：集成态是 library，独立态是 application（`xrn1997.android.component` 按 `isModule` 切换 application/library 插件并附带 `therouter` 插件，见 `AndroidComponentConventionPlugin.kt:12-14`）。两种身份对规则机制的要求不同：

| 身份 | 生效机制 | 失效机制 |
|---|---|---|
| 集成态 library | `consumerProguardFiles`（随 AAR 传播进 module_app 的 R8） | `proguardFiles`（无 R8 任务，标志无操作） |
| 独立态 application | `proguardFiles`（自己是 R8 执行者） | `consumerProguardFiles`（无消费方） |

**结论：遵循 Android 标准约定，按职责拆分为两份文件。** `consumer-rules.pro` 挂 `consumerProguardFiles`（集成态随 AAR 传播，规则内容的唯一来源）；`proguard-rules.pro` 挂 `buildTypes.release.proguardFiles`（独立态自己执行 R8）。功能模块的 `proguard-rules.pro` 仅含 `-include consumer-rules.pro` 避免重复维护；纯 library 模块只需 `consumer-rules.pro`。

```kotlin
defaultConfig {
    consumerProguardFiles("consumer-rules.pro")    // 集成态：随 AAR 传播
}
buildTypes {
    release {
        isMinifyEnabled = true                     // 独立态生效；集成态对 library 无操作
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"                   // 独立态：-include consumer-rules.pro
        )
    }
}
```

### 3.2 minify 开关

- `module_app`：release `isMinifyEnabled = true`（real/mock 两 flavor）。
- 5 个功能模块（book/find/me/login/main）：release `isMinifyEnabled = true`，**不按 isModule 分支**——集成态下该标志对 library 是无操作，构建脚本零分支，KDoc 注明双态语义；实现时以集成构建通过实证。
- 3 个 lib 模块（lib_book_common/lib_ebook_api/lib_ebook_db）：无 application 身份，R8 永不执行，不设 minify；consumer 规则由消费方的 R8 验证。
- debug 构建一律不开。

### 3.3 规则文件内容归属

| 模块 | 文件内容 |
|---|---|
| 5 个功能模块 | TheRouter 官方规则块（androidx `@Keep` 全套 + `@com.therouter.router.Autowired <fields>`）+ `-keep @com.therouter.inject.ServiceProvider class * { *; }`（依据：RouteMapKt 字节码 forName，Provider 实现类不在 manifest） |
| lib_book_common | 注释头：无自有反射面；Jsoup/serialization/juniversalchardet/permissionx 无规则需求 |
| lib_ebook_api | 注释头：DTO 反射序列化查找由 kotlinx-serialization 自带规则覆盖；Retrofit 接口由 retrofit 自带规则覆盖；`EncodingInterceptor` 重构后无反射 |
| lib_ebook_db | 注释头：Room3 自带 consumer 规则覆盖实体/DAO |
| module_app | TheRouter 官方块（自身也有 @Route MainActivity）+ `-keepattributes SourceFile,LineNumberTable` + `-renamesourcefileattribute SourceFile` |

**全面删除**：EventBus 块 ×7、`**$Properties`、`net.sqlcipher`/`rx.**` dontwarn。

**行号属性只放 module_app**：`keepattributes` 全局合并，一处声明即可；独立态当前不开 R8 时无意义，将来若独立态开 R8 再自行补充。

### 3.4 EncodingInterceptor 重构去反射

唯一业务代码改动，`lib_ebook_api` 一处：

```kotlin
// 原：getDeclaredField("contentTypeString") 反射写 OkHttp 私有字段
// 新：OkHttp 公开 API 等价实现
val mediaType = "application/rss+xml;charset=$encoding".toMediaTypeOrNull()
response.newBuilder()
    .body(body.source().asResponseBody(mediaType, body.contentLength()))
    .build()
```

- 行为等价：contentType 强制为 `application/rss+xml;charset=UTF-8`，流式不缓冲。
- 收益：删除对库私有内部类名的反射依赖（跨 OkHttp 版本脆弱 + 混淆下必炸），不需要任何 keep 规则。
- KDoc 注明动机（R8 混淆 + OkHttp 版本演进双重脆弱性）。

### 3.5 构建脚本改动清单

- 5 个功能模块：`defaultConfig` 加 `consumerProguardFiles("consumer-rules.pro")`；buildTypes 的 **debug 块删除**（永不混淆，纯噪音）；release 块保留并置 `isMinifyEnabled = isModule`，`proguardFiles` 指向 `proguard-rules.pro`（内容仅 `-include consumer-rules.pro`）。
- lib_ebook_db / lib_ebook_api：删除 buildTypes 块（library 无独立身份）；`defaultConfig` 挂 `consumerProguardFiles("consumer-rules.pro")`；不保留 `proguard-rules.pro`（纯 library 无独立 R8）。
- lib_book_common：`defaultConfig` 挂 `consumerProguardFiles("consumer-rules.pro")`；不保留 `proguard-rules.pro`。
- module_app：release 置 `isMinifyEnabled = true`，`proguardFiles` 指向 `proguard-rules.pro`（纯 application，无 consumer 文件）。

## 4. 文档同步

- 新增 `docs/adr/0024-*`（编号顺延）：记录混淆体系决策——双态规则机制（consumer-rules.pro + proguard-rules.pro 两份文件）、模块归属原则、"自带规则优先、不凑规则"判据、EncodingInterceptor 去反射理由。
- AGENTS.md「构建约定」补一小节：混淆规则归属约定（文件拆分遵循 Android 标准约定、TheRouter 规则放功能模块 consumer-rules.pro、行号属性放 module_app、新增反射面时先查依赖是否自带规则）。

## 5. 验证方案（按仓库分工：Agent 止于编译与静态检查）

**Agent 验证环**

1. 早发现环（`isModule=true`，独立态）：逐模块 `:module_book:assembleRelease` / `:module_find:...` / `:module_me:...` / `:module_login:...` / `:module_main:...`——R8 执行 5 次，每份规则文件独立过堂。
2. 集成环（临时切 `isModule=false`）：`:module_app:assembleRealRelease` + `assembleMockRelease`；同时实证"library 上 minify 标志无操作"（构建通过即证）。
3. 审查 R8 missing-class / 警告清单，不为消警告乱加 `-dontwarn`。
4. 核对 `mapping.txt` 产物；抽查 ServiceProvider 实现类（LoginProvider/MeProvider/BookProvider/FindProvider）与路由 Activity 的类名保留情况。
5. 工作树安全：`gradle.properties` 当前是本地未提交的 `isModule=true`——集成环验证临时切换，**验完恢复原值，绝不提交该文件**；用户其余未提交改动不碰。

**人工装机验证清单（Agent 未验证，提交前必须人工过）**

- 三主 Tab 渲染（ServiceProvider 创建页面）
- 登录/注册/忘记密码链路
- 书籍详情页 @Autowired 参数注入（from/data/data_key）
- 阅读器翻页与目录
- 离线下载前台服务（ADR-0018 链路）
- 书城/搜索/分类（书源客户端走重构后的 EncodingInterceptor，注意中文站点编码）
- 版本更新检查（@Named("release") 客户端，ADR-0021）
- mock flavor 启动（applicationId `com.ebook.mock`）

## 6. 风险与边界

- **EventBus 规则删除**：依据是全仓依赖零命中 + AGENTS.md 明示异步统一 Coroutines/Flow；若有遗漏（如传递依赖引入）会在 R8 阶段暴露（missing class），属可观测失败。
- **serialization 自带规则覆盖 `JsonUtils`**：依据为 1.11.0 jar 内置规则文件内容（`-if @kotlinx.serialization.Serializable` 系列）；若 `parseJson` 的 `Class<T>` 入参来自反射构造的类（当前无此用法）需重查。
- **独立态 release 的可用性**：功能模块独立 release 构建此前从未跑过（一直 false），首轮构建可能暴露与混淆无关的问题（如独立清单 release 语义），按构建报错逐项处理，不扩大范围。
- **mapping.txt 归档**：AGP 输出至 `module_app/build/outputs/mapping/<variant>/`；release 人工装机通过后应随发版归档（build/ 不入库，归档方式由人工流程决定，本设计不引入脚本）。
