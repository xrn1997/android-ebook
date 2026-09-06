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

依赖方向：**业务模块 → lib\_book\_common → lib\_ebook\_api → lib\_ebook\_db**（lib\_ebook\_db 为基础库，无交叉依赖）。

```
module_app        → 应用入口（@HiltApplication），组装所有功能模块
module_main       → 主页、启动页
module_book       → 书籍阅读、管理、评论（含阅读器，全部 Compose）
module_find       → 书城、搜索、书库浏览
module_me         → 个人中心、头像、评论管理、版本更新检查（见 ADR-0021）
module_login      → 登录/注册/密码（Compose UI，Coroutines）
lib_book_common   → 项目专属共享件：ebook 域共享 UI（com.ebook.common.ui，见 ADR-0006）与 Provider 接口；通用工具类与基类归口依赖的 lib_common（分界判据见 ADR-0015）
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
- 独立模块（`isModule=true`）：各功能模块 `src/main/test/debug/` 下的 `MockNetworkModule` 自动参与编译（source set 优先级高于 `main/`）并绑定 mock，独立运行默认 mock，无需配置

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

- **AGP 9 源码目录注意**：给 Kotlin 编译添加源码目录必须用 `sourceSets.main.kotlin.srcDirs(...)`；`java.srcDirs(...)` 不再被 Kotlin 编译拾取（但 KSP 仍可见，会造成"KSP 生成了代码、Kotlin 没编译"的错位）

- **图片加载**：Coil（Compose）。Glide 已随 View 体系一并移除，不要再引入

- **日志统一走** **`com.xrn1997.common.util.Logger`**（级别控制、debug/release 自动裁剪），禁止直接调用 `android.util.Log`

- **注释要求**：每个类、每个方法、每个重要逻辑分支都必须有足够的 KDoc/注释，说明"是什么、为什么"——包括设计决策、竞态条件、跨线程可见性等审查时需要知道的背景

- **文档同步**：代码、注释与文档（本文件 / CONTEXT.md / docs/adr/）必须保持一致——任何改动在提交前同步更新相关注释与文档，禁止留下"代码已改、注释/文档仍是旧描述"的状态

- **评审/grill 驱动改动的沉淀**：由代码评审、grill 会话等驱动的架构级决定（依赖替换、体系迁移、组件归属等）必须沉淀为 `docs/adr/` 的 ADR，不能只留在提交信息与会话记录里

- **跨仓库 ADR 编号**：android-ebook、android-practice（lib\_common）与 ebook-server 是三个独立项目，各有**连续递增、互不对齐**的 `docs/adr/` 编号序列（三仓都从 0001 起编，序列在 0001 段即已重叠）。**代码注释与文档中禁止引用其他仓库的 ADR 编号**（`lib_common ADR-xxxx` / `ebook-server ADR-xxxx` 一律不写）——跨仓编号对只看本仓的读者无意义、且随对侧重排失效；需要表达的外部决策把内容写成自足描述（不怕重复，如「服务端契约：邮箱为登录主标识」）。本仓自己的 ADR 编号裸写即可；`docs/adr/` 内做跨仓溯源时用提交 hash 等本仓可验证的锚点，不引外部编号

- **ADR 独立完整与更新**：每篇 ADR 独立完整地讲清一件事，单篇打开即可读懂、不依赖别篇作前情——需要别篇决策的背景时写成自足描述（不怕重复），**ADR 正文不交叉引用本仓其他 ADR**（`见 ADR-xxxx` 一律不写）。允许就地更新（补事实、纠处方、标日期）；原决策局限性大、结合新需求已不止是扩展时**整篇重写**，不层层打补丁——引用链与补丁摞补丁正是 ADR 越养越难读的来源

- **认证体系约定**：**邮箱为登录主标识**（用户名仅展示用、可重复；注册三步不发 token，见 ADR-0009）。**access token 只驻内存不落盘**（lib\_common 的 `TokenHolder`），由 `AuthInterceptor` 附加到请求头，且**只对白名单内 host 附加**——第三方站点永远拿不到用户 token；冷启动为空，由首个请求的 A0230 经静默刷新补上。双 token 持久化由 `AndroidUserSessionManager` 负责（**密码不落盘**），会话生命周期各时点同步 `TokenHolder`。**A0230 过期由网络层收口**：单飞静默刷新 → 成功重放一次原请求；刷新失败发会话过期事件全局处置（清会话 + 提示 + 跳登录，见 ADR-0010），调用方对会话过期只静默记日志、不重复提示。**不同信任域各用各的客户端**：书源请求（第三方网站）走 `@Named("source")` 纯净客户端、版本检查走 `@Named("release")`，均**不得携带 token**；服务端载荷为蛇形命名，DTO 边界翻译用逐字段 `@SerialName`，不开全局命名策略。ebook-server 基址经 `local.properties` 的 `ebook.server.host` 注入 BuildConfig，不硬编码。**本地调试地址**：Android 17（targetSdk 37）起 `10.0.0.0/8`、`192.168.0.0/16` 等算「本地网络」，访问需 `ACCESS_LOCAL_NETWORK` 运行时权限，未授权时 OkHttp 直接报 `sendto failed: EPERM`（症状是「页面不闪退、数据永远加载不出来」）；本项目不申请该权限（普通用户用不到，Play 需额外论证），真机/模拟器连本机后端一律用 `adb reverse tcp:9090 tcp:9090` + `ebook.server.host=127.0.0.1`（回环不属本地网络），或直接用公网 IP。**用户会话有三处镜像**（① `user_session` SP 文件；② `spUtils` 的 `SP_IS_LOGIN` 等兼容键；③ `ProfileRepository` 的内存 StateFlow，装着昵称/头像供「我的」页渲染），**清会话一律只调 `userSessionManager.clearSession()`**，三处由它内部一并覆盖；**禁止调用方自行「成对」补调 `ProfileRepository` 的清理方法**——旧约定是 SP 镜像尚未收进 `clearSession()` 时的写法，如今既多余、又会掩盖真正容易漏的内存镜像（表现：会话已过期、token 已清，但「我的」页仍显示上一个身份的昵称与头像）

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

## 测试约定

- 单元测试使用 JUnit 4，位于各模块 `src/test/java`；插桩测试位于 `src/androidTest/java`

- 测试类命名 `<Subject>Test`；测试方法使用反引号包裹的句子式描述

- 行为变更时同步更新已有测试，保持测试与实现一致

- 测试覆盖待办见 `docs/test-coverage-todo.md`

## 领域文档

- **领域术语表**：`CONTEXT.md`（纯术语，无实现细节）。涉及领域概念时先查阅，术语冲突时以 CONTEXT.md 为准

- **架构决策记录**：`docs/adr/`。重大决策（难回退 / 无上下文令人惊讶 / 有真实权衡）必须记录

- 本项目为单上下文仓库，无 CONTEXT-MAP.md

## Agent 实战建议

- 涉及书源改动时，先读 assets `default_sources.json` 获取**默认内置书源**实况（按 JSON 规则动态解析），适配新网站优先改 JSON 规则而不是硬编码选择器。**现状是单书源**（多书源为 ADR-0016 规划、尚未实现），各解析路径统一走 `bookSourceManager.requireParser()`，实现该 ADR 前勿按多书源现状描述本模块

- 涉及清单权限改动时，先读 ADR-0022（逐条判据与保留项理由）：删一条的前提是**全仓（含依赖的 lib\_common）找不到需要它的 API**，不是"看着没用"；两份 Manifest 的权限条目必须同步增删，改完必须读 `module_app/build/intermediates/merged_manifests/*/AndroidManifest.xml` 核对**合并结果**（库可能把自己声明的权限加回来，必要时才用 `tools:node="remove"` 覆盖）。存储三项（`MANAGE`/`WRITE`/`READ_EXTERNAL_STORAGE`）与 `FOREGROUND_SERVICE*` 不得顺手删；拍照/相册/导入/下载通知四条回归属人工装机验证项

- 涉及版本更新检查改动时，先读 ADR-0021：发布源顺序/failover/`.apk` 过滤等**策略**在 `module_me` 的 `ReleaseRepository`，HTTP 与接缝在 `lib_ebook_api/service/release`，**不得**把仓库层写回 `lib_ebook_api`；该请求走专属 `@Named("release")` 纯净客户端（不带 token、不套解 `RespDTO` 信封的 `CoroutineAdapter`）。两条不变量：**判不出结论就不算检查成功**（远端 tag 解析不出版本、本地版本号读不到 → 按检查失败处置且不写限频时间戳）；**「是否有新版」是派生态**（由上次检查到的 tag 与装机版本现场比较，不落地成布尔量）

- 涉及前台服务/离线下载改动时，先读 ADR-0018（要点已收进上面「构建约定 → 前台服务约定」）

- 涉及 Room 实体操作，注意主键策略：自然键（`note_url`/`content_ref`——`content_ref` 是内容定位符：本地书存章文件相对路径、网络书存章节 URL）与自增键并存，upsert 用 `existing?.id ?: 0L`（见 ADR-0003）

- **改实体必须接迁移链**：version +1、在链上追加紧邻的 `MIGRATION_n_n+1`（不跳版、不删旧迁移）、提交 Room 生成的新 schema JSON；禁止启用 `fallbackToDestructiveMigration`（ADR-0003「Schema 演进」）

- 涉及正文分页跟进（多页拼接）时：判定基准是**目录页给出的原始章节 URL**（不对入口剥后缀），只对「下一页」候选链接剥一次分页后缀再比。对入口也剥离会让「章节号写在连字符后」的站点剥后同形而**串章**（一路跟进后续章节直到页数上限，正文错乱 + 数十次冗余请求）；「第 1 页也带后缀」的站点与此结构同形、无法靠 URL 区分，取舍是**宁漏页不串章**，真要支持需在书源规则里声明分页模板。边界形态已由 `ChapterPageMatcherTest` 锁死

- 涉及列表分页（分类页/搜索页 URL 模板）时：模板**必须带 `{{page}}`**，否则「加载更多」每页都在请求同一个首页（内置书源曾如此）；页码换算与渲染统一走 `JsoupBookParser` 的 `ListPageUrl`（它把以 `/{{page}}` 结尾的模板在首页裁掉页码段——裸路径首页 `/xuanhuan/1` 是 404），故取首页的调用也必须经它，不要自己 `replace("{{page}}", "1")`。判「到底」不能只看空页：**越界页会以 HTTP 200 重复返回首页书目**（软 404），因此追加页一律按 `noteUrl` 去重、无新条目即置 `hasMore=false`——列表页以 `noteUrl` 作 item key，重复条目直接抛异常。形态由 `ListPageUrlTest` 与 `BookPageMergeTest` 锁死

- 涉及本地书籍导入或章节正文读取时，先读 `docs/superpowers/specs/2026-09-04-local-book-import-design.md`。**导入判重（见 ADR-0023）**：口径是待导入文件的 `comment_key` 等于书架某条目的**当前主键**，不是比 `book_info.name` 书名——键含作者，只比书名会把同名不同作者的两本书判成一本并给出删除入口。处置四个动作（继续添加/智能合并/覆盖/跳过），非破坏的「继续添加」占主按钮位；顺序一律**先导入新条目、后处置旧条目**，覆盖删旧之前先 `absorbGroupKeys` 吸收旧条目的关联键。补章只对本地目标书，且要求旧书归一化章名序列是新书的**前缀**，分叉即整笔放弃；新索引取现有最大 `durChapterIndex + 1`，不用 `size`（历史删章留下的洞会让二者不等，从而覆写既有章文件）

- 修改 Compose 页面时遵循 Material Theme 语义色（`MaterialTheme.colorScheme`），禁止硬编码颜色（阅读界面背景主题除外）

- 跨模块共享的 Compose 组件（卡片/列表项/标签/封面等）统一归口 `lib_book_common` 的 `com.ebook.common.ui`（`CommonUiTokens` 设计常量 + `CommonCard`/`CommonListItem`/`InfoChip`/`BookCover` 等），字号走 Material typography，不要在模块内新建重复实现（见 ADR-0006）

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

- **共享/基础库**：`lib_book_common` / `lib_ebook_api` / `lib_ebook_db`

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

- **首次 clone 后**执行 `bash scripts/install-hooks.sh`，把 `commit-msg` 校验钩子装进 `.git/hooks/`（钩子实现在 `scripts/commit-msg`，校验本文上述 Conventional Commits 格式）；未安装时提交信息格式不受本地强制校验

- 运行 `./gradlew test`，并对涉及模块执行 `./gradlew :module:assembleDebug`

- **"能编译、能安装、能打开页面"三步缺一不可，但分工明确**：Agent 止于第一步（编译与静态检查），第二、三步（安装运行、打开页面确认）由人工在提交前完成。涉及 UI/启动链路（Activity、Compose 页面、drawable 资源）的改动一律适用此分工

- **构建通过不等于可运行**：`painterResource` 加载 NinePatch（.9.png）、selector 等资源类型只会在运行时抛异常（`ResourceResolutionException`），启动不闪退与页面正常渲染也只能在设备或模拟器上确认（`adb logcat -b crash` 无 FATAL EXCEPTION）。因此 Agent 未做装机验证时**必须显式交代**：留给人工的验证项是什么——打开哪个页面、走哪条路径、看什么现象，并标注该步骤未验证。禁止以"构建通过"暗示改动已验证
