# 仓库指南（Repository Guidelines）

本文件是本仓库统一的 Agent 协作与贡献指南，供 Codex、Claude Code 等 Agent 与人工贡献者共用。

## 项目概述

安卓小说阅读器，100% Kotlin 开发，MVVM 架构，多模块 Gradle 项目。界面全部为 Jetpack Compose（含阅读器，见 ADR-0001），ViewBinding 与 XML 布局已移除且不再引入；异步统一使用 Kotlin Coroutines + Flow，RxJava3 已完全移除（无依赖、无引用），禁止重新引入。

lib\_common（android-practice）是仓库外的共享基类库。它的依赖方式以根 `settings.gradle.kts` 为事实源：默认走 Maven 中央坐标，本地源码联调时启用 `includeBuild("lib-common-build")` 迷你独立构建（`lib-common-build/settings.gradle.kts` 以相对路径定位 android-practice 源码，开发者需把 android-practice 克隆到匹配位置，事实源以该 settings 脚本为准）。切换是双向的：本地改完 common 即切回 Maven 坐标，联动态不提交。

## 常用命令

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
```

## 模块架构

依赖方向：**业务模块 → lib\_book\_common → lib\_book\_source → lib\_ebook\_api / lib\_ebook\_db**（lib\_book\_common 与 lib\_book\_source 都对 lib\_ebook\_api、lib\_ebook\_db 并列 `api` 依赖，`lib_ebook_api → lib_ebook_db` 这条边并不存在；lib\_book\_source 为书源解析专用层：原生解析器 + 脚本书源解释器 + 沙箱执行器，见 ADR-0028/0029；lib\_ebook\_db 为基础库，无交叉依赖）

```
module_app        → 应用入口（@HiltAndroidApp），组装所有功能模块
module_main       → 主页、启动页
module_book       → 书籍阅读、管理、评论（含阅读器，全部 Compose）
module_find       → 书城、搜索、书库浏览
module_me         → 个人中心、头像、评论管理、版本更新检查（见 ADR-0021）
module_login      → 登录/注册/密码（Compose UI，Coroutines）
lib_book_common   → 项目专属共享件：ebook 域共享 UI（com.ebook.common.ui，见 ADR-0006）与 Provider 接口；通用工具类与基类归口依赖的 lib_common（分界判据见 ADR-0015）
lib_book_source   → 书源解析专用层：原生解析器、脚本书源解释器（规则词法/求值/取文）、`:js` 沙箱执行器（vendored QuickJS + JNI 桥）
lib_ebook_api     → 网络层：Retrofit 服务、数据实体、OkHttp 拦截器
lib_ebook_db      → Room 数据库实体和 DAO（见 ADR-0003）
build-logic/      → 自定义 Gradle 约定插件（统一构建配置）
```

### 核心架构模式

> 本节只记**稳定约束**；接线细节（具体调用方式、基类钩子、内部机制）以相关代码与 KDoc 为事实源，改动时不要求同步本文件。

- **约定插件**：`build-logic/convention/` 提供统一构建配置，插件 ID 以 `xrn1997.` 为前缀；其中 `xrn1997.android.component` 支撑模块化开发——`gradle.properties` 的 `isModule=true` 时功能模块可独立运行，`false`（默认）时作为 library 被 `module_app` 依赖。三条铁律：**提交态 `isModule` 必须是 `false`**（`true` 时 `module_app` 不依赖任何功能模块，产出的是空壳 App）；**临时单模块独立运行直接把 `isModule` 改成 `true`，调试完改回，不要提交**；**不要用 `./gradlew -PisModule=true` 覆盖**——命令行 `-P` 会渗进 `settings.gradle.kts` 的 `includeBuild`，让 lib\_common 也被套上 application 插件、与它的 library 插件冲突而构建失败（`includeBuild` 启用时触发；两仓 compose 插件 ID 统一与其实现差异见 ADR-0020）

- **功能模块**依赖 `lib_book_common`，互不依赖；跨模块导航使用 TheRouter，服务经 `provider/` 接口暴露。

- **跨模块页面**：主 Tab 页面（书架/书城/我的）由 Provider 接口暴露 `@Composable () -> Unit`（非 Fragment），由 module\_main 的 NavHost 直接组合；Provider 由 TheRouter 创建（非 Hilt），页面依赖经页面级 `@HiltViewModel` 注入。

- **MVVM**：ViewModel 继承 lib\_common 的 `BaseViewModel`/`BaseRefreshViewModel`，经 Hilt 构造注入。

- **Activity 基类**：Compose 业务页面统一继承 lib\_common 的 `BaseActivity`（Compose 版）；例外场景（启动转场、模块独立运行的 test/debug 宿主）不继承基类的，**必须自行对齐基类行为**（主题、insets/沉浸式状态栏、状态覆盖层），以基类 KDoc 与现有宿主实现为准，禁止裸 `MaterialTheme` 造成配色分裂。

### Mock 数据源与独立开发

项目通过 product flavor 与 source set 两层机制实现 mock 数据源切换，无需后端服务器即可开发调试（机制细节以 `module_app/build.gradle.kts` 与各模块 source set 为事实源）。

**两种运行形态**：

- 集成构建（`isModule=false`）：`./gradlew :module_app:assembleRealDebug` 连接真实后端；`assembleMockDebug` 使用内存 mock 数据源（applicationId 为 `com.ebook.mock`）。mock/real 的 `NetworkModule` 分别位于 `module_app/src/mock/` 与 `src/real/`
- 独立模块（`isModule=true`）：约定插件（`AndroidComponentConventionPlugin`）只在该分支把 `src/main/test` 作为**额外 Kotlin 源码目录并入 `main` source set**，其下 `debug/` 里的 `MockNetworkModule` 等宿主件随之参与编译并绑定 mock（`debug/` 是目录命名约定，不是 source set 优先级——集成态根本不编译这个目录，不存在与 `main/` 抢类的关系）。独立运行默认 mock，无需配置

**四条铁律**：

- **两份 Manifest 是替换关系**：独立态只生效 `src/main/module/AndroidManifest.xml`、集成态只生效 `src/main/AndroidManifest.xml`——Activity 声明及其属性（`launchMode`/`theme`/`label`）两处必须同步修改，否则两种模式行为不一致（历史事故：`singleTask` 只写在集成清单里，独立模式下注册页不被清顶，登录成功后回退又露出注册页）

- **跨模块路由在独立模式下静默丢失**：业务代码跳往其他模块的路由在独立模块里不存在，TheRouter 找不到路由**只记一行日志、不报错不闪退**。需要该链路的模块在 `src/main/test/debug/` 宿主上以 `@Route` 挂同名路径占位（该 source set 只在独立模式编译，不与集成模式抢路由）。另注：新增/改动 `@Route` 后，routeMap 资产由 TheRouter transform 回写，**当次构建的 APK 仍装旧路由表，需再构建一次**才生效

- **新增接口同步 mock**：新增 `DataSource` 接口方法时，必须同步更新对应的 `XxxNetworkTest` mock 实现。JSON 资产只适用于**返回固定结构的读接口**（这类方法同时要在 `lib_ebook_api/src/main/assets/` 补对应资产）；**回显入参的写接口与文件上传接口**应在 mock 里以代码合成响应，并注释说明「为何无静态资产可对应」——静态资产表达不了「按入参变化」与「上传后真实地址」，禁止为凑规则造一份固定 JSON 冒充响应。例外：`ReleaseDataSource` 的 mock 忽略 endpoint 入参、固定回一份资产，双源 failover 因此不在 mock 里验，由 `module_me` 的 `ReleaseRepositoryTest` 用假数据源锁住

- **资产形态与解码类型同步**：mock 读资产用 `getDataFromJsonFile<T>` 的 reified 类型，必须与资产的 `data` 实际形态一致（服务端改分页包裹时 `T` 要跟着换）。错配抛的 `SerializationException` 会被 `CoroutineAdapter` 吞成「未知错误」，**页面不闪退、数据永远加载不出来**，只有一行看不出根因的 ERROR 日志——因此这类改动必须同步更新 mock 资产契约测试。第三方平台的原始 JSON 资产不带 `RespDTO` 信封，不走 `getDataFromJsonFile<T>`，把整个资产直接解成实体（见 ADR-0021）

### 响应式编程约定

- 统一使用 Kotlin Coroutines + Flow（`viewModelScope.launch`、`suspend` 函数）；RxJava3 依赖与代码引用已全部移除，**禁止重新引入**

- **事件总线**：SharedFlow（`BookRepository.bookShelfEvents`，见 ADR-0004），已替代 RxBus

- **遗留命名**：`initBaseViewObservable()` 是 lib\_common 基类的历史钩子名（实现均为协程），与 RxJava 无关，新代码不要望文生义

## 构建约定

- **依赖版本仅通过版本目录管理**（`gradle/libs.versions.toml`），构建脚本中引用 `libs.xxx.yyy`，不硬编码版本号；各依赖的具体版本号以版本目录为准，本文件不复述。**已知结构性豁免**：`settings.gradle.kts` 的 `plugins {}` 块读不到版本目录，其中 foojay resolver 插件的版本为字面值，不是漏改

- **工具链**：字节码目标 JDK 17；构建守护进程 JVM 由 `gradle/gradle-daemon-jvm.properties` 自动拉取，无需本地预装

- **AGP 9 内置 Kotlin（built-in Kotlin）**：Android 模块不再应用 `org.jetbrains.kotlin.android`（约定插件 `xrn1997.android.*` 不包含 KGP，Kotlin 支持由 AGP 提供）；顶层 `kotlin { compilerOptions {} }` 块仍可用（AGP 注册了 `KotlinAndroidProjectExtension`）。`gradle.properties` 不设置 `builtInKotlin`/`newDsl` 开关（默认内置 + 新 DSL）。lib-common-build 的 lib\_common 同规则（约定插件由 android-ebook 的 build-logic 提供）

- **AGP 9 源码目录注意**：给 Kotlin 编译添加源码目录走 `sourceSets` 上的 `kotlin.directories.add(...)`（仓内实例：`AndroidComponentConventionPlugin` 在独立态加 `src/main/test`）；`java.srcDirs(...)` 不再被 Kotlin 编译拾取（但 KSP 仍可见，会造成"KSP 生成了代码、Kotlin 没编译"的错位）

- **原生构建（脚本书源沙箱）**：`lib_book_source` 经 build-logic 的 `xrn1997.android.native` 约定插件编 C++，QuickJS 内核 vendored 在 `third_party/quickjs/`、桥接层在 `lib_book_source/src/main/cpp/`。ABI 口径：插件给 release 的最小集只有 `arm64-v8a`（多余 ABI 等于多带一份可被研究的内核副本），模拟器所需的 `x86_64` 由模块自己在 debug 里显式放宽——放宽点写在模块而非插件，是为了让「谁多带了一份内核」当场可见

- **图片加载**：Coil（Compose）。Glide 已随 View 体系一并移除，不要再引入

- **日志统一走** **`com.xrn1997.common.util.Logger`**（级别控制、debug/release 自动裁剪），禁止直接调用 `android.util.Log`

- **注释要求**：每个类、每个方法、每个重要逻辑分支都必须有足够的 KDoc/注释，说明"是什么、为什么"——包括设计决策、竞态条件、跨线程可见性等审查时需要知道的背景

- **文档同步**：代码、注释与文档（本文件 / CONTEXT.md / docs/adr/）必须保持一致——任何改动在提交前同步更新相关注释与文档，禁止留下"代码已改、注释/文档仍是旧描述"的状态

- **评审/grill 驱动改动的沉淀**：由代码评审、grill 会话等驱动的架构级决定（依赖替换、体系迁移、组件归属等）必须沉淀为 `docs/adr/` 的 ADR，不能只留在提交信息与会话记录里

- **跨仓库 ADR 编号**：android-ebook、android-practice（lib\_common）与 ebook-server 是三个独立项目，各有**连续递增、互不对齐**的 `docs/adr/` 编号序列（三仓都从 0001 起编，序列在 0001 段即已重叠）。**代码注释与文档中禁止引用其他仓库的 ADR 编号**（`lib_common ADR-xxxx` / `ebook-server ADR-xxxx` 一律不写）——跨仓编号对只看本仓的读者无意义、且随对侧重排失效；需要表达的外部决策把内容写成自足描述（不怕重复，如「服务端契约：邮箱为登录主标识」）。本仓自己的 ADR 编号裸写即可；`docs/adr/` 内做跨仓溯源时用提交 hash 等本仓可验证的锚点，不引外部编号

- **ADR 独立完整与更新**：每篇 ADR 独立完整地讲清一件事，单篇打开即可读懂、不依赖别篇作前情——需要别篇决策的背景时写成自足描述（不怕重复），**ADR 正文不交叉引用本仓其他 ADR**（`见 ADR-xxxx` 一律不写）。允许就地更新（补事实、纠处方、标日期）；原决策局限性大、结合新需求已不止是扩展时**整篇重写**，不层层打补丁——引用链与补丁摞补丁正是 ADR 越养越难读的来源

- **认证体系约定**：**邮箱为登录主标识**（用户名仅展示用、可重复；注册三步不发 token，见 ADR-0009）。**access token 只驻内存不落盘**（lib\_common 的 `TokenHolder`），由 `AuthInterceptor` 附加到请求头，且**只对白名单内 host 附加**——第三方站点永远拿不到用户 token；冷启动为空，由首个请求的 A0230 经静默刷新补上。双 token 持久化由 `AndroidUserSessionManager` 负责（**密码不落盘**），会话生命周期各时点同步 `TokenHolder`。**A0230 过期由网络层收口**：单飞静默刷新 → 成功重放一次原请求；刷新失败发会话过期事件全局处置（清会话 + 提示 + 跳登录，见 ADR-0010），调用方对会话过期**一律经 `lib_book_common` 的 `reportFailure` 上报**（其内部只记日志、不重复提示），**不要再手写 `isSessionExpiredHandled` 分支**（见 ADR-0025）。**不同信任域各用各的客户端**：书源请求（第三方网站）走 `@Named("source")` 纯净客户端、版本检查走 `@Named("release")`，均**不得携带 token**；服务端载荷为蛇形命名，DTO 边界翻译用逐字段 `@SerialName`，不开全局命名策略。ebook-server 基址经 `local.properties` 的 `ebook.server.host` 注入 BuildConfig，不硬编码。**本地调试地址**：Android 17（targetSdk 37）起 `10.0.0.0/8`、`192.168.0.0/16` 等算「本地网络」，访问需 `ACCESS_LOCAL_NETWORK` 运行时权限，未授权时 OkHttp 直接报 `sendto failed: EPERM`（症状是「页面不闪退、数据永远加载不出来」）；本项目不申请该权限（普通用户用不到，Play 需额外论证），真机/模拟器连本机后端一律用 `adb reverse tcp:9090 tcp:9090` + `ebook.server.host=127.0.0.1`（回环不属本地网络），或直接用公网 IP。**用户会话有三处镜像**（① `user_session` SP 文件；② `spUtils` 的 `SP_IS_LOGIN` 等兼容键；③ `ProfileRepository` 的内存 StateFlow，装着昵称/头像供「我的」页渲染），**清会话一律只调 `userSessionManager.clearSession()`**，三处由它内部一并覆盖；**禁止调用方自行「成对」补调 `ProfileRepository` 的清理方法**——旧约定是 SP 镜像尚未收进 `clearSession()` 时的写法，如今既多余、又会掩盖真正容易漏的内存镜像（表现：会话已过期、token 已清，但「我的」页仍显示上一个身份的昵称与头像）

- **前台服务约定（离线下载，见 ADR-0018）**：`DownloadService` 声明为 `foregroundServiceType="dataSync"`，两参 `startForeground(id, notification)` 合法且足够（`MissingForegroundServiceTypeException` 只在 manifest 未声明类型时抛）——**不存在「必须改成三参调用」的问题**，不要按这类评审断言改动；targetSdk 35+ 的真实约束（dataSync 6 小时/24h 配额、配额用尽或应用后台时启动被拒）见 ADR-0018。由此四条：**拉起服务一律走 `DownloadService.start(context, intent)`**（返回 false 即需提示用户），禁止在页面/ViewModel 里直接调 `ContextCompat.startForegroundService`；**发起下载先入库再拉服务**（`BookReadViewModel.startDownload`），否则启动被拒时只躲在 Intent 里的任务会丢；**`onTimeout` 内只做数秒可完成的收尾**，不得查库/发网络请求/起协程；**失败章必须在重试耗尽后出队**（`DownloadService.downloading`）——队头被失败任务占住会让服务在同一章上无限重试（常驻通知不消失、该书后续章节全被阻塞），跳过章数经 `skippedCount` 带进收尾文案，不静默丢章；暂停中断重试时**不出队**（任务保留待续跑）

- **混淆规则约定（见 ADR-0024）**：release 混淆已开启（`module_app` release 恒开；功能模块 `isMinifyEnabled = isModule`——独立态开启以尽早暴露规则缺口，集成态关闭以避免 AGP 对 library 执行 R8 剥离类）。**文件拆分遵循 Android 标准约定**：`consumer-rules.pro` 挂 `consumerProguardFiles`（集成态随 AAR 传播，规则内容的唯一来源）；`proguard-rules.pro` 挂 `buildTypes.release.proguardFiles`（独立态自己执行 R8），功能模块的 `proguard-rules.pro` 仅含 `-include consumer-rules.pro` 避免重复；纯 library 模块只需 `consumer-rules.pro`。**规则归属**：只写本模块反射面需要的规则，第三方库（kotlinx-serialization、Retrofit、Room3、Hilt、Coil、Compose、AndroidX）自带 consumer 规则已覆盖，不重复、不凑 `-dontwarn`；新增反射面时先查依赖是否自带规则（grep jar/AAR 内 `META-INF/proguard/` 与 `proguard.txt`），有则不写、无则手写并注释证据。**禁止无证据的 `-keep`/`-dontwarn`**；行号属性（`-keepattributes SourceFile,LineNumberTable`）只放 `module_app`，一处声明全局合并

- 不要引入新的编译警告，提交代码应保持警告清洁

## MVVM 架构约定

项目采用严格的 **Model → ViewModel → View** 三层结构，由 `lib_common` 提供基类。

### Compose 体系

lib\_common 还同时提供 ViewBinding 一族基类（服务它自己的旧下游），**本仓不使用**——勿据此新建页面，新页面一律走本节 Compose 体系。

```
BaseActivity (Compose)
  └── BaseMvvmActivity<VM : BaseViewModel<*>>
        └── BaseMvvmRefreshActivity<VM>      (基于 RefreshableList)
```

- 抽象方法：`PageContent()` (Composable)；`initData()` 已有默认空实现，无数据页面无需覆写

- **持有 ViewModel 的页面必须继承** **`BaseMvvmActivity`**（而非裸 `BaseActivity` + 手动 `by viewModels()`）：一次性命令通道（`sendToast`/`sendFinish`/`sendNavigate`）与 loading 覆盖层只由基类里的 `MvvmBinder` 消费，绑不上不报编译错、只会静默失效（命令堆积在 Channel 里随 ViewModel 销毁丢弃）——表现是"页面不弹提示、该关的页不关，返回栈里露出残留中间页"

- 状态通过 Compose `mutableStateOf` 管理

- 统一提供：Toolbar（`enableToolbar()` 开关、`showBackButton()` 返回箭头、`toolbarTitle` 标题）、状态栏 insets 自动处理（`enableFitsSystemWindows()` 开关）、加载/空态/网络错误覆盖层

- 全局主题由基类经 lib\_common 的 `AppTheme` 装配点提供，子类不在 `PageContent` 中重复包裹 MaterialTheme（**唯一例外**：阅读器整片豁免系统深色，在 `ReadBookActivity.PageContent` 内做作用域固定浅色，见 ADR-0012；其余场景一律禁止）

### Hilt 注入模式

```kotlin
@Singleton
class XxxModel @Inject constructor(
    private val repository: XxxRepository,
) : BaseModel()

@HiltViewModel
class XxxViewModel @Inject constructor(
    model: XxxModel,
) : BaseViewModel<XxxModel>(model)

@AndroidEntryPoint
class XxxActivity : BaseMvvmActivity<XxxViewModel>() {
    override val viewModel: XxxViewModel by viewModels()
    // override fun PageContent() { ... }  // Compose 体系
}
```

- **无 Model 门面的 ViewModel 用 `NoOpModel` 占位**：纯展示页（依赖直接注入多个仓库、无一次性命令需求）仍须继承 `BaseViewModel`，Model 位传 lib\_common 的 `NoOpModel()`（实例：`MePageViewModel`），**不得直继 `androidx.lifecycle.ViewModel`**——否则全仓 VM 基类约定出现例外，后续接 `BaseMvvmActivity`/命令通道时要连带改页面。子类状态流命名避开基类的 `uiState`（覆盖层专用），沿用 `meState`/`detailState`/`cacheState` 这类「页面名 + State」

- **Application 上不许有 eager `@Inject` 字段**：`:js` 沙箱进程里框架仍会实例化 `Application`（`ActivityThread` 在隔离进程只跳过少数几步、不跳过 `makeApplicationInner`），而进程门写在 `super.onCreate()` **之后**、Hilt 的字段注入发生在 `super.onCreate()` **里面**——加一个注入字段等于把门挪到注入之前，沙箱进程起来即崩，且崩的现场藏在 binder 连接超时里。需要重对象就经 `EntryPointAccessors` 在用到那一刻取（`MyApplication` 的 `ContentStoreEntryPoint`、`BookApplication` 的 `ThemeModeManagerEntryPoint` 同法）。进程门本身两处都要有：`BookApplication` 与 `MyApplication`（`SandboxProcess.isInIsolatedProcess`），漏一处就是给一个读不到本应用数据目录的进程装配主题与仓库

## 测试约定

- 单元测试使用 JUnit 4，位于各模块 `src/test/java`；插桩测试位于 `src/androidTest/java`

- 测试类命名 `<Subject>Test`；测试方法使用反引号包裹的句子式描述

- 行为变更时同步更新已有测试，保持测试与实现一致

- 测试覆盖待办见 `docs/test-coverage-todo.md`

## 领域文档

- **领域术语表**：`CONTEXT.md`（纯术语，无实现细节）。涉及领域概念时先查阅，术语冲突时以 CONTEXT.md 为准

- **架构决策记录**：`docs/adr/`。重大决策（难回退 / 无上下文令人惊讶 / 有真实权衡）必须记录，
  写作规范与判据见 `docs/adr/ADR-FORMAT.md`（以 domain-modeling 技能规范为底、按本仓既有 ADR 校准）

- **`docs/superpowers/` 是一次性工件，不是事实源**：`plans/`（施工计划）、`specs/`（设计与契约）、
  `reviews/`（评审记录）记录的是**当时打算怎么做**，落地后通常不回写。两条硬约束：
  **勾选框 `- [ ]` 一律不代表待办**（多数计划执行完毕却整片未勾），照旧计划动工前须先核对代码实况；
  **进度与结论以代码、KDoc、`docs/adr/`、CONTEXT.md 为事实源**，plan 里被后续决策撤销的设想只在
  其头部「状态说明」段有标注——读到没标注的 plan 不等于它仍有效。规格（`specs/`）例外：
  脚本书源语法规格是本仓指定的事实源，与实现冲突时以规格为准

- 本项目为单上下文仓库，无 CONTEXT-MAP.md

- **`.qoder/repowiki/` 是工具生成的快照，不是事实源，且不手工维护**：该树（RepoWiki 中文文档 +
  `knowledge/` 知识卡片，即 Agent 侧 `SearchKnowledge` 读到的内容）由 Qoder 一次性生成，此后**不随代码更新**。
  生成时点早于多书源/脚本书源落地，故**整棵树上找不到 `lib_book_source` 这个模块**，另有若干与代码相反的
  描述（例：书城分类区块仍写作 `items(kindBooks, key = it.kindName)`，而该 key 已因空标题撞 key 崩溃改为
  `itemsIndexed`）。据此新建代码或做架构判断会直接错过解析器与沙箱这一整层。需要更新时**重新生成该树**，
  不要逐文件改——改完下次生成照样被覆盖

## Agent 实战建议

- 涉及书源改动时注意：本仓**不内置任何书源**（书源一律由用户导入），不要去找内置清单——它不存在（`lib_book_common` 的 assets 里已无书源资产，首启也不会灌库）。适配新网站优先改用户导入的 JSON 规则而不是硬编码选择器；**架构是多书源共存**（ADR-0016），勿按单书源描述本模块：每本书按 `tag` 绑定归属书源，解析一律跟书走——书架/详情/下载取 `entity.tag`、正文取 `BookLocation.sourceUrl`，最终都经 `bookSourceManager.getParserFor(url)` 拿源。`requireParser()` / `currentParser` 已删除且**不得重新引入**：按全局默认源去解另一站点的书不会闪退，只会拉回不相干的内容，比崩溃难查。书城与「新加的书绑哪个源」都由 `observeDefaultSource()` 给出（默认源每次由 `defaultFromRows(Room 行)` 现算，SP 只记「用户上次主动选了哪个」；载体是格式中立的 `SourceDefinition`，脚本行同样可成为默认源；无启用源时发 null，页面进引导态）——**同步读面已无任何出口**，别为了「首帧不空白」重新修一条；搜索用 `searchAcross(keyword, page, skipSourceUrls)`——「哪些源已到底」只有调用方知道，故由调用方每轮带进来（Manager 不持有搜索会话状态，免得长出第二个事实源）。清单与默认源都只有 Room 一处事实源（表里每一行都由用户导入，不存在被随包配置回填覆盖的可能）。**默认源的 parser 与其它源同构**（一样进 LRU）：覆盖规则只需一次 `evictParser`，不再有「连定义带 parser 一起换」这套口令——那种夹带只属于已拆除的内存快照时代。书城页面判据是 `LibraryViewModel.sourceState` 四档：首帧是 `Unknown`（默认源由 Room 现算，页面那一刻还没有结论 → **整片留空**，既不画引导语也不画半截切换器，别拿 `NoSource` 兜底——那会让有源用户的冷启动先闪一帧「请导入书源」的误导文案）；「一条源都没有」与「有源但当前源解析不出来」两句话各走各的，**禁止**把后者并进前者（那会把用户支去启用那条本就坏掉的源），零源空态另有「去导入书源」入口（跨模块跳书源管理页，独立模式由占位宿主承接）。**脚本书源（ADR-0029：导入、解释器与执行器）在仓，下面几件事别重新发现一次**：`book_source.format` 列值是小写 `native`/`script`，读写一律取 `SourceFormat.raw`——拿枚举 `name` 当列值不报错也不闪退，只会让脚本行静默按原生规则解，拉回的是不相干的内容。两种格式同表共存由 `BookSourceManagerImpl` 的 `toDefinition` 按该列路由：规则类型化读面（`getAllSources`/`getEnabledSources`/`getSourceByUrl` 与默认源回落）经 `toRule` 一律挡下非原生行，而 `observeSources()` 必须继续带出脚本行（管理页要看得见、能禁用、能删）——两侧口径本就不同，别去「统一」。`getParserFor` 对脚本行**永不返回 null、也不在取 parser 时抛**（`ScriptRuleSet.load` 惰性到首次求值才跑，坏 JSON 在那里抛类型化 `ScriptRuleParseException`），而 null 恒等于「书源不存在/坏行」，两者**不得混用**（混了等于把「这条规则解不动」说成「这条源已失效」，用户会被支去重导一条本来好的源；构造期抛则会在缓存锁内炸出未预期异常）。跨模块可见性坑：Kotlin `internal` 只在同模块（含同模块测试源集）可见，依赖方的 test source set 不是 friend module，故 `JsoupBookParser.rule`、`ChapterPageMatcher` 与 `ScriptBookParser` 的生产构造都是 public——上浮解析器时不要顺手收窄，且解析器测试必须与实现放在同一模块。`JsoupSourceReader` 住在 `lib_book_common`、**不随解析器迁走**：它依赖 `BookStore`/`ChapterReader`/`BookSourceManager`，搬去 `lib_book_source` 就是一个 Gradle 环。**脚本书源的规则词法层在 `lib_book_source` 的 `com.ebook.source.script`**，只回答「这条规则串被切成什么」（模式标志表、括号感知的组合符切分、`##` 替换三形态、索引方言、链式段结构、原始 JSON → `ScriptRuleSet`），词法层自身零网络零 Jsoup（同包里的求值/取文后端要用 Jsoup，别把这个包当无依赖层），**事实源是 `docs/superpowers/specs/2026-09-08-script-source-rule-grammar.md`（下称规格），与它冲突时以规格为准**。两处口径是**本仓规定而非上游实证**：`RuleSplitter` 的 `%%`→`||`→`&&` 次序（§2.3）与 `IndexSelector` 的闭区间＋「越界逐项过滤、一律不抛」（§4）——公开文档没给答案，改一处必须规格、实现、单测三处同步，留一处就长成「同一个规则串两种解法」。切分主线外另有一道**前置例外，改次序时务必保住**：`@js:`/`<js>`、以 `:` 开头的正则 AllInOne 与 §2.5 的前导 `-`（列表反序）都在进组合符循环**之前**判出（§2.2 硬约束 1「组合符不包括 js 和正则」；`()` 不计入括号深度，扫描器看不见正则字面量里的 `||`），把这三项顺手「统一」回切分主线的症状不是报错而是拿链式规则去解正则、静默解错内容；反序前缀由各求值后端在产出前消费（元素集/条目集整体倒转）。**HTML 侧求值入口是 `ScriptRuleEvaluator.evaluate`**：链式 / `@css:` / 正则 AllInOne 三后端、`&&`/`||`/`%%` 组合符、取值器与索引消费、`##` 替换段、`@put:`/`@get:` 变量与 `{{}}` 插值的声明式子集。三条容易踩的口径：**(a)** 「未取到值」必须是 `RuleResult.Miss`——折叠成空列表会让 `||` 分不清「这一支没值」与「取到零条」，短路整体失效；**(b)** AllInOne 的产物是**条目×捕获组二维**（`RuleResult.Matches`），压平成一维 `$n` 字段就无从落地；**(c)** 任何还不支持的语法一律抛类型化异常（`RuleSyntaxException`/`JsEvaluationPendingException`/`UnsupportedRuleFeatureException` 各管一种失败），返回空结果等于对用户撒谎「这个源没有这条信息」。**JSONPath 与 URL 语义、取文层**（`JsonPathBackend` 三形态进 JSONPath；`ScriptUrlResolver`+`ScriptPageFetcher` 串起「URL 规则串 → 请求 → 响应文本」；发现页条目切分 `ExploreUrlFormat`）；`{{}}` 里的算术表达式按 JS 待执行抛出，取文层不得绕过它自己算页码。三条容易踩的口径：**(a)** 取文按选项 charset **显式字节解码**（`bytes()` 取回再 `String(bytes, charset)`），走 `body.string()` 会吃掉 `@Named("source")` 客户端 `EncodingInterceptor` 强改的 UTF-8、让 gbk 站全部乱码；**(b)** 相对落位复用 `TocPageUrl.join` 三形态语义，`ListPageUrl` 的首页裁剪**绝不**用于脚本 URL——对算好的 URL 再裁一次会裁掉真实页码段；**(c)** JSONPath 切片是标准半开方言（end 排他）、与链式索引的闭区间分属两个语法域不得互推，过滤器 `[?(…)]` 按不支持拒绝。**声明式求值链路全线接页面**：`ScriptBookParser` 实现 `BookParser` 五面（搜索/详情/目录/发现分类/书库首屏）与 `ScriptContentParser` 正文接缝——聚合搜索候选含脚本行、正文读取按 `ScriptContentParser` 分岔、脚本源可搜索可加书架可全链路阅读。四条口径：**(a)** 脚本 URL 的关键词 `{{key}}` 按表单百分号编码（§11-20）；**(b)** `getParserFor` 对脚本行**永不 null**，坏 JSON 在首次求值抛类型化装载失败——null 仍只表示「书源不存在/坏行」；**(c)** 目录/正文翻页判据（零新增/回环/上限）与原生同套，但**不移植** `ChapterPageMatcher`（§7.3）；**(d)** **书城接线**：默认源载体是密封 `SourceDefinition`（Native 带规则、Script 带原文+实体列展示信息），两种出身都可承载；现算与写路径收敛共用 `defaultFromRows` 一处（条目面、不筛格式），`observeDefaultSource()` 是唯一出口；`LibraryViewModel.sources` 放开 format 过滤（切换器候选 = 启用行）；分类入口经 `BookSourceManager.getExploreEntries`（原生 kinds / 脚本 exploreUrl 条目，url 承载规则串，`ScriptExplore` 是唯一公开门面）。别重新发现的坑：默认源**没有同步初值也无从猜**（assets 与冷启动快照都已随内置书源拆除），首帧落点是 `LibrarySourceState.Unknown` 整片留空、Room 答复后落定；`SourceDefinition.Script` 的 name/url 生产填充点只有 `toDefinition`，别去解 `rule_json` 取展示信息；`getAllSources`/`getEnabledSources`/`getSourceByUrl` 三面仍只含原生行（这是保留语义不是漏改）。`ScriptRuleSet.unsupported` 是装载期登记的能力缺失集合，**尚未上 UI**：要出逐项警示就从这份数据接，别在解析器里另判一次。含 JS 的规则段（`@js:`/`<js>`/`js`/`bodyJs`/`init` 的 JS 分支）交沙箱执行器求值，只有**没装配执行器**的路径仍抛 `JsEvaluationPendingException`。**脚本书源的沙箱执行器（ADR-0028/0029）**是本仓首个 C++ 模块（`third_party/quickjs/` vendored + `lib_book_source/src/main/cpp/`，build-logic 的 `xrn1997.android.native`）。四条口径：**(a)** **永不在主线程调沙箱**——脚本回调要回主进程，主线程调过去就是等自己；执行器客户端有主线程守卫，改它之前先想清楚回调线程模型（`HostHandler.handle` 同步、transport 是 suspend，靠 binder 线程池上 `runBlocking` 接起来）。**(b)** 未装配执行器与执行失败是两件事：`ctx.js == null` 抛 `JsEvaluationPendingException`（消息片段「需脚本沙箱执行器」被 `lib_book_source` 的 `SandboxScriptJsTest` 断言，**改消息会红它**；`JsoupSourceReader` 侧只把类型化异常原样透出、不解析这句话），真跑了而失败抛 `JsExecutionFailedException` 带内核原文；把两者合并成「该源已失效」会把用户支去重导一条好源。**(c)** 沙箱外呼只用 `GuardedNetwork` 派生的守门客户端，host 白名单由 `SourceHostAllowlist` 从源规则原文导出——自己 new 一个 OkHttpClient 就等于绕过私网拒绝与白名单，而 `:js` 进程零权限因此只剩一半。守门目前覆盖的是**脚本自己发起的外呼**，URL 选项 `js` 改写出的地址仍由取文层按原路径发出（不带凭证，但也不查白名单与私网），这条缝隙登记在 ADR-0028 的遗留里。**(d)** Hilt 装配在 `lib_book_common`（`lib_book_source` 无 Hilt/KSP），`JsSandboxHost`/`HostCallbackRouter` 为此上浮 public：`provideJsSandboxHost` 里转子必须**先建、再传进客户端**，两边不是同一个对象时每条回调都报「主进程没有正在进行的脚本任务」，这一条 JVM 测锁不住、只有装机能验

- `book_source` 表是书源清单的**唯一**事实源：应用不随包携带任何书源，也没有首启 seeding（表里每一行都由用户导入），因此不存在「内置行受保护」——任何一条源都可删、可禁用、可设默认。新增/改动 `BookSourceManager` 的成员要同步测试替身——具名替身 `FakeBookSourceManager` 在 `lib_book_common` 与 `module_me` 各一份，其余是 `module_find`/`module_book` 测试文件里的内联实现；改完接口用 `grep ": BookSourceManager {"` 现数一遍，别信任何写死的清单——漏改的一侧直接编译失败。书库缓存（cacheDir 文件版，见 ADR-0030）：key 只能由 `libraryCacheKey(sourceUrl)` 生成、文件名取其 MD5，读与写都收在 `lib_book_common` 的 `LibraryDiskCache` 一处——各处各拼一次字符串稍有差别就变成「写完永远读不到」，表现为每次进书城都在重拉网络而功能上看不出毛病；**缓存策略（TTL 6 小时、进页 SWR、下拉强刷 `LibraryLoadPolicy`）住 `module_find` 的 `BookSourceRepository`，解析器不碰缓存**（`BookParser.fetchLibraryData()` 纯网络解析）——把策略写回解析器是旧病根，那会让「下拉刷新」再被缓存命中吞掉

- 涉及清单权限改动时，先读 ADR-0022（逐条判据与保留项理由）：删一条的前提是**全仓（含依赖的 lib\_common）找不到需要它的 API**，不是"看着没用"；两份 Manifest 的权限条目必须同步增删，改完必须读 `module_app/build/intermediates/merged_manifests/*/AndroidManifest.xml` 核对**合并结果**（库可能把自己声明的权限加回来，必要时才用 `tools:node="remove"` 覆盖）。存储三项（`MANAGE`/`WRITE`/`READ_EXTERNAL_STORAGE`）与 `FOREGROUND_SERVICE*` 不得顺手删；拍照/相册/导入/下载通知四条回归属人工装机验证项

- 涉及版本更新检查改动时，先读 ADR-0021：发布源顺序/failover/`.apk` 过滤等**策略**在 `module_me` 的 `ReleaseRepository`，HTTP 与接缝在 `lib_ebook_api/service/release`，**不得**把仓库层写回 `lib_ebook_api`；该请求走专属 `@Named("release")` 纯净客户端（不带 token、不套解 `RespDTO` 信封的 `CoroutineAdapter`）。两条不变量：**判不出结论就不算检查成功**（远端 tag 解析不出版本、本地版本号读不到 → 按检查失败处置且不写限频时间戳）；**「是否有新版」是派生态**（由上次检查到的 tag 与装机版本现场比较，不落地成布尔量）

- 涉及前台服务/离线下载改动时，先读 ADR-0018（要点已收进上面「构建约定 → 前台服务约定」）

- 涉及 Room 实体操作，注意主键策略是两套：自然键表（`note_url`/`content_ref`——`content_ref` 是内容定位符：本地书存章文件相对路径、网络书存章节 URL）直接 `OnConflictStrategy.REPLACE` 整行替换；自增键的流水表（下载队列）主键是自增 `id` 另挂唯一索引，upsert 必须先查回旧行、用 `existing?.id ?: 0L` 回填主键才算**原地覆盖**——传 0 是让 SQLite 分配新行，同唯一索引会撞成「先删后插」（实例见 `DownloadChapterDao.getChapterByUrl` 与 `DownloadRepository.addTasks`）（见 ADR-0003）

- **改实体必须接迁移链**：version +1、在链上追加紧邻的 `MIGRATION_n_n+1`（不跳版、不删旧迁移）、提交 Room 生成的新 schema JSON；禁止启用 `fallbackToDestructiveMigration`（ADR-0003「Schema 演进」）

- 涉及正文分页跟进（多页拼接）时：判定基准是**目录页给出的原始章节 URL**（不对入口剥后缀），只对「下一页」候选链接剥一次分页后缀再比。对入口也剥离会让「章节号写在连字符后」的站点剥后同形而**串章**（一路跟进后续章节直到页数上限，正文错乱 + 数十次冗余请求）；「第 1 页也带后缀」的站点与此结构同形、无法靠 URL 区分，取舍是**宁漏页不串章**，真要支持需在书源规则里声明分页模板。边界形态已由 `ChapterPageMatcherTest` 锁死

- 涉及列表分页（分类页/搜索页 URL 模板）时：模板**必须带 `{{page}}`**，否则「加载更多」每页都在请求同一个首页；页码换算与渲染统一走 `JsoupBookParser` 的 `ListPageUrl`（它把以 `/{{page}}` 结尾的模板在首页裁掉页码段——裸路径首页 `/xuanhuan/1` 是 404），故取首页的调用也必须经它，不要自己 `replace("{{page}}", "1")`。判「到底」不能只看空页：**越界页会以 HTTP 200 重复返回首页书目**（软 404），因此追加页一律按 `noteUrl` 去重、无新条目即置 `hasMore=false`——列表页以 `noteUrl` 作 item key，重复条目直接抛异常。形态由 `ListPageUrlTest` 与 `BookPageMergeTest` 锁死

- 涉及本地书籍导入或章节正文读取时，先读 `docs/superpowers/specs/2026-09-04-local-book-import-design.md`。**导入判重（见 ADR-0023）**：口径是待导入文件的 `comment_key` 等于书架某条目的**当前主键**，不是比 `book_info.name` 书名——键含作者，只比书名会把同名不同作者的两本书判成一本并给出删除入口。处置四个动作（继续添加/智能合并/覆盖/取消——最后一个不导入本文件、批次里的其余文件照常走），非破坏的「继续添加」占主按钮位；顺序一律**先导入新条目、后处置旧条目**，覆盖删旧之前先 `absorbGroupKeys` 吸收旧条目的关联键。补章只对本地目标书，且要求旧书归一化章名序列是新书的**前缀**，分叉即整笔放弃；新索引取现有最大 `durChapterIndex + 1`，不用 `size`（历史删章留下的洞会让二者不等，从而覆写既有章文件）

- 修改 Compose 页面时遵循 Material Theme 语义色（`MaterialTheme.colorScheme`），禁止硬编码颜色（阅读界面背景主题除外）

- 涉及缓存管理页改动时：本页「缓存」只指 `cacheDir`（图片/临时/其他三档，可清，`cacheBreakdown` 一次遍历分档）；书籍内容住在 `filesDir/books`，由 `BookStore.storageUsage()` 一次遍历给出「占用 + 册数」并单列一行——**不计入可清理总量、本页不删书**（删书唯一入口是书架长按），该行可点，用 `CLEAR_TOP` 回主页（其 `startDestination` 即书架）；独立运行无该路由时按 `matchRouteMap` 退化为不可点，别留假箭头。把两者合并或在本页顺手加删除，会造出第二个删书入口与两种占用口径（见 ADR-0026）

- 跨模块共享的 Compose 组件（卡片/列表项/标签/封面/头像等）统一归口 `lib_book_common` 的 `com.ebook.common.ui`（`CommonUiTokens` 设计常量 + `CommonCard`/`CommonListItem`/`InfoChip`/`BookCover`/`Avatar` 等），字号走 Material typography，兜底默认图与组件同处一档（`drawable-xxhdpi`，不在业务模块留副本），不要在模块内新建重复实现（见 ADR-0006）

- **共享件上浮两步走**：迭代中发现明显的跨模块共性（域无关的工具函数、通用组件等），**优先抽到 `lib_book_common`** 消除重复，不必等到能上 `lib_common` 才动手——过早跨仓协调出太多版本不利于开发和维护。等 `lib_book_common` 里同类共性积累到能组成一份完整、通用的功能集时，再整体推荐迁入外部库 `lib_common`（`FileTree.treeSize` 即此类待迁移候选）

## 提交规范

本仓库遵循 [Conventional Commits](https://www.conventionalcommits.org/)：

```
<type>(<scope>): <description>

[body]

[footer]
```

### Type（标准类型，不得自定义）

| Type       | 说明                        |
| ---------- | ------------------------- |
| `feat`     | 新功能                       |
| `fix`      | Bug 修复                    |
| `build`    | 构建系统 / 依赖变更（Gradle、版本目录等） |
| `refactor` | 重构（不改变外部行为）               |
| `docs`     | 文档变更                      |
| `test`     | 测试变更                      |
| `perf`     | 性能优化                      |
| `chore`    | 杂务（CI 配置、脚本、工具链等）         |
| `revert`   | 回退提交                      |

### Scope

Scope 直接使用模块目录名：

- **业务模块**：`module_app` / `module_main` / `module_book` / `module_find` / `module_me` / `module_login`

- **共享/基础库**：`lib_book_common` / `lib_book_source` / `lib_ebook_api` / `lib_ebook_db`

- **横切关注点**：`build` / `docs` / `scripts` / `adr`

- **跨模块**：`all`（改动覆盖多个模块且无法归到单一模块时使用）

- **可省略**：改动仅涉及根目录文件（如 `.gitignore`、`AGENTS.md`）或与 scope 无关的杂务时，可省略 scope

### Description

- 使用中文，不超过 72 个字符

- **动词前置**：以动词开头（新增…、修复…、重构…、升级…），禁止名词性短语（如"关于…的修改"、"xxx 的变更"）

- **英文缩写保持原样**：TheRouter、MVVM、Hilt、Room 等专有名词保持原始大小写，不强行翻译

- **禁止模糊表述**：不允许"更新了 xxx"、"优化了一下"等无信息量描述

- **禁止嵌入 issue 编号**：编号放在 footer（`Closes #123`），不要写在 description 里

- **不加句号**

### Body

- **何时写**：单行 description 不足以解释动机或影响时，用 body 补充 why / how

- **格式**：与 header 之间空一行；**正文优先用列表分条目**（每个条目一个改动点，条目按行换行、续行缩进两空格），每行不超过 100 字符，禁止一整段超长单行；条目组之间空一行

- **语言**：与 description 一致（中文）

### Footer

- **Breaking Change**：在 footer 中声明 `BREAKING CHANGE: <描述>`，或在 type 后加 `!`（如 `feat!: ...`）

- **关联 Issue**：`Closes #123` / `Refs #456`

### 语义版本映射

提交类型与版本号 bump 的对应关系（Conventional Commits 核心价值，用于自动化版本发布）：

| type / footer                                                                | 版本 bump       | 示例        |
| ---------------------------------------------------------------------------- | ------------- | --------- |
| `fix`                                                                        | PATCH (0.0.x) | 修复崩溃      |
| `feat`                                                                       | MINOR (0.x.0) | 新增功能      |
| `BREAKING CHANGE` / `!`                                                      | MAJOR (x.0.0) | API 不兼容变更 |
| `build` / `chore` / `docs` / `test` / `refactor` / `perf`（无 BREAKING CHANGE） | **不 bump**    | 版本号不变     |

任何 type 都可以携带 `BREAKING CHANGE`（不限于 `feat`/`fix`），如 `refactor!: 删除废弃 API` 触发 MAJOR。

### Revert 格式

Revert 有特殊格式约定，`description` 为被回退提交的完整 header，body 中必须包含 `This reverts commit <hash>`：

```
revert: feat(module_me): 新增个人中心编辑资料入口

This reverts commit abc1234.
原因: 导致内存泄漏，待修复后重新合入。
```

### 示例

常规提交：

```
feat(module_me): 新增个人中心编辑资料入口     → MINOR bump (0.x.0)

引入头像上传与昵称修改能力，
支持登录用户维护个人标识信息。

Closes #42
```

```
fix(lib_ebook_api): 修复书源请求误携带认证 token   → PATCH bump (0.0.x)

书源请求改用 @Named("source") 纯净客户端，
避免第三方网站读取到用户 token。
```

```
build: 升级 Gradle 到 9.4.1                       → 不 bump
```

Breaking change 提交（两种写法等价）：

```
refactor!: 重构认证 token 流向                   → MAJOR bump (x.0.0)

将 token 持久化从 lib_ebook_api 收敛到 lib_common 的 TokenHolder，
调用方需更新导入路径。

BREAKING CHANGE: TokenHolder 移至 lib_common，旧 import 路径失效
```

```
feat(module_login)!: 移除旧版 RxBus 事件分发       → MAJOR bump (x.0.0)

全面迁移到 ViewModel + Flow，旧 onLoginEvent() 回调不再可用。

BREAKING CHANGE: AuthenticationManager.onLoginEvent() 已删除
```

### 提交前验证

- **首次 clone 后**执行 `bash scripts/install-hooks.sh`，把 `commit-msg` 校验钩子装进 `.git/hooks/`（钩子实现在 `scripts/commit-msg`）。它**强制**的只有四项：header 的 `type(scope)!: description` 结构、`type` 在白名单内、revert 形态的 body 必须含 `This reverts commit <hash>`、description 不以中文句号结尾且不超 72 字符；**动词前置、禁嵌 issue 编号、body 分行与语言等约定不受脚本强制**，靠本文件与评审约束——钩子放行不等于合规

- 运行 `./gradlew test`，并对涉及模块执行 `./gradlew :module:assembleDebug`

- **"能编译、能安装、能打开页面"三步缺一不可，但分工明确**：Agent 止于第一步（编译与静态检查），第二、三步（安装运行、打开页面确认）由人工在提交前完成。涉及 UI/启动链路（Activity、Compose 页面、drawable 资源）的改动一律适用此分工

- **构建通过不等于可运行**：`painterResource` 加载 NinePatch（.9.png）、selector 等资源类型只会在运行时抛异常（`ResourceResolutionException`），启动不闪退与页面正常渲染也只能在设备或模拟器上确认（`adb logcat -b crash` 无 FATAL EXCEPTION）。因此 Agent 未做装机验证时**必须显式交代**：留给人工的验证项是什么——打开哪个页面、走哪条路径、看什么现象，并标注该步骤未验证。禁止以"构建通过"暗示改动已验证
