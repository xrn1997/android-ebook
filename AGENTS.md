# 仓库指南（Repository Guidelines）

本文件是本仓库统一的 Agent 协作与贡献指南，供 Codex、Claude Code 等 Agent 与人工贡献者共用。

## 项目概述

安卓小说阅读器，100% Kotlin 开发，MVVM 架构，多模块 Gradle 项目。界面已全部迁移到 Jetpack Compose（含阅读器，见 ADR-0001），ViewBinding 与 XML 布局已移除。RxJava3 已完全移除（无依赖、无引用），异步统一使用 Kotlin Coroutines + Flow。

本项目处于**开发阶段**，lib\_common（android-practice）仍在迭代，通过**迷你独立构建**（`lib-common-build/`，只引入 lib\_common 一个模块）高效联动（临时方案，避免频繁上传 Maven），lib\_common 稳定后改回 Maven 依赖（`io.github.xrn1997:common`）。

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
module_me         → 个人中心、头像、评论管理、版本更新检查（发布源策略与更新状态槽，见 ADR-0021）
module_login      → 登录/注册/密码（Compose UI，Coroutines）
lib_book_common   → 项目专属共享件：ebook 域共享 UI（com.ebook.common.ui，见 ADR-0006）与 Provider 接口；通用工具类与基类归口依赖的 lib_common（分界判据见 ADR-0015）
lib_ebook_api     → 网络层：Retrofit 服务、数据实体、OkHttp 拦截器
lib_ebook_db      → Room 数据库实体和 DAO（见 ADR-0003）
build-logic/      → 自定义 Gradle 约定插件（统一构建配置）
```

### 核心架构模式

> 本节只记**稳定约束**；接线细节（具体调用方式、基类钩子、内部机制）以相关代码与 KDoc 为事实源，改动时不要求同步本文件。

- **约定插件**：`build-logic/convention/` 提供统一构建配置，插件 ID 以 `xrn1997.` 为前缀；其中 `xrn1997.android.component` 支撑模块化开发（`gradle.properties` 的 `isModule=true` 时功能模块可独立运行，`false`（默认）时作为 library 被 `module_app` 依赖）。**提交态 `gradle.properties` 必须是 `isModule=false`**——`true` 时 `module_app` 不依赖任何功能模块，产出的是空壳 App。临时单模块独立运行：**直接把 `gradle.properties` 的 `isModule` 改成 `true`，调试完改回，不要提交**。**不要用 `./gradlew -PisModule=true` 覆盖**——命令行 `-P` 会渗进 `settings.gradle.kts` 的 `includeBuild(lib-common-build)`，让 `lib_common` 也按独立模块去套 `com.android.application`，与它的 library 插件冲突而构建失败（`'com.android.application' and 'com.android.library' plugins cannot be applied in the same project`）；根因是 `xrn1997.android.compose` 约定插件自带 `isModule` 分支、会重复应用基础插件（`lib_common` 已自行应用 `xrn1997.android.library`），待修项见 `docs/test-coverage-todo.md`。**该坑以 `includeBuild("lib-common-build")` 为启用态为前提**——当前它是注释态、lib\_common 走 Maven 中央坐标，故 `-PisModule=true` 暂不触发，取消注释恢复本地联动后即复现（两仓 compose 插件 ID 统一与其实现差异见 ADR-0020）。

- **功能模块**依赖 `lib_book_common`，互不依赖；跨模块导航使用 TheRouter，服务经 `provider/` 接口暴露。

- **跨模块页面**：主 Tab 页面（书架/书城/我的）由 Provider 接口暴露 `@Composable () -> Unit`（非 Fragment），由 module\_main 的 NavHost 直接组合；Provider 由 TheRouter 创建（非 Hilt），页面依赖经页面级 `@HiltViewModel` 注入。

- **MVVM**：ViewModel 继承 lib\_common 的 `BaseViewModel`/`BaseRefreshViewModel`，经 Hilt 构造注入。lib\_common 经 `lib-common-build/` 迷你独立构建联动（根 `settings.gradle.kts` 的 `includeBuild` 当前为**注释态**，lib\_common 走 `io.github.xrn1997:common` 中央坐标；需要本地源码联动时取消该注释即启用，两边可同步改）；`lib-common-build/settings.gradle.kts` 用**相对路径** `../../../CodeUp/android-practice` 定位 android-practice 源码，开发者需把 android-practice 克隆到相对本仓库匹配的位置（不绑定盘符/绝对路径，事实源以该 settings 脚本为准）。该切换是双向的：本地改完 common 就注释掉 `includeBuild` 回到 Maven 坐标（当前即此态，已随 common 0.3.1 做过一次），要联调再取消注释

- **Activity 基类**：Compose 业务页面统一继承 lib\_common 的 `BaseActivity`（Compose 版）；例外场景（启动转场、模块独立运行的 test/debug 宿主）不继承基类的，**必须自行对齐基类行为**（主题、insets/沉浸式状态栏、状态覆盖层），以基类 KDoc 与现有宿主实现为准，禁止裸 `MaterialTheme` 造成配色分裂。

### Mock 数据源与独立开发

项目通过 product flavor 和 source set 两层机制实现 mock 数据源切换，无需后端服务器即可开发调试。

**集成构建（`isModule=false`）**：`module_app` 定义 `network` flavor dimension，含 `real` 和 `mock` 两个 flavor。

- `./gradlew :module_app:assembleRealDebug` — 连接真实后端

- `./gradlew :module_app:assembleMockDebug` — 使用内存 mock 数据源（`UserNetworkTest`、`CommentNetworkTest`），applicationId 为 `com.ebook.mock`

- mock flavor 的 `NetworkModule` 位于 `module_app/src/mock/`，real 位于 `module_app/src/real/`

**独立模块（`isModule=true`）**：各功能模块的 `src/main/test/debug/` 下放置 `MockNetworkModule`，当 `isModule=true` 时自动参与编译（source set 优先级高于 `main/`），绑定 `UserNetworkTest` 和 `CommentNetworkTest`。独立运行时默认使用 mock，无需配置。

**两份 Manifest 是替换关系**：`isModule=true` 时约定插件把 `sourceSets.main.manifest` 整体改写到 `src/main/module/AndroidManifest.xml`，`src/main/AndroidManifest.xml` **不参与合并**——独立模式只生效前者、集成模式只生效后者。Activity 声明与其属性（`launchMode`/`theme`/`label`）两处必须同步修改，否则两种模式行为不一致（实例：`LoginActivity` 的 `singleTask` 只写在集成清单里，独立模式下注册页不被清顶，登录成功后回退又露出注册页）。

**跨模块路由在独立模式下的占位**：业务代码跳往其他模块的路由（如 `KeyCode.Main.MAIN_PATH`）在独立模块里不存在，TheRouter 找不到路由**只记一行日志、不报错不闪退**，跳转静默丢失。需要该链路的模块在 `src/main/test/debug/` 宿主上以 `@Route` 挂同名路径占位（该 source set 只在独立模式编译，不与集成模式抢路由），例：module\_login 的 `debug.MainActivity` 占 `MAIN_PATH` 供登录成功后 CLEAR\_TOP。另注：新增/改动 `@Route` 后，routeMap 资产由 TheRouter transform 回写，**当次构建的 APK 仍装旧路由表，需再构建一次**才生效。

**新增接口同步**：新增 `DataSource` 接口方法时，必须同步更新对应的 `XxxNetworkTest` mock 实现。JSON 资产只适用于**返回固定结构的读接口**——这类方法同时要在 `lib_ebook_api/src/main/assets/` 补对应资产。**回显请求内容的写接口与文件上传接口**（如 `updateMe` 的部分更新回显、`uploadAvatar` 返回上传后的 URL）应在 mock 里以代码合成响应，并在实现上注释说明「为何无静态资产可对应」：静态资产表达不了「按入参变化」与「上传后真实地址」这类语义，禁止为凑规则造一份固定 JSON 去冒充响应。例外：`ReleaseDataSource` 的 mock（`ReleaseNetworkTest`）**忽略 endpoint 入参**、固定回一份资产，因此双源 failover 不在 mock 里验，由 `module_me` 的 `ReleaseRepositoryTest` 用假数据源锁住。

**资产形态与解码类型同步**：mock 读资产用的是 `getDataFromJsonFile<T>` 的 reified 类型，它必须与资产的 `data` 实际形态一致（服务端把列表改成分页包裹时，`T` 要从 `List<X>` 换成包裹对象，只取 `.data?.items`）。错配抛的 `SerializationException` 会被 `CoroutineAdapter` 吞成「未知错误」，**页面不闪退、数据永远加载不出来**，只有一行看不出根因的 ERROR 日志——因此这类改动必须同步更新 `lib_ebook_api` 的 mock 资产契约测试（`CommentNetworkTestTest` 一类）。**第三方平台的原始 JSON 资产**（`release_latest.json`）不带 `RespDTO` 信封，故不走 `getDataFromJsonFile<T>`，而是把整个资产直接解成实体；形态错配同样不闪退，表现为「永远检查更新失败」（见 ADR-0021）。

### 响应式编程约定

- 统一使用 Kotlin Coroutines + Flow（`viewModelScope.launch`、`suspend` 函数）；RxJava3 依赖与代码引用已全部移除，**禁止重新引入**

- **事件总线**：SharedFlow（`BookRepository.bookShelfEvents`，见 ADR-0004），已替代 RxBus

- **遗留命名**：`initBaseViewObservable()` 是 lib\_common 基类的历史钩子名（实现均为协程），与 RxJava 无关，新代码不要望文生义

## 技术栈（关键版本）

- Kotlin 2.4.10、Gradle 9.4.1、AGP 9.2.1

- compileSdk/targetSdk 37、minSdk 26、JDK 17（字节码目标；构建守护进程 JVM 由 `gradle/gradle-daemon-jvm.properties` 自动拉取，无需本地预装）

- Hilt 2.60.1、KSP 2.3.10、Dagger 2.60.1、TheRouter 1.4.0-rc1

- Retrofit 3.0.0 + OkHttp 5.3.0

- Room 3.0.0（数据库，群组迁移为 `androidx.room3`）、Compose BOM 2026.06.00

- 需要 Android Studio >= 2025.1.3

## 构建约定

- 依赖版本仅通过版本目录管理（`gradle/libs.versions.toml`），构建脚本中引用 `libs.xxx.yyy`，不硬编码版本号。**已知结构性豁免**：`settings.gradle.kts` 的 `plugins {}` 块中 foojay resolver 插件（`org.gradle.toolchains.foojay-resolver-convention`）版本 `1.0.0` 为字面值——settings 的 `plugins {}` 块读不到版本目录，无法写成 `libs.` 引用，不是漏改

- **AGP 9 内置 Kotlin（built-in Kotlin）**：Android 模块不再应用 `org.jetbrains.kotlin.android`（约定插件 `xrn1997.android.*` 不包含 KGP，Kotlin 支持由 AGP 提供）；顶层 `kotlin { compilerOptions {} }` 块仍可用（AGP 注册了 `KotlinAndroidProjectExtension`）。`gradle.properties` 不设置 `builtInKotlin`/`newDsl` 开关（默认内置 + 新 DSL）。lib-common-build 的 lib\_common 同规则（约定插件由 android-ebook 的 build-logic 提供）

- **AGP 9 源码目录注意**：给 Kotlin 编译添加源码目录必须用 `sourceSets.main.kotlin.srcDirs(...)`；`java.srcDirs(...)` 不再被 Kotlin 编译拾取（但 KSP 仍可见，会造成"KSP 生成了代码、Kotlin 没编译"的错位）

- **不使用 DataBinding**：已移除，使用 ViewBinding 或 Compose

- **图片加载**：Coil（Compose）。Glide 已随 View 体系一并移除（依赖与 `MyAppGlideModule`/`MyGlideExtension` 均已删除，代码零引用），不要再引入

- **日志统一走** **`com.xrn1997.common.util.Logger`**（lib\_common 提供，级别控制、debug/release 自动裁剪），禁止直接调用 `android.util.Log`

- **注释要求**：每个类、每个方法、每个重要逻辑分支都必须有足够的 KDoc/注释，说明"是什么、为什么"——包括设计决策、竞态条件、跨线程可见性等审查时需要知道的背景

- **文档同步**：代码、注释与文档（本文件 / CONTEXT.md / docs/adr/）必须保持一致——任何改动在提交前同步更新相关注释与文档，禁止留下"代码已改、注释/文档仍是旧描述"的状态

- **评审/grill 驱动改动的沉淀**：由代码评审、grill 会话等驱动的架构级决定（依赖替换、体系迁移、组件归属等）必须沉淀为 `docs/adr/` 的 ADR，不能只留在提交信息与会话记录里

- **跨仓库 ADR 编号**：android-ebook、android-practice（lib\_common）与 ebook-server 是三个独立项目，各有**连续递增、互不对齐**的 `docs/adr/` 编号序列（三仓都从 0001 起编，序列在 0001 段即已重叠）。**代码注释与文档中禁止引用其他仓库的 ADR 编号**（`lib_common ADR-xxxx` / `ebook-server ADR-xxxx` 一律不写）——跨仓编号对只看本仓的读者无意义、且随对侧重排失效；需要表达的外部决策把内容写成自足描述（不怕重复，如「服务端契约：邮箱为登录主标识」）。本仓自己的 ADR 编号裸写即可；`docs/adr/` 内做跨仓溯源时用提交 hash 等本仓可验证的锚点，不引外部编号

- **认证体系约定**：**邮箱为登录主标识**（用户名仅展示用，可重复；注册三步不发 token，见 ADR-0009）；access token 运行时存放于 lib\_common 的 `TokenHolder`（内存单例），`AuthInterceptor` 只对 `@AuthAllowedHosts` 白名单内的 host 附加到请求头（白名单绑定在 lib\_ebook\_api 的 NetworkModule，值为 `BuildConfig.EBOOK_SERVER_HOST`；`AuthInterceptor`/`@AuthAllowedHosts` 属 lib\_common 侧约定）；双 token 持久化由 `AndroidUserSessionManager` 负责（密码不落盘），登录/登出/启动恢复/静默刷新时同步 TokenHolder。A0230 过期由 `CoroutineAdapter` 收口：单飞静默刷新（`TokenRefresher` 接缝，实现在 lib\_book\_common）→ 成功重放一次；刷新失败发 `SessionEventBus` 会话过期事件，由 module\_main `MainActivity` 订阅处置（清会话 + 提示 + 跳登录页，见 ADR-0010）。ebook-server 基址经 `local.properties` 的 `ebook.server.host` 注入 BuildConfig（缺省 10.0.2.2），不硬编码。**本地调试地址约定**：Android 17（targetSdk 37）起 `10.0.0.0/8`、`192.168.0.0/16`、`169.254.0.0/16` 等算“本地网络”，访问需 `ACCESS_LOCAL_NETWORK` 运行时权限，未授权时 OkHttp 直接报 `sendto failed: EPERM`（症状是“页面不闪退、数据永远加载不出来”）；本项目不申请该权限（普通用户用不到，Play 需额外论证），真机/模拟器连本机后端一律用 `adb reverse tcp:9090 tcp:9090` + `ebook.server.host=127.0.0.1`（回环不属本地网络），或直接用公网 IP。**书源请求（第三方网站）必须使用** **`@Named("source")`** **纯净客户端，不得携带 token**；服务端载荷为蛇形命名，DTO 边界翻译用逐字段 `@SerialName`，不开全局命名策略。**用户会话有三处镜像**（① `user_session` SP 文件；② `spUtils` 的 `SP_IS_LOGIN` 等，供 `LoginInterceptor` 读；③ `ProfileRepository` 的内存 StateFlow，装着昵称/头像供「我的」页渲染），**清会话一律只调 `userSessionManager.clearSession()`**，三处由它内部一并覆盖。**禁止调用方自行「成对」补调 `ProfileRepository` 的清理方法**：旧约定「两个方法必须成对调」是 SP 镜像（②）尚未收进 `clearSession()` 时的写法，如今既已多余、又会掩盖真正容易漏的③——漏掉③的表现是会话已过期、token 已清，但「我的」页仍显示上一个身份的昵称与头像。`ProfileRepository` 侧的清理方法现为 internal 的 `resetProfileState()`，只是 `clearSession()` 的内部实现细节，不作外部入口（见 CONTEXT.md「用户会话」）

- **前台服务约定（离线下载，见 ADR-0018）**：`DownloadService` 声明为 `foregroundServiceType="dataSync"`，两参 `startForeground(id, notification)` 合法且足够（其内部传 `ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST`，语义是取 manifest 声明的类型；`MissingForegroundServiceTypeException` **只在 manifest 未声明类型时**抛）——不存在“必须改成三参调用”的问题，不要按这类评审断言改动。targetSdk 35+ 的真实约束是两条：dataSync 前台服务在任意 24 小时内只有 6 小时配额（到点回调 `Service.onTimeout(int, int)`，必须在数秒内 `stopSelf()`，否则被记 `RemoteServiceException`），以及配额用尽/应用后台时启动被拒抛 `*ServiceStartNotAllowedException`。因此：**拉起服务一律走** **`DownloadService.start(context, intent)`**（返回 false 即需提示用户），禁止在页面/ViewModel 里直接调 `ContextCompat.startForegroundService`；**发起下载先入库再拉服务**（`BookReadViewModel.startDownload`），否则启动被拒时只躲在 Intent 里的任务会丢；`onTimeout` 内只做数秒可完成的收尾，不得查库/发网络请求/起协程；**失败章必须在重试耗尽后出队**（`DownloadService.downloading`）——`getNextDownloadTask` 永远取队头，失败任务留在表里会让服务在同一章上无限重试（常驻通知不消失、前台服务不停止、该书后续章节全被队头阻塞），跳过章数经 `skippedCount` 带进收尾文案，不静默丢章；暂停中断重试时**不出队**（任务保留待续跑）

- 不要引入新的编译警告，提交代码应保持警告清洁

## MVVM 架构约定

项目采用严格的 **Model → ViewModel → View** 三层结构，由 `lib_common` 提供基类。

### ViewBinding 体系（lib\_common 的双 UI 栈能力，本仓只用 Compose 分支）

本节描述的是依赖库 `lib_common`（android-practice）仍同时提供的两套 UI 栈之一，**本仓库不使用**：全仓无任何 ViewBinding / XML 布局页面（见开头「项目概述」），勿据本节新建页面，新页面一律走下面的 Compose 体系。双 UI 栈并存是 `lib_common` 作为独立库的既有设计（它要同时服务仍以 View 体系为主的旧下游），本仓只取用其 Compose 分支。保留本节只为说明基类层级事实：

```
BaseActivity<V : ViewBinding>
  └── BaseMvvmActivity<V, VM : BaseViewModel<*>>
        └── BaseMvvmRefreshActivity<V, VM>   (基于 RefreshView)
```

- 抽象方法：`onBindViewBinding()`、`initView()`、`initData()`

- 使用 `binding` 属性操作 View

### Compose 体系

```
BaseActivity (Compose)
  └── BaseMvvmActivity<VM : BaseViewModel<*>>
        └── BaseMvvmRefreshActivity<VM>      (基于 RefreshableList)
```

- 抽象方法：`PageContent()` (Composable)；`initData()` 已有默认空实现，无数据页面无需覆写

- **持有 ViewModel 的页面必须继承** **`BaseMvvmActivity`**（而非裸 `BaseActivity` + 手动 `by viewModels()`）：一次性命令通道（`sendToast`/`sendFinish`/`sendNavigate`）与 loading 覆盖层只由基类里的 `MvvmBinder` 消费，绑不上不报编译错、只会静默失效（命令堆积在 Channel 里随 ViewModel 销毁丢弃）——表现是“页面不弹提示、该关的页不关，返回栈里露出残留中间页”

- 无 ViewBinding，状态通过 Compose `mutableStateOf` 管理

- 统一提供：Toolbar（`enableToolbar()` 开关、`showBackButton()` 返回箭头、`toolbarTitle` 标题）、状态栏 insets 自动处理（`enableFitsSystemWindows()` 开关）、加载/空态/网络错误覆盖层

- 全局主题由基类经 lib\_common 的 `AppTheme` 装配点提供（未装配时回落 `MyApplicationTheme`；装配点属 lib\_common 侧约定），子类不在 `PageContent` 中重复包裹 MaterialTheme（**唯一例外**：阅读器整片豁免系统深色，在 `ReadBookActivity.PageContent` 内做作用域固定浅色，见 ADR-0012；其余场景一律禁止）

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
    // override fun initView() { ... }     // ViewBinding 体系
}
```

- **无 Model 门面的 ViewModel 用 `NoOpModel` 占位**：纯展示页（依赖直接注入多个仓库、无一次性命令需求）仍须继承 `BaseViewModel`，Model 位传 lib\_common 的 `NoOpModel()`（实例：`MePageViewModel`），**不得直继 `androidx.lifecycle.ViewModel`**——否则全仓 VM 基类约定出现例外，后续接 `BaseMvvmActivity`/命令通道时要连带改页面。子类状态流命名避开基类的 `uiState`（覆盖层专用），沿用 `meState`/`detailState`/`cacheState` 这类「页面名 + State」

- **用户会话清理相关页面**：清会话一律只调 `userSessionManager.clearSession()`（单点收口，见上面「认证体系约定」的三处镜像），不要单独去清 `ProfileRepository`，也不要自行补调 `ProfileRepository` 的清理方法（内部实现是 internal 的 `resetProfileState()`）

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

- 调整模块依赖前，先确认依赖方向符合分层（业务模块 → lib\_book\_common → lib\_ebook\_api → lib\_ebook\_db）

- 涉及认证/网络改动时，先确认 token 流向：认证请求走共享 OkHttpClient（带 AuthInterceptor），书源请求走 `@Named("source")` 纯净客户端

- 涉及书源改动时，先读 `default_sources.json` 获取**默认内置书源**实况（`JsoupBookParser` 按规则动态解析），适配新网站优先改 JSON 规则而不是硬编码选择器；当前书源来自随 APK 出货的 assets `default_sources.json`，运行时由 `BookSourceManagerImpl` 加载（用户导入书源的 Room 持久化尚未实现，属 ADR-0016 规划）

- **书源架构现状：单书源**（多书源共存是 ADR-0016 规划的**未来项，尚未实现**）：书架/阅读/下载/搜索/书城各解析路径当前统一走 `bookSourceManager.requireParser()`（默认书源 parser），`requireParser()` **未废弃、仍是主路径**。ADR-0016 规划的按 `tag` 查 parser（`getParserFor`）、聚合搜索（`searchAcross`）、默认书源订阅（`observeDefaultSource`）、`BookSourceNotFoundException`、Room `book_source` 表等**当前代码中均不存在**；`BookSourceManager` 的 `importFromJson`/`exportToJson`/`switchSource`/`saveCurrentSource` 等是为该规划预留的脚手架，暂无业务调用方。实现 ADR-0016 前不要按多书源现状描述本模块

- 涉及清单权限改动时，先读 ADR-0022（逐条判据与保留项理由）：删一条的前提是**全仓（含依赖的 `lib_common`）找不到需要它的 API**，不是"看着没用"；`src/main/AndroidManifest.xml` 与 `src/main/module/AndroidManifest.xml` 是**替换关系**，权限条目必须两份同步增删，否则集成态与独立态权限面分裂。改完必须读 `module_app/build/intermediates/merged_manifests/*/AndroidManifest.xml` 核对**合并结果**（库可能把自己声明的权限加回来，必要时才用 `tools:node="remove"` 覆盖）。存储三项（`MANAGE`/`WRITE`/`READ_EXTERNAL_STORAGE`）由 `module_book` 的导入本地书链路持有、`FOREGROUND_SERVICE*` 由 ADR-0018 持有，均不得顺手删；拍照/相册/导入/下载通知四条回归属人工装机验证项

- 涉及版本更新检查改动时，先读 ADR-0021：发布源顺序/failover/`.apk` 过滤等**策略**在 `module_me/repository/ReleaseRepository`，HTTP 与接缝（`ReleaseDataSource`/`ReleaseNetwork`/`ReleaseResponse`）在 `lib_ebook_api/service/release`，**不得**把仓库层写回 `lib_ebook_api`；该请求走专属 `@Named("release")` 纯净客户端（不带 token、不套解 `RespDTO` 信封的 `CoroutineAdapter`），也不要改回去复用 `@Named("source")` 书源客户端。两条不变量：**判不出结论就不算检查成功**（远端 tag 解析不出版本、本地 `versionName` 读不到 → 按检查失败处置且不写限频时间戳）；**「是否有新版」是派生态**（由上次检查到的 tag vs 装机版本算，不落地成布尔量）

- 涉及前台服务/离线下载改动时，先读 ADR-0018（dataSync 配额与启动被拒的收口方式）：新增启动点一律走 `DownloadService.start`，发起方先写 `download_chapter` 再拉服务；`foregroundServiceType="dataSync"` 与 `FOREGROUND_SERVICE_DATA_SYNC` 不得删除，两参 `startForeground` 无需改成三参

- 涉及 Room 实体操作，注意主键策略：自然键（note\_url/dur\_chapter\_url）与自增键并存，upsert 用 `existing?.id ?: 0L`（见 ADR-0003）

- **改实体必须接迁移链**：当前 `AppDatabase` 为 version = 2（`download_chapter.force_refresh`，`DatabaseModule.MIGRATION_1_2`）。再改动实体要同时做三件事——version +1、在链上追加紧邻的 `MIGRATION_n_n+1`（不跳版、不删旧迁移）、提交 Room 生成的新 schema JSON；禁止启用 `fallbackToDestructiveMigration`（ADR-0003「Schema 演进」）

- 涉及正文分页跟进（`JsoupBookParser.getBookContent` / `ChapterPageMatcher`）时：判定基准是**目录页给出的原始章节 URL**（不对入口剥后缀），只对「下一页」候选链接剥一次分页后缀再比（扩展名形态不一致时再去扩展名兜底比一次）。对入口也剥离会让「章节号写在连字符后」的站点（`/1234-15.html` 与 `/1234-16.html`）剥后同形而串章（一路跟进后续章节直到页数上限，正文错乱 + 数十次冗余请求）。「第 1 页也带后缀」的站点与此结构同形、无法靠 URL 区分，取舍是**宁漏页不串章**，真要支持需在书源规则里声明分页模板（属 ADR-0016）；边界形态已由 `ChapterPageMatcherTest` 锁死

- 涉及列表分页（分类页 `ruleFind.url` / 搜索页 `searchUrl`）时：模板**必须带 `{{page}}`**，否则「加载更多」每页都在请求同一个首页（内置书源曾如此）；页码换算与渲染统一走 `JsoupBookParser` 的 `ListPageUrl`，它把以 `/{{page}}` 结尾的模板在**首页裁掉页码段**（笔趣阁式站点首页是裸路径 `/xuanhuan`、`/so/关键词`，`/xuanhuan/1` 与 `/xuanhuan/` 都是 404），故 `getLibraryData` 等取首页的调用也必须经它，不要自己 `replace("{{page}}", "1")`。判「到底」不能只看空页：**越界页会以 HTTP 200 重复返回首页书目**（软 404），因此追加页一律走 `mergeBookPage` 按 `noteUrl` 去重、无新条目即置 `hasMore=false`——列表页的 `LazyColumn` 以 `noteUrl` 作 item key，重复条目直接抛异常。形态由 `ListPageUrlTest` 与 `BookPageMergeTest` 锁死

- 跨模块导航使用 TheRouter，不直接依赖其他模块

- 修改 Compose 页面时遵循 Material Theme 语义色（`MaterialTheme.colorScheme`），禁止硬编码颜色（阅读界面背景主题除外）

- 跨模块共享的 Compose 组件（卡片/列表项/标签/封面等）统一归口 `lib_book_common` 的 `com.ebook.common.ui`（`CommonUiTokens` 设计常量 + `CommonCard`/`CommonListItem`/`InfoChip`/`BookCover` 等），字号走 Material typography，不要在模块内新建重复实现（见 ADR-0006）

- 新增依赖必须走版本目录（`gradle/libs.versions.toml`），不要硬编码版本

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

