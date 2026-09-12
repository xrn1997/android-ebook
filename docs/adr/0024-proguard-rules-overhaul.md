# 混淆规则体系重构：双态文件拆分 + EncodingInterceptor 去反射

全仓 9 份 `proguard-rules.pro` 从未生效（所有模块 release `isMinifyEnabled = false`），内容存在机制错误（功能模块用 `proguardFiles` 在集成态不生效）、死规则（EventBus/`sqlcipher`/`rx.**` 等无对应依赖的 dontwarn）、重复规则（6 份手写 androidx `@Keep` 块与 AAR 自带规则重复）三类问题。决定遵循 Android 标准约定做双态文件拆分，同时将 `EncodingInterceptor` 从反射 OkHttp 私有字段改为公开 API，消除混淆下必崩的反射依赖。

## 决策

1. **双态规则机制**：`consumer-rules.pro` 经 `consumerProguardFiles()` 声明，集成态随 AAR 传播给消费方的 R8，是规则内容的唯一来源；`proguard-rules.pro` 经 `buildTypes.release.proguardFiles()` 声明，独立态模块自己执行 R8 时使用，功能模块的 `proguard-rules.pro` 仅含 `-include consumer-rules.pro` 避免两份文件重复维护，纯 library 模块不需要此文件。`isMinifyEnabled` 按 `isModule` 条件取值——AGP 9.2.1 下 `isMinifyEnabled = true` 对 library 模块同样执行 R8，集成态功能模块若开 minify 会让 R8 提前剥离类导致 `module_app` 的 R8 找不到依赖（Missing class 错误），独立态开启以尽早暴露规则缺口，集成态关闭以让类完整传播。
2. **模块归属原则**：5 个功能模块放 TheRouter 官方规则块 + `@ServiceProvider` keep；`lib_book_common`/`lib_ebook_api`/`lib_ebook_db` 注释头说明无自有反射面（第三方库自带 consumer 规则已覆盖）；`module_app` 放 TheRouter 块（自身 `@Route MainActivity`）+ 行号属性 keep。判据：只写本模块反射面需要的规则；第三方库（kotlinx-serialization、Retrofit、Room3、Hilt、Coil、Compose、AndroidX）自带 consumer 规则已覆盖，不重复、不凑 `-dontwarn`；禁止无证据的 `-keep`/`-dontwarn`；行号属性（`-keepattributes SourceFile,LineNumberTable`）只放 `module_app`，一处声明全局合并。
   - `lib_book_source` 只有**一条** keep：`-keep class com.ebook.source.sandbox.HostDispatcher { *; }`。理由是它是本仓唯一的「以字符串常量写死的 JNI 反射面」——`js_bridge.cpp` 用 `FindClass` + `GetStaticMethodID("handle", …)` 按名字与签名找入口，这种引用 R8 看不见；不 keep 的后果不是崩溃，而是 `nativeCreate` 里的 `ensure_dispatcher` 当场失败、返回 0，Kotlin 侧于是报 `沙箱 runtime 创建失败（堆上限 … 字节）`——**这句话把原因指向内存配置，而真因是类名被改**，排障时别只查堆上限（`host_call` 里那句「host 回调：主进程入口未就绪」是同一个判据的兜底分支，runtime 都建不出来就走不到它）。且只出现在开了混淆的 release 包上。
   - 同一份文件里**刻意不写** `QuickJsNative` 四个 `external fun` 的规则：AGP 自带那份只覆盖 native 方法本身（`gradle-9.2.1.jar` 内 `com/android/build/gradle/proguard-common.txt`：`-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }`），`external fun` 落在它的匹配范围内；再写一遍只会让后来人以为本仓的 native 面是自己照顾的。这也是 `HostDispatcher.handle` 必须单列的原因——它是普通 Kotlin 方法，只被 C++ 侧按字符串点名，`native <methods>` 那条一个字也覆盖不到。
3. **EncodingInterceptor 去反射**：原实现反射 `okhttp3.internal.http.RealResponseBody.contentTypeString`（OkHttp 私有字段），改为 OkHttp 公开 API：`val mediaType = "application/rss+xml;charset=$encoding".toMediaTypeOrNull()` + `response.newBuilder().body(body.source().asResponseBody(mediaType)).build()`。实现落地为单参 `asResponseBody(mediaType)`，contentLength 固定为 -1（未知），不透传原始 Content-Length——避免下游因已知长度触发全量缓冲，书源响应经本拦截器后一律按流式读取。收益：删除对库私有内部类名的反射依赖（跨 OkHttp 版本脆弱 + 混淆下必炸），不需要任何 keep 规则。该行为已由 `EncodingInterceptorTest` 锁定（含以已知长度响应为入参、断言输出 `contentLength == -1` 的用例）。
4. **行号属性与崩溃栈**：`module_app` 保留 `-keepattributes SourceFile,LineNumberTable` 与 `-renamesourcefileattribute SourceFile`，配合 `mapping.txt` 还原 release 崩溃栈。

## 下游影响

- release 混淆正式生效：`module_app/build.gradle.kts` 的 release 恒为 `isMinifyEnabled = true`（集成态 R8 执行者），5 个功能模块取 `isMinifyEnabled = isModule`。仓内现存 9 份 `consumer-rules.pro`（8 个库/功能模块 + `lib_book_source`）与 6 份 `proguard-rules.pro`（`module_app` + 5 个功能模块），纯 library 模块没有后者。
- 规则归属清晰：每个模块管自己的反射面，新增反射面时先查依赖是否自带规则（grep jar/AAR 内 `META-INF/proguard/` 与 `proguard.txt`），有则不写、无则手写并注释证据。
- 独立态 release 构建让规则缺口在本模块暴露，不等集成组包。
- `EncodingInterceptor` 不再依赖 OkHttp 私有字段，跨版本稳健。
- `mapping.txt` 需人工归档（`build/` 不入库），归档方式由发版流程决定。

## 遗留

- **EventBus 规则按全仓依赖零命中删除**：若实际有传递依赖引入，会在 R8 阶段以 missing class 暴露，属可观测失败——出现时按「新增反射面」流程补规则并注明证据。
- **kotlinx-serialization 自带规则只覆盖 `JsonUtils.parseJson` 的 `Class<T>` 入参用法**：未来出现反射构造类的用法时需重查规则。
- **TheRouter 规则有 6 份逐字相同的副本**：5 个功能模块的 `consumer-rules.pro` md5 完全一致（`0232b380`），
  `module_app/proguard-rules.pro` 另存一份同内容的规则。同一反射面（TheRouter 按类名字符串 `Class.forName`）
  只有一个归属，复制 6 份意味着改一处要改六处、漏改的那份静默按旧规则混淆。
  收口方向：把这份规则归到 `lib_book_common`（或一处 app 级文件）由其余模块 `-include`，模块侧只留自己独有的面。
  本轮未动：收口改的是 release 混淆配置，任何改动都得单跑一次 release 构建并比对 `mapping.txt` 才算验证，
  与本轮的其余改动不同轴，不混在一起提交。
