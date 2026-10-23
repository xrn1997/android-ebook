# 架构事实源

> 本文件描述**当前**的模块结构、核心接缝与领域不变量。为什么这样选、放弃了什么见 [`docs/adr/`](adr/)；构建、运行、测试见 [`docs/development.md`](development.md)；术语见 [`CONTEXT.md`](../CONTEXT.md)。
>
> 只写现在时。历史与变更过程不写在这里；需要历史时读 ADR、CHANGELOG 或 git。接线细节（具体调用方式、基类钩子、内部机制）以相关代码与 KDoc 为事实源；本文件只锁稳定结构与不变量。

## 1. 模块与依赖方向

依赖方向：**业务模块 → lib_book_common → lib_book_source → lib_ebook_api / lib_ebook_db**。

- `lib_book_common` 与 `lib_book_source` 都对 `lib_ebook_api`、`lib_ebook_db` 并列 `api` 依赖；`lib_ebook_api → lib_ebook_db` 这条边不存在。
- `lib_book_source` 是书源解析专用层：原生解析器 + 脚本书源解释器（规则词法/求值/取文）+ `:js` 沙箱执行器（vendored QuickJS + JNI 桥）。
- `lib_ebook_db` 是基础库，无交叉依赖。

```
module_app        → 应用入口（@HiltAndroidApp），组装所有功能模块
module_main       → 主页、启动页
module_book       → 书籍阅读、管理、评论（含阅读器，全部 Compose）
module_find       → 书城、搜索、书库浏览
module_me         → 个人中心、头像、评论管理、版本更新检查
module_login      → 登录/注册/密码（Compose UI，Coroutines）
lib_book_common   → 项目专属共享件：ebook 域共享 UI（com.ebook.common.ui）与 Provider 接口；
                    通用工具类与基类归口依赖的 lib_common
lib_book_source   → 书源解析专用层：原生解析器、脚本书源解释器、`:js` 沙箱执行器
lib_ebook_api     → 网络层：Retrofit 服务、数据实体、OkHttp 拦截器
lib_ebook_db      → Room 数据库实体和 DAO
build-logic/      → 自定义 Gradle 约定插件（统一构建配置）
```

`lib_common`（android-practice）是仓库外的共享基类库。依赖方式以根 `settings.gradle.kts` 为事实源：默认走 Maven 中央坐标；本地源码联调时启用 `includeBuild("lib-common-build")` 迷你独立构建（`lib-common-build/settings.gradle.kts` 以相对路径定位 android-practice 源码）。联动态不提交；本地改完 common 即切回 Maven 坐标。

## 2. 模块化与跨模块协作

- **约定插件**：`build-logic/convention/` 提供统一构建配置，插件 ID 以 `xrn1997.` 为前缀。`xrn1997.android.component` 支撑模块化开发：`gradle.properties` 的 `isModule=true` 时功能模块可独立运行，`false`（默认）时作为 library 被 `module_app` 依赖。
- **`isModule` 三条铁律**：提交态必须是 `false`（`true` 时 `module_app` 不依赖任何功能模块，产出空壳 App）；临时单模块独立运行直接改 `gradle.properties`，调试完改回、不提交；不要用 `./gradlew -PisModule=true` 覆盖——命令行 `-P` 会渗进 `settings.gradle.kts` 的 `includeBuild`，让 lib_common 也被套上 application 插件、与它的 library 插件冲突而构建失败。
- **功能模块**依赖 `lib_book_common`，互不依赖；跨模块导航使用 TheRouter，服务经 `provider/` 接口暴露。
- **跨模块页面**：主 Tab 页面（书架/书城/我的）由 Provider 接口暴露 `@Composable () -> Unit`（非 Fragment），由 module_main 的 NavHost 直接组合；Provider 由 TheRouter 创建（非 Hilt），页面依赖经页面级 `@HiltViewModel` 注入。页面需要宿主提供的能力时，用**可空参数向下传**（宿主知道 Tab 路由、模块内不知道，独立运行态传 null 即降级不渲染），实例是 `IBookProvider.mainBookPage` 的 `onGoBookstore`；不要用 `CompositionLocal` 或另开 TheRouter 路由表达「切 Tab」。
- **MVVM**：ViewModel 继承 lib_common 的 `BaseViewModel`/`BaseRefreshViewModel`，经 Hilt 构造注入。
- **响应式编程**：统一 Kotlin Coroutines + Flow（`viewModelScope.launch`、`suspend` 函数）；RxJava3 依赖与代码引用已全部移除，**禁止重新引入**。事件总线是 SharedFlow（`BookRepository.bookShelfEvents`，ADR-0004），已替代 RxBus。`initBaseViewObservable()` 是 lib_common 基类的历史钩子名（实现均为协程），与 RxJava 无关，新代码不要望文生义。
- **Activity 基类**：Compose 业务页面统一继承 lib_common 的 `BaseActivity`（Compose 版）；例外场景（启动转场、模块独立运行的 test/debug 宿主）不继承基类的，必须自行对齐基类行为（主题、insets/沉浸式状态栏、状态覆盖层），以基类 KDoc 与现有宿主实现为准，禁止裸 `MaterialTheme` 造成配色分裂。

## 3. Compose 体系

### 3.1 基类与页面结构

lib_common 还同时提供 ViewBinding 一族基类（服务它自己的旧下游），**本仓不使用**——勿据此新建页面，新页面一律走本节 Compose 体系。

```
BaseActivity (Compose)
  └── BaseMvvmActivity<VM : BaseViewModel<*>>
        └── BaseMvvmRefreshActivity<VM>      (基于 RefreshableList)
```

- 抽象方法：`PageContent()` (Composable)；`initData()` 已有默认空实现，无数据页面无需覆写。
- **持有 ViewModel 的页面必须继承 `BaseMvvmActivity`**（而非裸 `BaseActivity` + 手动 `by viewModels()`）：一次性命令通道（`sendToast`/`sendFinish`/`sendNavigate`）与 loading 覆盖层只由基类里的 `MvvmBinder` 消费，绑不上不报编译错、只会静默失效（命令堆积在 Channel 里随 ViewModel 销毁丢弃）——表现是「页面不弹提示、该关的页不关，返回栈里露出残留中间页」。
- 状态通过 Compose `mutableStateOf` 管理。
- 统一提供：Toolbar（`enableToolbar()` 开关、`showBackButton()` 返回箭头、`toolbarTitle` 标题）、状态栏 insets 自动处理（`enableFitsSystemWindows()` 开关）、加载/空态/网络错误覆盖层。

### 3.2 主题与阅读器两层口径

全局主题由基类经 lib_common 的 `AppTheme` 装配点提供，子类**一律**不在 `PageContent` 中重复包裹 MaterialTheme。阅读界面分两层：

- **正文层**用「阅读背景主题」（`ReadBookControl` 四档纸张色），不随深浅色切换；
- **控制器层**（顶/底栏、目录抽屉、亮度/字体/设置面板、弹窗）继承全局主题，随外观主题模式走浅色或深色。

其余场景禁止硬编码颜色；Compose 页面遵循 Material Theme 语义色（`MaterialTheme.colorScheme`）。

### 3.3 Hilt 与 Application

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

- **无 Model 门面的 ViewModel 用 `NoOpModel` 占位**：纯展示页（依赖直接注入多个仓库、无一次性命令需求）仍须继承 `BaseViewModel`，Model 位传 lib_common 的 `NoOpModel()`（实例：`MePageViewModel`），**不得直继 `androidx.lifecycle.ViewModel`**——否则全仓 VM 基类约定出现例外，后续接 `BaseMvvmActivity`/命令通道时要连带改页面。子类状态流命名避开基类的 `uiState`（覆盖层专用），沿用 `meState`/`detailState`/`cacheState` 这类「页面名 + State」。
- **Application 上不许有 eager `@Inject` 字段**：`:js` 沙箱进程里框架仍会实例化 `Application`（`ActivityThread` 在隔离进程只跳过少数几步、不跳过 `makeApplicationInner`），而进程门写在 `super.onCreate()` **之后**、Hilt 的字段注入发生在 `super.onCreate()` **里面**——加一个注入字段等于把门挪到注入之前，沙箱进程起来即崩，且崩的现场藏在 binder 连接超时里。需要重对象就经 `EntryPointAccessors` 在用到那一刻取（`MyApplication` 的 `ContentStoreEntryPoint`、`BookApplication` 的 `ThemeModeManagerEntryPoint` 同法）。进程门本身两处都要有：`BookApplication` 与 `MyApplication`（`SandboxProcess.isInIsolatedProcess`），漏一处就是给一个读不到本应用数据目录的进程装配主题与仓库。

### 3.4 无状态根与预览

`@Preview` 只标**无参** `@Composable`、设计期渲染不经 Hilt，故每个屏幕的 UI 收在「不可变状态 + 回调」的无状态根里，状态收集留在有状态外壳（`XxxPage(viewModel = hiltViewModel())` 或 `PageContent()`）；宿主提供的能力（切 Tab、改窗口亮度、FileProvider uri）按 ADR-0041 的口径作可空回调向下传，预览侧传 no-op。约定**向前生效**：新页面照此写，既有页面在它下次被改动时补齐，不一次性追溯改造。

- 预览函数与组件**同文件就近**（`private` 组件因此无需放开可见性；先例是 `module_find` 的 `SearchActivity`）；外层套 `lib_book_common` 的 `ui/preview/AppPreview`。它 `dynamicColor = false`（图要可比）、`darkTheme` 跟随 `isSystemInDarkTheme()`，于是深浅档一律用官方 `@PreviewLightDark` 翻 `uiMode`；要看壁纸取色用 `@PreviewDynamicColors`。
- 样例数据只从 `ui/preview/PreviewSamples` 取：条目那层「书架行 → `book_info` → 章节」的嵌套少填一层就画成半截，据此判断布局是错的；各模块的渲染冒烟测试吃**同一份**样例。多形态交给官方 `@PreviewParameter` + `PreviewParameterProvider`（书城 `LibrarySourceState` 四档一次出四张图）。
- **可见性允许时**（根为 `internal`/`public`）每个被预览的屏幕根配一个 Robolectric 渲染用例（配方见 `EmptyStateTest`：`@GraphicsMode(NATIVE)` + `@Config(sdk = [34], qualifiers = …)` + `createAndroidComposeRule<ComponentActivity>()`），锁「组合得出来」与「取值顺序对」——这两类错不崩、不报错，只在渲染时显形。`private` 根测试源集看不见，那一档由预览面板人工确认。
- **阅读器的正文排版层不进预览**：翻页/滚屏正文要吃 `ReadBookActivity` 的窗口旗标与排版实测块高，硬凑可预览等于再造一套假排版器。预览范围止于控制器层（顶/底栏、目录抽屉、亮度/字体面板、各类弹层）。

## 4. 数据与存储

### 4.1 Room 主键与写事务

主键策略是两套：

- **自然键表**（`note_url`/`content_ref`）直接 `OnConflictStrategy.REPLACE` 整行替换。`content_ref` 是内容定位符：本地书存章文件相对路径、网络书存章节 URL。
- **自增键的流水表**（下载队列）主键是自增 `id` 另挂唯一索引，upsert 必须先查回旧行、用 `existing?.id ?: 0L` 回填主键才算**原地覆盖**——传 0 是让 SQLite 分配新行，同唯一索引会撞成「先删后插」（实例见 `DownloadChapterDao.getChapterByUrl` 与 `DownloadRepository.addTasks`）。

**写「一个书架条目」的四张表一律收在同一笔写事务里**（由 `ShelfEntryStore` 的 `add`/`replaceRows`/`appendTailAndRemove` 经 `WriteTransactionRunner` 落地；`BookRepository` 的加书架、换源、导入与智能合并都委托它，事件在事务返回后才发）：逐张提交会留出窗口，让并发读方 `getAllBooksWithDetails` 看到「书架行已在、章节还没写完」的半截条目，而它把「取不到 `book_info`」的行当孤立记录**删掉**——半截状态不只被读到，还会被写实。该不变量由 `ShelfEntryStoreTest` 与 `BookRepositoryTest` 的 `addToShelf 把整个条目收进一次写事务并在提交后才发事件` 在纯 JVM 侧锁住。

### 4.2 Schema 演进

改实体必须接迁移链：version +1、在链上追加紧邻的 `MIGRATION_n_n+1`（不跳版、不删旧迁移）、提交 Room 生成的新 schema JSON；禁止启用 `fallbackToDestructiveMigration`。

### 4.3 目录事实源与追更

**目录的单一事实源是 `chapter_list`**：要渲染或写进度一律回读 `BookRepository.getStoredChapters`，不要拿「手上那份目录 + appended」自己拼（`Appended` 只带新增的那几行，够报条数不够拼），基数不保证等于库内行数——差一章的症状是「读至」变空白＋点进去提示章节加载失败、目录还会长出两遍末章。

**只有「本地每一章都还在远端、相对顺序一致」才追加**，分叉一律整笔放弃（章文件按序号命名，序号漂移＝静默读错章）：

- 判定**按 `content_ref` 定位、不按位置逐位比**（本地序号可能有洞＝用户删过章，按位置硬比会让这本书永远判成分叉、再也追不了更）；新索引取 `max(durChapterIndex) + 1`，不用 `size`。
- **远端行自带的序号不可沿用**：那是 parser 按位置写的 `chapters.size`，有洞时直接沿用会让两行撞同一个 index 而不报错（主键是 `content_ref`）。
- **撞主键一律整笔放弃**——`ChapterTocDiff` 会检查算出的 tail：与本地既有 `content_ref` 重合、或 tail 内部自撞即判 `Diverged`，因为整行 REPLACE 撞键不报错、只把既有那一行搬到表尾（序号静默错位），而详情页现在也会落库。
- 追加成功时**不需要失效任何缓存**（既有章的 index 与 contentRef 逐字不变，与 `refreshChapter`／`mergeTailChapters` 的处置不同）。
- **`book_shelf.dur_chapter` 是列表位置**，不是目录的存储形态；`saveProgress` 写库前按行数钳住落点是**第二道**防线，兜今后新长出来的自拼目录（库里零行时它**不出手**——零行没有「最后一行」可钳，提前钳成 0 会把「未加书架先读、中途点加书架」的进度抹回第一章）。
- **落库前先认归属**：比较书架行的 `tag == 本次解析用的 tag`，跨源浏览同一 `noteUrl` 时手上那份是别家的目录。**这道门有两个入口**：详情页在落库前比（不符跳过落库但仍回读库里那份），阅读器在 `ReaderSession.appendIfAtTail` 追更前比（书架行不存在或 tag 不同即静默跳过，不发网络）。
- **追加的判定与落库收在同一写事务里**（`appendRemoteChapters` 内读-判-写不拆开）：事务外读快照、事务内写的话，并发落进的另一批追更会让 diff 基于旧快照，撞 `content_ref` 的整行 REPLACE 把既有行静默搬到表尾。
- 限频落在 `book_info.final_refresh_data`（`isTocCheckDue` + `BookShelfEntity.REFRESH_TIME`）：**得出结论就写时间戳**（`UpToDate`／`Diverged` 都写，分叉重试无意义），失败一律不写（那是暂时性故障）；写它必须走 `BookInfoDao.setFinalRefreshData` 那条定向 UPDATE，读回整行再 `insert` 是整行 REPLACE，漏填字段会把书名封面静默抹掉。
- `Diverged` 不发事件（库没动），`Appended` 发 `BookShelfEvent.ChaptersUpdated`——加该分支会让 4 个穷举 `when` 的消费方编译失败，一并补齐。

## 5. 认证与会话

- **邮箱为登录主标识**（用户名仅展示用、可重复；注册三步不发 token）。
- **access token 只驻内存不落盘**（lib_common 的 `TokenHolder`），由 `AuthInterceptor` 附加到请求头，且**只对白名单内 host 附加**——第三方站点永远拿不到用户 token；冷启动为空，由首个请求的 A0230 经静默刷新补上。双 token 持久化由 `AndroidUserSessionManager` 负责（**密码不落盘**），会话生命周期各时点同步 `TokenHolder`。
- **A0230 过期由网络层收口**：单飞静默刷新 → 成功重放一次原请求；刷新失败发会话过期事件全局处置（清会话 + 提示 + 跳登录）。调用方对会话过期**一律经 `lib_book_common` 的 `reportFailure` 上报**（其内部只记日志、不重复提示），**不要再手写 `isSessionExpiredHandled` 分支**。
- **不同信任域各用各的客户端**：书源请求（第三方网站）走 `@Named("source")` 纯净客户端、版本检查走 `@Named("release")`，均**不得携带 token**；服务端载荷为蛇形命名，DTO 边界翻译用逐字段 `@SerialName`，不开全局命名策略。
- ebook-server 基址经 `local.properties` 的 `ebook.server.host` 注入 BuildConfig，不硬编码。**本地调试地址**：Android 17（targetSdk 37）起 `10.0.0.0/8`、`192.168.0.0/16` 等算「本地网络」，访问需 `ACCESS_LOCAL_NETWORK` 运行时权限；本项目不申请该权限，真机/模拟器连本机后端一律用 `adb reverse tcp:9090 tcp:9090` + `ebook.server.host=127.0.0.1`（回环不属本地网络），或直接用公网 IP。
- **用户会话有三处镜像**（① `user_session` SP 文件；② `spUtils` 的 `SP_IS_LOGIN` 等兼容键；③ `ProfileRepository` 的内存 StateFlow，装着昵称/头像供「我的」页渲染）。**清会话一律只调 `userSessionManager.clearSession()`**，三处由它内部一并覆盖；**禁止调用方自行「成对」补调 `ProfileRepository` 的清理方法**——旧约定是 SP 镜像尚未收进 `clearSession()` 时的写法，如今既多余、又会掩盖真正容易漏的内存镜像。

## 6. 书源与内容获取

### 6.1 清单、默认源与搜索

- 应用**不内置任何书源**，也没有首启 seeding；`book_source` 表是书源清单的**唯一**事实源，表里每一行都由用户导入，任何一条源都可删、可禁用、可设默认。适配新网站优先改用户导入的 JSON 规则，而不是硬编码选择器。
- **多书源共存**：每本书按 `tag` 绑定归属书源，解析一律跟书走——书架/详情/下载取 `entity.tag`、正文取 `BookLocation.sourceUrl`，最终都经 `SourceResolver.resolveSource(url)` 拿源；生产调用点统一走 `parserOrThrow`，四档只在那里翻译一次（正文入口的本地/空白前置短路见 [ADR-0045](adr/0045-source-binding-typed-result.md) 的「边界」）。取源结果是密封的 `SourceBinding` 四档（Local / Found / Missing / Broken）；`getParserFor` 只剩 deprecated 适配，生产路径不得新增调用。`requireParser()` / `currentParser` 已删除且**不得重新引入**：按全局默认源去解另一站点的书不会闪退，只会拉回不相干的内容。
- 书城与「新加的书绑哪个源」都由 `SourceCatalog.observeDefaultSource()` 给出（默认源每次由 `defaultFromRows(Room 行)` 现算，SP 只记「用户上次主动选了哪个」；载体是格式中立的 `SourceDefinition`，脚本行同样可成为默认源；无启用源时发 null，页面进引导态）。**同步读面已无任何出口**，别为了「首帧不空白」重新修一条。
- 搜索用 `SourceResolver.searchAcross(keyword, page, skipSourceUrls)`——「哪些源已到底」只有调用方知道，故由调用方每轮带进来（Manager 不持有搜索会话状态）。原生搜索的传输失败也**上抛**，由 `searchAcross` 收敛成该源的 `SourceFailed`（为什么不能吞成空列表，见规格 §12「失败要如实报」）。
- 聚合搜索的**轮次协议住在 `module_find` 的 `AggregationSession`**：关键词、每源游标、已结束源集、已见 noteUrl 集（全局按 `noteUrl` 去重的唯一主人）、软 404 的「还有更多但零新增即到底」判据、本轮进度与「全部源失败」都只在那一处算。`searchAcross` 不持有任何搜索会话状态（[ADR-0016](adr/0016-multi-book-source-architecture.md) 决策 6）；`SearchViewModel` 不自持这些状态，只把聚合事件翻译成会话调用，并向页面给出列表、历史、书架标记与覆盖层状态。协议由 `AggregationSessionTest` 锁住，接线由 `SearchViewModelTest` 锁住（[ADR-0048](adr/0048-single-owner-derivation-extraction.md)）。
- **默认源的 parser 与其它源同构**（一样进 LRU）：覆盖规则只需一次 `evictParser`。书城页面判据是 `LibraryViewModel.sourceState` 四档：首帧是 `Unknown`（默认源由 Room 现算，页面那一刻还没有结论 → **整片留空**，既不画引导语也不画半截切换器），「一条源都没有」与「有源但当前源解析不出来」两句话各走各的——把后者并进前者会把用户支去启用那条本就坏掉的源。零源空态另有「去导入书源」入口（跨模块跳书源管理页，独立模式由占位宿主承接）。
- **书源接口按调用方分四面**：`SourceResolver`（取 parser、聚合搜索、分类入口）、`SourceCatalog`（清单/默认源订阅、格式查询）、`SourceAdmin`（增删、启停、设默认）、`SourceCodec`（JSON 导入导出）。解析/搜索/分类路径只注入 `SourceResolver`，书城注入 `SourceCatalog` + `SourceAdmin`，设置页注入 `SourceCatalog`，书源管理页另加 `SourceAdmin`/`SourceCodec`；`BookSourceManager` 是四者的聚合类型，只留给需要整张面的测试，生产代码不注入它。Hilt 把四个窄面各自 `@Binds @Singleton` 到同一个 `BookSourceManagerImpl`，聚合类型没有注入绑定（[ADR-0047](adr/0047-book-source-surface-split.md)）。
- 新增/改动书源面的成员要同步测试替身——具名替身 `FakeBookSourceManager` 在 `lib_book_common` 与 `module_me` 各一份，其余是 `module_find`/`module_book` 测试文件里的内联实现；替身只实现测试实际调用的那一面（或几面），未实现的成员照旧抛异常。改完接口把四个面的实现类型各搜一遍。

### 6.2 脚本书源与解析层

- `book_source.format` 列值是小写 `native`/`script`，读写一律取 `SourceFormat.raw`——拿枚举 `name` 当列值不报错也不闪退，只会让脚本行静默按原生规则解。
- 两种格式同表共存时，各读面按 `book_source.format` 分路：规则类型化读面 `getAllSources` 经 `toRule` 只认原生行；默认源回落走 `defaultFromRows`（`toItem` 选行、`toDefinition` 构造），不经过 `toRule` 的格式门，两种出身都可承载——脚本行同样可被立为默认源（`toDefinition` 的 native 分支内部仍复用 `toRule` 解码）；`observeSources()` 则必须继续带出脚本行（管理页要看得见、能禁用、能删）——三处口径本就不同，不要「统一」。
- `resolveSource` 对脚本行**永不返回 Missing/Broken、也不在取 parser 时抛**（`ScriptRuleSet.load` 惰性到首次求值才跑，坏 JSON 在那里抛类型化 `ScriptRuleParseException`）；`Missing`（没有这行）与 `Broken`（原生坏行）在 UI 上可给同一句提示，但日志必须分开。脚本失败层级已 public（ADR-0046），`lib_book_common` 可按类型处置（正文路径当前仍原样上抛）；**不得**把「这条规则解不动」折叠成「这条源已失效」。构造期抛则会在缓存锁内炸出未预期异常。
- 跨模块可见性：Kotlin `internal` 只在同模块（含同模块测试源集）可见，依赖方的 test source set 不是 friend module，故 `JsoupBookParser.rule`、`ChapterPageMatcher` 与 `ScriptBookParser` 的生产构造都是 public——上浮解析器时不要顺手收窄，且解析器测试必须与实现放在同一模块。
- `JsoupSourceReader` 住在 `lib_book_common`、**不随解析器迁走**：它依赖 `BookStore`/`ChapterReader`/`SourceResolver`，搬去 `lib_book_source` 就是一个 Gradle 环。
- 声明式求值链路全线接页面：`ScriptBookParser` 实现 `BookParser` 五面（搜索/详情/目录/发现分类/书库首屏）与 `ScriptContentParser` 正文接缝——聚合搜索候选含脚本行、正文读取按 `ScriptContentParser` 分岔、脚本源可搜索可加书架可全链路阅读。
- 脚本 URL 的关键词 `{{key}}` 按表单百分号编码；目录/正文翻页判据（零新增/回环/上限）与原生同套，但**不移植** `ChapterPageMatcher`。
- **书城接线**：默认源载体是密封 `SourceDefinition`（Native 带规则、Script 带原文+实体列展示信息），两种出身都可承载；现算与写路径收敛共用 `defaultFromRows` 一处（条目面、不筛格式），`observeDefaultSource()` 是唯一出口；`LibraryViewModel.sources` 放开 format 过滤（切换器候选 = 启用行）；分类入口经 `SourceResolver.getExploreEntries`（原生 kinds / 脚本 exploreUrl 条目，url 承载规则串，`ScriptExplore` 是唯一公开门面）。
- `SourceDefinition.Script` 的 name/url 生产填充点只有 `toDefinition`，不要解 `rule_json` 取展示信息；`getAllSources` 仍只含原生行（保留语义，不是漏改）。
- `ScriptRuleSet.unsupported` 是装载期登记的能力缺失集合，**尚未上 UI**：要出逐项警示就从这份数据接，别在解析器里另判一次。

### 6.3 规则词法、求值与取文

**规则词法层**在 `lib_book_source` 的 `com.ebook.source.script`，只回答「这条规则串被切成什么」（模式标志表、括号感知的组合符切分、`##` 替换三形态、索引方言、链式段结构、原始 JSON → `ScriptRuleSet`）。词法层自身零网络、零 Jsoup（同包里的求值/取文后端要用 Jsoup，别把这个包当无依赖层）。先切出词法层是有意的：故障可定位到「切分」、能力矩阵里有一批纯词法项、导入期可以逐条规则报告，求值与取文后端因此不必重复解析规则串。

**事实源是 [`docs/specs/book-source-rule-grammar.md`](specs/book-source-rule-grammar.md)**，与它冲突时以规格为准。两处口径是本仓规定而非上游实证：`RuleSplitter` 的 `%%`→`||`→`&&` 次序（规格 §2.3）与 `IndexSelector` 的闭区间＋「越界逐项过滤、一律不抛」（规格 §4）——改一处必须规格、实现、单测三处同步。

切分主线外另有一道**前置例外**：`@js:`/`<js>`、以 `:` 开头的正则 AllInOne 与规格 §2.5 的前导 `-`（列表反序）都在进组合符循环**之前**判出（规格 §2.2 硬约束 1「组合符不包括 js 和正则」；`()` 不计入括号深度，扫描器看不见正则字面量里的 `||`）。把这三项「统一」回切分主线的症状不是报错，而是拿链式规则去解正则、静默解错内容；反序前缀由各求值后端在产出前消费。

**HTML 侧求值入口是 `ScriptRuleEvaluator.evaluate`**：链式 / `@css:` / 正则 AllInOne 三后端、`&&`/`||`/`%%` 组合符、取值器与索引消费、`##` 替换段、`@put:`/`@get:` 变量与 `{{}}` 插值的声明式子集。三条口径：

- 「未取到值」必须是 `RuleResult.Miss`——折叠成空列表会让 `||` 分不清「这一支没值」与「取到零条」，短路整体失效。
- AllInOne 的产物是**条目×捕获组二维**（`RuleResult.Matches`），压平成一维 `$n` 字段就无从落地。
- 任何还不支持的语法一律抛类型化异常（`RuleSyntaxException`/`JsEvaluationPendingException`/`UnsupportedRuleFeatureException` 各管一种失败），返回空结果等于对用户撒谎「这个源没有这条信息」。

**JSONPath 与 URL 语义、取文层**：`JsonPathBackend` 三形态进 JSONPath；`ScriptUrlResolver`+`ScriptPageFetcher` 串起「URL 规则串 → 请求 → 响应文本」；发现页条目切分 `ExploreUrlFormat`。`{{}}` 里的算术表达式按 JS 待执行抛出，取文层不得绕过它自己算页码。三条口径：

- 取文按选项 charset **显式字节解码**（`bytes()` 取回再 `String(bytes, charset)`）；走 `body.string()` 会吃掉 `@Named("source")` 客户端 `EncodingInterceptor` 强改的 UTF-8，让 gbk 站全部乱码。
- 相对落位复用 `TocPageUrl.join` 三形态语义；`ListPageUrl` 的首页裁剪**绝不**用于脚本 URL——对算好的 URL 再裁一次会裁掉真实页码段。
- JSONPath 切片是标准半开方言（end 排他），与链式索引的闭区间分属两个语法域，不得互推；过滤器 `[?(…)]` 按不支持拒绝。

### 6.4 JS 沙箱

沙箱执行器是本仓首个 C++ 模块：`third_party/quickjs/` vendored + `lib_book_source/src/main/cpp/`，经 build-logic 的 `xrn1997.android.native` 约定插件编译。含 JS 的规则段（`@js:`/`<js>`/`js`/`bodyJs`/`init` 的 JS 分支）交沙箱执行器求值；只有**没装配执行器**的路径仍抛 `JsEvaluationPendingException`。

- **永不在主线程调沙箱**——脚本回调要回主进程，主线程调过去就是等自己；执行器客户端有主线程守卫。改它之前先想清楚回调线程模型（`HostHandler.handle` 同步、transport 是 suspend，靠 binder 线程池上 `runBlocking` 接起来）。
- **未装配执行器与执行失败是两件事**：`ctx.js == null` 抛 `JsEvaluationPendingException`（消息片段「需脚本沙箱执行器」被 `SandboxScriptJsTest` 断言，改消息会红它；`JsoupSourceReader` 侧只把类型化异常原样透出、不解析这句话）；真跑了而失败抛 `JsExecutionFailedException` 带内核原文。把两者合并成「该源已失效」会把用户支去重导一条好源。
- 沙箱外呼只用 `GuardedNetwork` 派生的守门客户端，host 白名单由 `SourceHostAllowlist` 从源规则原文导出——自己 new 一个 OkHttpClient 就等于绕过私网拒绝与白名单。守门目前只覆盖脚本自己发起的外呼；URL 选项 `js` 改写出的地址仍由取文层按原路径发出（不带凭证，但也不查白名单与私网）。
- Hilt 装配在 `lib_book_common`（`lib_book_source` 无 Hilt/KSP），`JsSandboxHost`/`HostCallbackRouter` 为此上浮 public：`provideJsSandboxHost` 里转子必须**先建、再传进客户端**，两边不是同一个对象时每条回调都报「主进程没有正在进行的脚本任务」；这一条 JVM 测锁不住、只有装机能验。

### 6.5 分页与正文跟进

- **逐页走**的机制收在 `PageWalker`（回环守卫、页数/条目上限、可选「零新增即停」）；四条链路只注入「下一页怎么算、何时停」。原生正文仍用 `ChapterPageMatcher`、脚本仍用 `ScriptPageChain`，判定函数**不合并**（规格 §7.3/§12），`PageWalkerTest` 锁机制，各链测试锁语义。
- 正文分页跟进（多页拼接）的判定基准是**目录页给出的原始章节 URL**（不对入口剥后缀），只对「下一页」候选链接剥一次分页后缀再比。对入口也剥离会让「章节号写在连字符后」的站点剥后同形而**串章**（一路跟进后续章节直到页数上限，正文错乱 + 数十次冗余请求）；「第 1 页也带后缀」的站点与此结构同形、无法靠 URL 区分，取舍是**宁漏页不串章**。边界形态由 `ChapterPageMatcherTest` 锁死。
- 列表分页（分类页/搜索页 URL 模板）的模板**必须带 `{{page}}`**，否则「加载更多」每页都在请求同一个首页；页码换算与渲染统一走 `JsoupBookParser` 的 `ListPageUrl`（它把以 `/{{page}}` 结尾的模板在首页裁掉页码段——裸路径首页 `/xuanhuan/1` 是 404），取首页也必须经它。判「到底」不能只看空页：**越界页会以 HTTP 200 重复返回首页书目**（软 404），追加页一律按 `noteUrl` 去重、无新条目即置 `hasMore=false`——列表页以 `noteUrl` 作 item key，重复条目直接抛异常。形态由 `ListPageUrlTest` 与 `BookPageMergeTest` 锁死。

### 6.6 书库缓存

书库缓存是 cacheDir 文件版：key 只能由 `libraryCacheKey(sourceUrl)` 生成、文件名取其 MD5，读与写都收在 `lib_book_common` 的 `LibraryDiskCache` 一处——各处各拼一次字符串稍有差别就变成「写完永远读不到」，表现为每次进书城都在重拉网络而功能上看不出毛病。

**缓存策略（TTL 6 小时、进页 SWR、下拉强刷 `LibraryLoadPolicy`）住 `module_find` 的 `BookSourceRepository`，解析器不碰缓存**（`BookParser.fetchLibraryData()` 纯网络解析）——把策略写回解析器是旧病根，那会让「下拉刷新」再被缓存命中吞掉。

## 7. 下载与前台服务

`DownloadService` 声明为 `foregroundServiceType="dataSync"`，两参 `startForeground(id, notification)` 合法且足够（`MissingForegroundServiceTypeException` 只在 manifest 未声明类型时抛）——不存在「必须改成三参调用」的问题。targetSdk 35+ 的实践约束（dataSync 6 小时/24h 配额、配额用尽或应用后台时启动被拒）见 ADR-0018。

- **拉起服务一律走 `DownloadService.start(context, intent)`**（返回 false 即需提示用户），禁止在页面/ViewModel 里直接调 `ContextCompat.startForegroundService`。
- **发起下载先入库再拉服务**（统一经 `DownloadRepository.startDownload`），否则启动被拒时只躲在 Intent 里的任务会丢。下载任务以 `download_chapter` 表为唯一队列事实源，启动 Intent 只带控制信号、不携带任务列表。
- **`onTimeout` 内只做数秒可完成的收尾**，不得查库/发网络请求/起协程。
- **队列策略收在 `DownloadBatch`**：候选域（书架顺序、跳过暂停书）、暂停标记、孤儿清理与队列计数只写一处；`DownloadRepository` 的队列策略面（取队头/队尾、暂停/继续、孤儿清理、计数）委托它，页面展示用的 `getPausedBooks`/`observeRemainingCount`/`getAllTasks` 仍直读 DAO，不参与策略判定。服务按「取不到可跑任务 + 表内仍有任务」判暂停而非完成（ADR-0036）。
- **单章执行收在 `DownloadRunner`**：缓存命中、强刷删章、空正文判失败、重试耗尽出队与暂停中断不出队都在这里；`DownloadService` 只保留前台生命周期、通知、跳过计数、暂停收尾与下一章调度。runner 之外逃出的意外异常也停在暂停态、不删任务（不把「没抓成」静默成跳过）。`DownloadRunnerTest` 锁住「失败耗尽出队、暂停不出队」，`DownloadBatchTest` 锁候选域、暂停跳过与孤儿清理。
- **下载中心的选章计划收在 `ChapterSelectionPlan.kt`**：预勾选窗口由 `initialChapterSelection` 给出；未缓存集、确认任务、跳过数与书头三态动作由 `planChapterDownload` 一处算出；`DownloadManageViewModel` 与 `ChapterDownloadSheet` 消费同一份结果，`DownloadBatch` 仍是队列策略的唯一主人。`ChapterSelectionPlanTest` 锁计划本身，`ChapterSelectionTest` 锁三态与区间的纯逻辑（[ADR-0048](adr/0048-single-owner-derivation-extraction.md)）。

下载中心的当前交互（一级按书任务列表、二级选章、按书暂停/继续）与目录定位解耦见 ADR-0034/0035/0036；实现细节以对应页面代码与 KDoc 为准。

## 8. 阅读器

- **阅读位置只有一个主人：`ReaderSession`**（由 `BookReadViewModel` 持有，页面经 `readerState` 读）。它持有打开时的书架快照、实时 `(chapterIndex, pageIndex)`，并派生章名、章数、滑条值，同时落定 `inShelf`/`shelfChecked`；追更（末章判定、`tag` 归属门、单飞、回读目录整体替换）与换源后的整行收下（`adoptAfterSourceSwitch`）都在会话里完成，书与目录不在页面另存第二份。`ReadBookActivity` 只收集 `readerState` 并组合、接线各控制器，控制器把新位置写回会话。`ReaderSessionTest` 锁会话的位置所有权、保存内容（未 adopt 空转）、追更门与换源收下，`ReadingProgressFlowTest` 走真 Room 保住端到端口径（[ADR-0048](adr/0048-single-owner-derivation-extraction.md)）。
- **分页与导航是会话之上的适配层**：`ReaderPagination` 仍是视口测量、整章排版缓存与页切片的唯一状态机，`ReaderNavigation` 仍是模式路由与单进度出口，两个 `ReaderTurnController` 仍是它们的适配器；三者都不持有书架快照、也不各自持久化进度——控制器只保留渲染几何（当前页键 / 锚点），交互位置的变化由控制器发起、经同一 `onProgress` 交回会话，会话才是持久化的主人，下文列出的不变量不变。
- **翻页方式有两种且共用分块链**（左右翻页 / 上下滚屏，导航判据 `ReaderTurnMode`、持久化索引 `turnModeIndex`）：两者都由同一条排版分块链给出「第 N 屏的正文」，差别只在容器（三页窗口 + 横向拖拽 / **跨章连续**的竖向列表，章界只有一个标题项、没有「上一章/下一章」链接），故进度口径同为「章内第几屏」、`dur_chapter_page` 语义不变。
- **分页状态机收在 `ReaderPagination`**：视口测量、整章排版缓存、页切片与按行换算都在这一处；`ReadBookActivity` 只把 Compose 测到的视口交进来，`loadPage` 用 `ReaderPageWindow` 切片后把 `ReaderPageUi.Loaded` 交给控制器。`ReaderPaginationTest` 锁状态与切片边界。
- **导航控制面收在 `ReaderNavigation`**：路由判据只有 `ReaderTurnMode`（页面用 `turnModeIndex` 镜像驱动重组，切换时同步写入），两个控制器都实现 `ReaderTurnController` 并退化为它的适配器；`gotoPage`（内部委托 `setPosition`）、音量键、换源后的换算都走 `setPosition`/`turnNext`/`turnPrev`，**只驱动当前模式的控制器**；冷启动与重分页另走 `converge`，把另一侧的落点一并预热上（`reportProgress = false`）——另一侧不预热就带着「章号 0」的初值躺着，换模式后新容器成形的那一帧落点是错的，而让它报进度会用另一种模式的屏号盖掉当前进度。两个控制器共用同一份 `onProgress`。`ReaderNavigationTest` 锁模式路由、两档定位与单进度出口。
- **预热侧不补报，当前模式先报可解释的落点**：预热侧（`reportProgress = false`）的探测块加载完成也不得补报；滚屏侧在块数未知时先报目标页码（BEGIN 归一为 0、END 暂报哨兵），块数落定后补报真实块号；翻页侧按页键报目标页码/哨兵，不在加载完成时补报。否则未显示侧会回写旧页，慢加载中退出会把解析不出的 0 落库。`ReaderScrollControllerTest` 锁「预热加载完成不报进度」与「块数未知不报 0」。
- **换模式的旧行数在点击时快照**：效应要等新容器的视口回报才跑，中间可能已经过一次 `rePaginate`（末章追加/字号变化）；现读 `pageLineCount` 会拿到新模式的值，换算退化成同页号。快照位住页面状态，不放进分页状态机。
- **块高一律取排版实测值**（`ReaderTypesetter.measureBlock`），不得用「行数 × 行高」心算；两种模式的正文视口高度不同（滚屏块内不含标题行），故 `pageLineCount` 不同，切换时按**行号**换算落点（`ReaderPagination.convertPageIndex`）。页面上的跳转一律走 `gotoPage`（内部按当前方式路由到对应控制器）——直接调某一个控制器在另一种方式下不报错、只静默失效。
- 滚屏连续列表的三条硬约束：
  - **item 必须带稳定且全局唯一的 key，且 key 只由 `(章, 块)` 构成、不含扁平序号**（LazyColumn 靠 key 在「锚点上方插入 item」时保住画面，而扁平序号会随插入整体平移；重复 key 会锚到另一项、位置静默跳变）。
  - **锚点存 `(章, 块)` 语义值而不是 item 序号**（存序号等于相邻章段一展开就把进度写偏一屏）。
  - **单次上方插入不超过约 100 项**（平台的 key→index 映射只索引锚点附近一个窗口，超窗即锚定失败且不自愈）。
- 阅读进度只在 `onPause` 落库（`initData` 那次会话尚未收下书架，空转）；前台 force-stop 与崩溃时 `onPause`/`onDestroy` 都不执行——所以「异常关闭后恢复」的判据只能是**标记残留**，不能靠进度本身。

## 9. 本地书导入

- **扫描资格与处置可用性各只有一个主人**：`ImportScanner` 收扫描资格（EPUB 一律、TXT 严格 >100KB、按相对根路径整棵跳过 `Android/data|obb`、取消返回已收集前缀、根不存在或不可读返回空），`DispositionPolicy` 收四种处置的可用集（只有命中项含本地书才给 `MERGE`）；`BookImportViewModel` 只提供存储根、挂载判断与取消信号，`ImportBookActivity` 直接读 `Detected.available`，不再自己算 `canMerge`。导入顺序、合并/覆盖语义、判重口径、权限与 UI 文案不变；`ImportScannerTest` 与 `DispositionPolicyTest` 锁这两处判据（[ADR-0048](adr/0048-single-owner-derivation-extraction.md)）。
- **导入判重**：口径是待导入文件的 `comment_key` 等于书架某条目的**当前主键**，不是比 `book_info.name` 书名——键含作者，只比书名会把同名不同作者的两本书判成一本并给出删除入口。
- 处置四个动作（继续添加/智能合并/覆盖/取消——最后一个不导入本文件、批次里的其余文件照常走），非破坏的「继续添加」占主按钮位；顺序一律**先导入新条目、后处置旧条目**，覆盖删旧之前先 `absorbGroupKeys` 吸收旧条目的关联键。
- 补章只对本地目标书，且要求旧书归一化章名序列是新书的**前缀**，分叉即整笔放弃；新索引取现有最大 `durChapterIndex + 1`，不用 `size`（历史删章留下的洞会让二者不等，从而覆写既有章文件）。
- 导入器只负责文件与实体；四表写入统一走 `BookRepository.addToShelf`（内部 `ShelfEntryStore`），不再自己写一份。
- 移出书架与智能合并删除条目时，`Removed` 事件在库事务提交后、章文件清理前发出；清目录失败不阻断「条目已移除」的事实，残留目录由启动对账回收。
- 书籍内容住在 `filesDir/books`；目录名由 `BookStore` 从 `note_url` 派生——本身已是合法单段名就原样用、否则取 md5（网络书的 `note_url` 是 URL，直接当目录名会落成嵌套目录树，启动对账会误删全部网络书章缓存）。本地书派生后逐字不变，`content_ref` 无需迁移。

## 10. 缓存管理

缓存管理页的「缓存」只指 `cacheDir`（图片/临时/其他三档，可清，`cacheBreakdown` 一次遍历分档）；书籍内容住在 `filesDir/books`，由 `BookStore.storageUsage()` 一次遍历给出「占用 + 册数」并单列一行——**不计入可清理总量、本页不删书**（删书唯一入口是书架长按）。该行可点，用 `CLEAR_TOP` 回主页（其 `startDestination` 即书架）；独立运行无该路由时按 `matchRouteMap` 退化为不可点，别留假箭头。把两者合并或在本页顺手加删除，会造出第二个删书入口与两种占用口径。

## 11. 共享 UI

跨模块共享的 Compose 组件（卡片/列表项/标签/封面/头像/空态等）统一归口 `lib_book_common` 的 `com.ebook.common.ui`（`CommonUiTokens` 设计常量 + `CommonCard`/`CommonListItem`/`InfoChip`/`BookCover`/`Avatar`/`EmptyState` 等），字号走 Material typography，兜底默认图与组件同处一档（`drawable-xxhdpi`，不在业务模块留副本），不要在模块内新建重复实现。

- **「三行书条目」这一族**（`BookItemLayout` 常量 + `BookItemFrame` 外壳 + `BookItemMetaRow` 底行）由找书条目与书架条目共用，谁都不该在模块里另起一份同构实现。
- **空态与「取不到内容」的失败态一律走 `EmptyState`**（图标由调用方模块给，共享库依赖不到 iconsExtended）；`lib_common` 的 `NoDataView` 位图插画本仓刻意不用。
- **共享件上浮两步走**：迭代中发现明显的跨模块共性（域无关的工具函数、通用组件等），优先抽到 `lib_book_common` 消除重复；等同类共性积累到能组成完整、通用的功能集时，再整体推荐迁入外部库 `lib_common`（`FileTree.treeSize` 即此类待迁移候选）。

## 12. 启动、路由、版本检查与权限

### 12.1 启动与路由

**TheRouter 的路由表是异步加载的**（`RouteMapKt.asyncInitRouteMap` 走 `TheRouterThreadPool`），启动期用 `matchRouteMap` 探测会把「还没加载完」误判成「没有这条路由」而静默跳过。需要「路由不可用时降级」就用 `createIntent(ctx)` 拿 Intent、判 `component` 非空后自己 `startActivity`，并在启动业务页之前先把主页垫进栈（否则页面起不来时用户停在一片空白）；`navigation()` 不给「是否落地」的反馈，凡是要区分成败的启动期跳转都别用它。

### 12.2 版本更新检查

发布源顺序/failover/`.apk` 过滤等**策略**在 `module_me` 的 `ReleaseRepository`，HTTP 与接缝在 `lib_ebook_api/service/release`，**不得**把仓库层写回 `lib_ebook_api`；该请求走专属 `@Named("release")` 纯净客户端（不带 token、不套解 `RespDTO` 信封的 `CoroutineAdapter`）。两条不变量：**判不出结论就不算检查成功**（远端 tag 解析不出版本、本地版本号读不到 → 按检查失败处置且不写限频时间戳）；**「是否有新版」是派生态**（由上次检查到的 tag 与装机版本现场比较，不落地成布尔量）。

### 12.3 权限

改清单权限前先读 ADR-0022 的逐条判据：删一条的前提是**全仓（含依赖的 lib_common）找不到需要它的 API**，不是「看着没用」；两份 Manifest 的权限条目必须同步增删，改完必须读 `module_app/build/intermediates/merged_manifests/*/AndroidManifest.xml` 核对**合并结果**（库可能把自己声明的权限加回来，必要时才用 `tools:node="remove"` 覆盖）。存储三项（`MANAGE`/`WRITE`/`READ_EXTERNAL_STORAGE`）与 `FOREGROUND_SERVICE*` 不得顺手删；拍照/相册/导入/下载通知四条回归属人工装机验证项。
