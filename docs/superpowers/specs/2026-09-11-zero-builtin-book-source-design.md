# 应用零内置书源设计

- **日期**：2026-09-11
- **状态**：设计定稿，待实施
- **范围**：`lib_book_common` / `lib_ebook_db` / `module_find` / `module_me` / 仓库资产与文档

## 1. 背景

**合法性口径**：本应用不随包携带任何书源。书源是第三方内容站点的解析配置（站点地址 + 抓取规则），
随包分发等于由本应用内置一份指向特定站点的访问配置。用户自备书源（自行导入社区 JSON）不受此限。

**现状与口径的冲突**：`lib_book_common/src/main/assets/default_sources.json` 内置一条「笔趣阁」源，
首启由 `BookSourceManagerImpl.ensureSeeded()` 在 `book_source` 表为空时整批灌入 Room
（`is_user_imported = false`），并配套一整套「内置源不可删」的删除保护。这套机制直接违反上述口径。

**本设计的输入（2026-09-11 逐条确认）**：

1. 拆除深度：**彻底拆净** —— `isUserImported` 概念一并拆除，不留死逻辑。
2. 同步属性 `BookSourceManager.currentSource`：**一并拆除**（已无生产消费方）。
3. 首装引导：**一并补入口** —— 零源首装时书城空态能直达书源管理。
4. 仓库里的真源语料夹具：**一并移出 git**。
5. 内部快照 `defaultSnapshot`：**一并拆除**（其存在前提是 assets 冷启动，前提已消失）。

## 2. 目标与非目标

**目标**

- APK 内不再有任何书源配置。
- 代码里不再存在「内置源」这一概念及其所有派生机制（首启 seeding、删除保护、冷启动同步快照）。
- 零源首装的用户能从书城空态一步到达导入入口。
- 仓库不再跟踪真源规则与真站 HTML 快照。

**非目标**

- 不提供内置源的合法替代品（书源市场、推荐榜单等）。
- 不做专门的首启引导页或引导弹窗。
- 不改动书源解析链路本身（解析器、沙箱、脚本书源解释器一律不动）。

## 3. 现状链路（拆前）

内置源的全部落点，供实施时逐个核对：

| 位置 | 职责 |
| --- | --- |
| `lib_book_common/src/main/assets/default_sources.json` | 内置源本体（一条「笔趣阁」） |
| `BookSourceManagerImpl.loadAssetsRules()` | 构造期读 assets 成 `coldStartSources` |
| `BookSourceManagerImpl.init` 冷启动段 | 按 assets 清单 + SP 猜同步快照 `defaultSnapshot` |
| `BookSourceManagerImpl.ensureSeeded()` | 表空时把 assets 整批灌入（`isUserImported=false`） |
| `BookSourceManagerImpl.toEntity(isUserImported=…)` | 灌库与导入共用的落库路径 |
| `BookSourceManager.currentSource` | 同步属性；生产代码已无消费方（`LibraryViewModel.currentSource` 是订阅 `observeDefaultSource()` 的同名 StateFlow），仅测试引用 |
| `BookSourceItem.isUserImported` | 管理页据其把内置源的删除项置灰 |
| `BookSourceEntity.isUserImported` | `is_user_imported` 列，删除保护的事实源 |
| `BookSourceDao.deleteUserImported()` | 删除语句带 `AND is_user_imported = 1` |
| `BookSourceDao.getCount()` | 首启 seeding 的判据（`== 0` 才灌库） |
| `module_find/BookstorePage.kt` `SourceGuidance` | 无源引导态，当前刻意不给跳转按钮 |

`BookSourceManagerImpl.seeding`（`Deferred<Unit>`）是上述 seeding 的可等待句柄，全部挂起入口
（`getAllSources` / `getEnabledSources` / `getSourceByUrl` / `getParserFor` / `searchAcross` /
`addSource` / `addScriptSource` / `getFormatByUrl` / `getExploreEntries` / `removeSource` /
`setEnabled` / `setDefaultSource`）与两个冷流都以 `await()` 起手。本设计完成后这台机器整体下线。

## 4. 设计

### 4.1 拆掉内置源与删除保护

- **删资产**：`lib_book_common/src/main/assets/default_sources.json`。
- **`BookSourceManagerImpl`**：删 `DEFAULT_SOURCES_FILE`、`coldStartSources`、`loadAssetsRules()`、
  `ensureSeeded()` 中灌库段与 init 冷启动猜测段；`toEntity` 去掉 `isUserImported` 入参。
- **`BookSourceManager` 接口**：删 `currentSource` 属性；重写接口与各处 KDoc 中把「内置源」「不可删」
  「首启 seeding」当事实描述的段落。
- **`BookSourceItem`**：删 `isUserImported` 字段（该类型的「存在理由」随之改写为「承载管理面专属的
  `format` 出身位」）。`format` 保持不变。
- **`BookSourceEntity`**：删 `isUserImported` 字段。
- **`BookSourceDao`**：`deleteUserImported(url): Int` → `deleteByUrl(url): Int`（去掉
  `AND is_user_imported = 1`）；`getCount()` 若在拆完后无调用方则一并删除，不新留一笔零调用方 DAO 技术债。
- **`module_me`**：`BookSourceManageActivity` 删「内置源删除项置灰」分支与相关常量；
  `BookSourceViewModel`、`SettingViewModel` 清掉述及内置源的 KDoc 与逻辑。
- **`lib_book_common/build.gradle.kts`**：确认移除该资产后无其它测试读 `src/main/assets`，
  则移除为 Robolectric 读 assets 而开的 `unitTests.isIncludeAndroidResources` 开关（该开关当初
  就是为 `BookSourceManagerImplTest` 读 `default_sources.json` 开的）。

### 4.2 默认源模型：内存快照 → Room 现算

`defaultSnapshot` 存在的唯一前提是「assets 冷启动时能同步猜出一个默认源」；assets 与 `currentSource`
都没了之后，它只剩两个用途——`getParserFor` 的默认源快路径，以及写路径的「有没有默认源 / 覆盖的是不是
默认源」判定。两者都可从 Room 现算，故整体拆除：

- 删 `defaultSnapshot`、内部类 `DefaultSource`、`refreshDefaultFromRoom()`。
  `applyDefault()` 不再构造 parser，其职责退化为「写 SP + 推 `defaultUrlFlow`」，与 `persistDefaultUrl()` 合并为一处。
- 默认源判定统一为 `defaultFromRows(dao.getAll())` 现算；「有没有可用默认源」即其返回值是否为 null。
- **保留 `defaultUrlFlow`**：`setDefaultSource` 对一条已启用的源只写 SP、不碰 Room，
  没有这个信号 `observeDefaultSource` 的 combine 不会重推，用户在书城切换默认源会看不到反应。
  它从「快照 URL 的推送源」降格为「SP 变更信号」，语义要同步改写。
- `getParserFor` 去掉默认源快路径，统一走 LRU。写路径本来就一律 `evictParser`，
  于是「默认源 parser 与 LRU 里的副本」这一对概念合并为一份，不再有第二个事实源。
- 删 `seeding` Deferred 与全部 `await()` 调用：没有后台写任务了，挂起面直接查 Room。
  两个冷流（`observeSources` / `observeDefaultSource`）随之退化为纯 `dao.observeAll()` 映射 + combine。
- **行为变化**：`setEnabled(禁用当前默认源)` 与 `setEnabled(启用且当前无默认源)` 两条分支保留原语义
  （回落到下一条启用源 / 提升新启用源），但实现改为「重算 + 持久化 SP + 推 `defaultUrlFlow`」。

### 4.3 数据库迁移

- `AppDatabase` version 6 → 7。
- 新增 `MIGRATION_6_7`，两条语句：

  ```sql
  DELETE FROM book_source WHERE is_user_imported = 0;
  ALTER TABLE book_source DROP COLUMN is_user_imported;
  ```

  先删内置行（`is_user_imported = 0` 只可能是内置源种下的），再去列，一次迁移同时完成
  「清掉已 seed 的内置源」与「去掉这一列」。

- **`DROP COLUMN` 可用性**：本仓 MIGRATION_3_4 已在用 `ALTER TABLE chapter_list DROP COLUMN has_cache`，
  且数据库由 `BundledSQLiteDriver`（`androidx.sqlite:sqlite-bundled` 2.7.0）承接，DDL 能力取决于
  随包 SQLite 而非设备系统版本，故无需重建表。`is_user_imported` 是普通列（非主键、无索引），
  不触及 `DROP COLUMN` 的限制。
- 提交 Room 生成的新 schema JSON（`lib_ebook_db/schemas/**/7.json`）。
- **对存量装机的影响**：发布基线 `master` 的 `AppDatabase` 是 version 2、根本没有 `book_source` 表
  （该表与 4→5 迁移都在未发布的 `develop_2` 上）。因此正式用户的升级路径是 2→…→7，
  `MIGRATION_4_5` 建出的就是一张空表，本设计实施后**不再有任何代码往里灌数据**。
  只有装过 `develop_2` 开发包的设备会有 `is_user_imported = 0` 的行，由 `MIGRATION_6_7` 清掉；
  这些设备上若已有书绑定到该源，升级后按既有语义显示「书源已失效」——这是预期结果。

### 4.4 书城空态引导入口

- `module_find/BookstorePage.kt` 的 `SourceGuidance`：`LibrarySourceState.NoSource` 档增加按钮
  「去导入书源」，动作为 `TheRouter.build(KeyCode.Me.BOOK_SOURCE_PATH).navigation(context)`。
- `LibrarySourceState.BrokenSource` 档**维持无按钮**：该档的主动作是「在顶部换一个源」，
  页面顶部本就有切换器可用，与 `SourceGuidance` KDoc 的既有理由一致。
- **独立模式（`isModule=true`）的路由丢失**：`Me.BOOK_SOURCE_PATH` 属 `module_me`，独立运行时不存在，
  TheRouter 找不到路由只记一行日志、不报错不闪退，按钮会变成「点了没反应」。按仓内既有写法解决：
  - `module_find/src/main/test/debug/` 新增 `TestBookSourceActivity`（`@Route` 到新常量
    `KeyCode.Find.TEST_BOOK_SOURCE_PATH`），内容只做占位提示；
  - `TestApplication` 的 `PathReplaceInterceptor` 增加 `Me.BOOK_SOURCE_PATH → Find.TEST_BOOK_SOURCE_PATH`
    的替换（与既有 `Book.DETAIL_PATH → Find.TEST_DETAIL_PATH` 同一机制）；
  - `KeyCode.Find` 增加 `TEST_BOOK_SOURCE_PATH` 常量。
- `SourceGuidance` 的 KDoc 需改写：原文「刻意不给按钮」的理由已被撤销，要写明现在的跨模块路由
  处置方式（独立模式下由占位宿主承接）。

### 4.5 真源语料夹具移出 git

已入库的 `lib_book_source/src/test/resources/scripted_corpus/<slug>/` 含真源规则（`source.json`）
与真站响应（`pages.json`，含目录页与**章节正文**），与「真源规则、真站 HTML 不入仓」的口径冲突。

- `.gitignore` 增加 `lib_book_source/src/test/resources/scripted_corpus/`。
- 对已入库的那条夹具 `git rm --cached -r`（**保留本地文件**）。
- 回放测试 `ScriptedCorpusReplayTest` 已用 `assumeTrue` 跳过「无夹具」情形，无需改行为；
  只更新录制器与回放测试的 KDoc，说明夹具是本地录制产物、不随仓分发。
- 用户本地那 4.8MB `lib_book_source/src/test/resources/book_source/测试源.txt` 维持现状排除。
  录制器的取源方式不变（`-Pcorpus.record=<文件>` / `-Pcorpus.batch=<文件>` 均为外部传路径），
  删内置源不影响录制链路。

### 4.6 文档与 ADR

- **新增 ADR**：应用不随包携带任何书源。内容需自足：合法性口径、用户自备书源的边界、
  连带拆掉删除保护/同步快照/seeding 的取舍、真源规则与真站 HTML 不入仓的口径、
  以及「升级后已绑定该书源的书显示书源已失效」这一已知结果。
- **就地更新**（把内置源/不可删/seeding 当事实写的段落）：ADR-0016、ADR-0022、ADR-0027。
- **`AGENTS.md`**：第 215 条（「先读 default_sources.json 获取默认内置书源实况」一段）、
  第 217 条（「内置默认源由 assets 在首启 seeding 写入 Room…不可删」一段）、
  第 231 条（「内置书源曾如此」括注）。
- **`CONTEXT.md`**：若有「内置书源」术语一并清理。
- **`docs/multi-source-plan.md`**：先核对该文件性质（是否属 `docs/superpowers/` 那类一次性工件），
  再决定回写还是标注为历史处方。

## 5. 测试策略

- **`BookSourceManagerImplTest`**：删除首启灌库、内置源不可删、assets 冷启动快照那批用例；
  改锁新行为——
  - 零源时清单为空、两个默认源出口同为 null、聚合搜索只发 `AllFinished`；
  - 导入第一条启用源即成为默认源（原「无默认源时提升」逻辑的等价迁移）；
  - 任意一条源都可删除，删除成功即从清单消失。
- **假件同步**：`grep ": BookSourceManager {"` 现数（当前 10 处：1 生产实现 + 2 具名
  `FakeBookSourceManager` + 7 内联），去掉 `currentSource` 覆写与 `isUserImported`；
  漏改的一侧直接编译失败，不靠写死清单。
- **`LibraryViewModelTest`**：复用既有引导态用例，补「零源不发请求」的断言（若尚未覆盖）。
- **迁移的验证方式**：分两层，都跑在 `./gradlew test` 里。
  ① **schema 契约**：`AppDatabaseSchemaTest` 的 `SCHEMA_LATEST_VERSION` 升到 7，新增
    「v7 相对 v6 只少 `is_user_imported` 一列」与「7.json 建表语句不含该列」两条断言。
  ② **迁移行为**：新增 `BookSourceMigration6To7Test`，用裸 `SQLiteConnection` 直接驱动
    `DatabaseModule.MIGRATION_6_7.migrate(...)`（该迁移因此声明为 `internal` 而非 `private`），
    断言「内置行被清、用户行保留、列已消失」，并以 `SELECT sqlite_version()` 守卫引擎版本 ≥ 3.35。
    关键在于**跑的是生产同款引擎**：`lib_ebook_db` 增加 `testImplementation` 依赖
    `androidx.sqlite:sqlite-bundled-jvm`（该 jar 带 windows/linux/macos 原生库，实测引擎为
    SQLite 3.50.1，与 APK 内 `BundledSQLiteDriver` 同源）。**不**使用 `MigrationTestHelper`：
    它要 Room 的 schema 资产走完整建库/校验流程、且默认驱动在 JVM 上加载不到，而裸迁移只要求任意连接。
  「覆盖安装后 Room 认出 v7 结构并正常打开」这一步仍归人工装机验证（见 §6）——本用例不驱动 Room 的
  建库与 schema 校验。
- **反向实验**：把「导入第一条启用源即成为默认源」临时改坏，确认对应用例变红后可还原；
  这是本仓区分「真锁住行为」与「恒真断言」的既有手段。

## 6. 验证分工

- **Agent 侧**：`./gradlew test`、涉及模块 `./gradlew :module_find:assembleDebug` 等编译与静态检查。
- **人工侧（Agent 未做，不得以「构建通过」暗示已验证）**：
  1. `develop_2` 旧包装机后覆盖安装新包，书架/书城/阅读/下载无回归，书源清单里原本的内置源消失；
  2. 全新安装（清数据）进书城，空态出现「去导入书源」按钮且能跳到书源管理页；
  3. `isModule=true` 独立运行 `module_find`，同一按钮跳到占位页而非「点了没反应」；
  4. 导入一条真实书源后，书城立即以它为准渲染（默认源提升路径）。

## 7. 风险与遗留

- **`getParserFor` 失去默认源快路径**：默认源的 parser 此后与其它源一样进 LRU（容量 3），
  多源并发下可能被淘汰重建。功能无影响（与其它源同构），代价是极端场景下一次额外的 parser 构造。
- **`setDefaultSource` 的 SP 语义**：改造后「写 SP」与「推 `defaultUrlFlow`」必须成对完成，
  漏一处就是「本次会话看着对、下次启动回到旧源」，实施时按 `applyDefault` 原有的收敛点集中处理。
- **`is_user_imported` 列删除不可逆**：迁移后无法区分「曾内置」的行——这正是本设计的目的，
  但意味着若将来要恢复内置信道，需要新的一列而不是复用旧语义。
