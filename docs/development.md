# 开发与构建

> 本文件是构建、运行、测试与工具链的操作事实源。模块结构与运行时机制见 [`docs/architecture.md`](architecture.md)；提交与发版流程见 [`CONTRIBUTING.md`](../CONTRIBUTING.md)。

## 1. 常用命令

> **Windows 用户**：PowerShell 下使用 `.\gradlew` 替代 `./gradlew`。

```bash
# 构建整个项目
./gradlew build

# 构建指定模块（debug）
./gradlew :module_app:assembleDebug

# 清理构建
./gradlew clean build

# 生成 release APK
./gradlew :module_app:assembleRelease

# 单元测试
./gradlew test
./gradlew :module_book:testDebugUnitTest

# 集成测试（需要连接设备/模拟器）
./gradlew connectedAndroidTest

# Lint 检查
./gradlew lint

# 文档检查（AGENTS 预算、相对链接、过程工件状态头）
bash scripts/check-docs.sh
```

## 2. 工具链与依赖

- **依赖版本仅通过版本目录管理**（`gradle/libs.versions.toml`），构建脚本中引用 `libs.xxx.yyy`，不硬编码版本号；各依赖的具体版本号以版本目录为准。**已知结构性豁免**：`settings.gradle.kts` 的 `plugins {}` 块读不到版本目录，其中 foojay resolver 插件的版本为字面值，不是漏改。
- **工具链**：字节码目标 JDK 17；构建守护进程 JVM 由 `gradle/gradle-daemon-jvm.properties` 自动拉取，无需本地预装。
- **AGP 9 内置 Kotlin（built-in Kotlin）**：Android 模块不再应用 `org.jetbrains.kotlin.android`（约定插件 `xrn1997.android.*` 不包含 KGP，Kotlin 支持由 AGP 提供）；顶层 `kotlin { compilerOptions {} }` 块仍可用（AGP 注册了 `KotlinAndroidProjectExtension`）。`gradle.properties` 不设置 `builtInKotlin`/`newDsl` 开关（默认内置 + 新 DSL）。lib-common-build 的 lib_common 同规则（约定插件由 android-ebook 的 build-logic 提供）。
- **AGP 9 源码目录注意**：给 Kotlin 编译添加源码目录走 `sourceSets` 上的 `kotlin.directories.add(...)`（仓内实例：`AndroidComponentConventionPlugin` 在独立态加 `src/main/test`）；`java.srcDirs(...)` 不再被 Kotlin 编译拾取（但 KSP 仍可见，会造成「KSP 生成了代码、Kotlin 没编译」的错位）。
- **图片加载**：Coil（Compose）。Glide 已随 View 体系一并移除，不要再引入。
- **日志**：统一走 `com.xrn1997.common.util.Logger`（级别控制、debug/release 自动裁剪），禁止直接调用 `android.util.Log`。
- **注释**：每个类、每个方法、每个重要逻辑分支都必须有足够的 KDoc/注释，说明「是什么、为什么」——包括设计决策、竞态条件、跨线程可见性等审查时需要知道的背景。
- **编译警告**：不要引入新的编译警告，提交代码应保持警告清洁。

### lib_common 的本地联调

`lib_common`（android-practice）默认从 Maven 中央坐标依赖。需要在本地改 common 时，启用根 `settings.gradle.kts` 里的 `includeBuild("lib-common-build")` 迷你独立构建：`lib-common-build/settings.gradle.kts` 以相对路径定位 android-practice 源码，开发者需把 android-practice 克隆到匹配位置（事实源以该 settings 脚本为准）。切换是双向的：本地改完 common 即切回 Maven 坐标，**联动态不提交**。

## 3. 模块独立运行与 mock

项目通过 product flavor 与 source set 两层机制实现 mock 数据源切换，无需后端服务器即可开发调试（机制细节以 `module_app/build.gradle.kts` 与各模块 source set 为事实源）。

**两种运行形态**：

- 集成构建（`isModule=false`）：`./gradlew :module_app:assembleRealDebug` 连接真实后端；`assembleMockDebug` 使用内存 mock 数据源（applicationId 为 `com.ebook.mock`）。mock/real 的 `NetworkModule` 分别位于 `module_app/src/mock/` 与 `src/real/`。
- 独立模块（`isModule=true`）：约定插件（`AndroidComponentConventionPlugin`）只在该分支把 `src/main/test` 与共享目录 `debug-host/src` 作为**额外 Kotlin 源码目录并入 `main` source set**。共享目录提供 `BaseTestApplication`（路径替换/拦截扩展点）、`TestLoginInterceptor`、`MockNetworkModule`（User/Comment/Release 全绑 mock）与 `DebugHostActivity`（主题、edge-to-edge、状态栏、双击退出）；各模块 `src/main/test/debug` 只留自己的 `TestApplication` 路由配置、首页入口与模块内桩页。集成态根本不编译这两个目录，独立运行默认 mock，无需配置。

**四条铁律**：

- **两份 Manifest 是替换关系**：独立态只生效 `src/main/module/AndroidManifest.xml`、集成态只生效 `src/main/AndroidManifest.xml`——Activity 声明及其属性（`launchMode`/`theme`/`label`）两处必须同步修改，否则两种模式行为不一致。
- **跨模块路由在独立模式下静默丢失**：业务代码跳往其他模块的路由在独立模块里不存在，TheRouter 找不到路由**只记一行日志、不报错不闪退**。需要该链路的模块在 `src/main/test/debug/` 宿主上以 `@Route` 挂同名路径占位（该 source set 只在独立模式编译，不与集成模式抢路由）。新增/改动 `@Route` 后，routeMap 资产由 TheRouter transform 回写，**当次构建的 APK 仍装旧路由表，需再构建一次**才生效。
- **新增接口同步 mock**：新增 `DataSource` 接口方法时，必须同步更新对应的 `XxxNetworkTest` mock 实现。JSON 资产只适用于**返回固定结构的读接口**（这类方法同时要在 `lib_ebook_api/src/main/assets/` 补对应资产）；**回显入参的写接口与文件上传接口**应在 mock 里以代码合成响应，并注释说明「为何无静态资产可对应」——静态资产表达不了「按入参变化」与「上传后真实地址」，禁止为凑规则造一份固定 JSON 冒充响应。例外：`ReleaseDataSource` 的 mock 忽略 endpoint 入参、固定回一份资产，双源 failover 因此不在 mock 里验，由 `module_me` 的 `ReleaseRepositoryTest` 用假数据源锁住。
- **资产形态与解码类型同步**：mock 读资产用 `getDataFromJsonFile<T>` 的 reified 类型，必须与资产的 `data` 实际形态一致（服务端改分页包裹时 `T` 要跟着换）。错配抛的 `SerializationException` 会被 `CoroutineAdapter` 吞成「未知错误」，**页面不闪退、数据永远加载不出来**，只有一行看不出根因的 ERROR 日志——因此这类改动必须同步更新 mock 资产契约测试。第三方平台的原始 JSON 资产不带 `RespDTO` 信封，不走 `getDataFromJsonFile<T>`，把整个资产直接解成实体。

**mock 的非成功码开关（账号面）**：mock 资产清一色是成功信封，「业务码非成功」那一档在不连后端时本来走不到——失败文案上屏与「刷新被拒 → 会话过期 → 跳登录」整条链都得连真后端才验得动。`UserNetworkTest` 因此带一个开关：把 `fail` 填进任一文本入参（邮箱 / 密码 / 验证码 / 昵称 / 头像文件名 / 待刷新的 refresh token）即回放 `user_rejected.json`（`A0001` + 一句服务端文案）。它只存在于 mock flavor 与独立调试宿主，真实后端不看这些值；读数由 `UserNetworkTestTest` 钉住（为什么要有这条开关见 [ADR-0059](adr/0059-account-faces-envelope-in-seam.md)）。

## 4. 原生构建（脚本书源沙箱）

`lib_book_source` 经 build-logic 的 `xrn1997.android.native` 约定插件编 C++，QuickJS 内核 vendored 在 `third_party/quickjs/`、桥接层在 `lib_book_source/src/main/cpp/`。

ABI 口径：插件给 release 的最小集只有 `arm64-v8a`（多余 ABI 等于多带一份可被研究的内核副本），模拟器所需的 `x86_64` 由模块自己在 debug 里显式放宽——放宽点写在模块而非插件，是为了让「谁多带了一份内核」当场可见。

## 5. 混淆

release 混淆已开启：`module_app` release 恒开；功能模块 `isMinifyEnabled = isModule`——独立态开启以尽早暴露规则缺口，集成态关闭以避免 AGP 对 library 执行 R8 剥离类。

- **文件拆分遵循 Android 标准约定**：`consumer-rules.pro` 挂 `consumerProguardFiles`（集成态随 AAR 传播，规则内容的唯一来源）；`proguard-rules.pro` 挂 `buildTypes.release.proguardFiles`（独立态自己执行 R8），功能模块的 `proguard-rules.pro` 仅含 `-include consumer-rules.pro` 避免重复；纯 library 模块只需 `consumer-rules.pro`。
- **规则归属**：只写本模块反射面需要的规则，第三方库（kotlinx-serialization、Retrofit、Room3、Hilt、Coil、Compose、AndroidX）自带 consumer 规则已覆盖，不重复、不凑 `-dontwarn`；新增反射面时先查依赖是否自带规则（grep jar/AAR 内 `META-INF/proguard/` 与 `proguard.txt`），有则不写、无则手写并注释证据。
- **禁止无证据的 `-keep`/`-dontwarn`**；行号属性（`-keepattributes SourceFile,LineNumberTable`）只放 `module_app`，一处声明全局合并。

决策背景见 ADR-0024。

## 6. 测试

- 单元测试使用 JUnit 4，位于各模块 `src/test/java`；插桩测试位于 `src/androidTest/java`。
- 测试类命名 `<Subject>Test`；测试方法使用反引号包裹的句子式描述。
- 新写的 ViewModel 与纯控制器测试只断言语义结论与状态，不取字符串资源；页面映射由页面里不带 `else` 的 `when` 在编译期兜住。Robolectric 保留给 Compose 渲染冒烟（组合 + 资源解析）、真 Room 端到端与仍需真实 Android Context 的存量接线测试；约定前向生效、不追溯。
- 行为变更时同步更新已有测试，保持测试与实现一致。
- 测试覆盖待办见 [`docs/test-coverage-todo.md`](test-coverage-todo.md)。
- `ReadBookControl` 是 `object`，类初始化即读 `BaseApplication.context` 并调 `DisplayUtil.dp2px`；JVM/Robolectric 下拿不到应用图，单测会卡在单例初始化。它的纯逻辑（如 `fitLines`、落点换算）应抽成可测函数；不要为这个 object 强行造测试。

## 7. 文档与提交

- **文档同步**：代码、注释与文档必须保持一致——任何改动在提交前同步更新相关注释与文档，禁止留下「代码已改、注释/文档仍是旧描述」的状态。文档该放哪、什么时候归档见 [`docs/AGENTS.md`](AGENTS.md)。
- **评审/grill 驱动改动的沉淀**：由代码评审、grill 会话等驱动的架构级决定（依赖替换、体系迁移、组件归属等）必须沉淀为 `docs/adr/` 的 ADR，不能只留在提交信息与会话记录里。ADR 写作见 [`docs/adr/ADR-FORMAT.md`](adr/ADR-FORMAT.md)。
- **提交前验证**：提交信息规范、`commit-msg` 钩子安装、发版判据与提交前检查见 [`CONTRIBUTING.md`](../CONTRIBUTING.md)。
