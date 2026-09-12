# 多书源共存实施路线图

配合 [ADR-0016](adr/0016-multi-book-source-architecture.md) 使用。本文档给出**分阶段、可发布、可回滚**的具体实施路线，
每阶段独立提交、独立验证；中间任何一步发现问题可及时调整方向，不必推倒重来。

## 校准记录（2026-09-07）

本文成稿时工程处于 Room v2，实施前按当前代码校准了四处偏差，正文已就地更正：

1. **新表是 `v4 → v5`，不是 `v2 → v3`**。迁移链上已有 `MIGRATION_1_2`/`MIGRATION_2_3`/`MIGRATION_3_4`，
   `MIGRATION_2_3` 这个编号已被占用（本地书正文迁章文件），照旧写会直接撞车。
2. **`BookRepository.loadBookContent` 已不存在**。`book_content` 表在 v3→v4 迁移中删除，正文统一经
   `chapterReaders[BookFormat].readChapter(entry, location)` 读取，网络书那一半是 `JsoupSourceReader`。
   因此「按 `tag` 找 parser」在正文路径上的落点是 `BookLocation`（书级定位值类型），不是仓库方法签名。
3. **`requireParser()` 实际调用点 8 处**，不是 10 处（清单见 P3-a）。
4. **`addSource` 冲突策略取 REPLACE（覆盖）**。原 ADR 决策 4 写「url 冲突拒绝」，与决策 9 的导入预览
   「已存在的 URL 标记将覆盖」及附录 A 的注释互斥；导入社区书源 JSON 时整包覆盖是主用法，
   拒绝策略会让用户无法更新一份已有书源。ADR 决策 4 已同步更正。

## 落地状态（2026-09-08 收尾）

**P0 / P1 / P2 / P3-a / P3-b / P3-c / P3-d 全部已实现**；P4 是本轮的文档与验证收尾（装机验证归人工，见附录 D）。

本文其余部分是**动工时的处方**，不是现状描述。与实况不符的地方已就地标注（多为「落地实况」「2026-09-08
更正」字样），实现细节一律以代码与 KDoc 为事实源——**接口签名不在本文维护**（附录 A 已改成指向源码的指针）。
四条最容易按旧处方走错的失效点先摆在明处：

1. `requireParser()` / `currentParser` **从未走过「标 `@Deprecated` 保留一版」这一步**：调用点在
   P3-a/P3-b 内一次迁完，归零后直接从接口与实现删除（本文所有「保留兜底」「过渡期继续用」的句子都作废）。
2. 书库缓存 key 的生成不在解析器也不在仓库，收在 `lib_book_common` 的 `libraryCacheKey(sourceUrl)` 一处。
3. 换源的「旧缓存保留」只覆盖盘上章文件与内存缓存，**库内四张表的行一律删净**，且这份保留只在同一次
   进程内成立——跨启动由内容仓库对账回收。
4. **「内置源 + 首启 seeding + 默认源同步快照」整条线（2026-09-11 起）作废**：应用不再随包携带任何书源，
   assets 内置清单、`ensureSeeded()`、`is_user_imported` 列与「内置源不可删」保护、同步属性
   `currentSource` 与内存快照 `defaultSnapshot` 全部删除；默认源改为每次从 Room 现算，
   `observeDefaultSource()` 是唯一出口。本文凡以「内置源」「assets 默认源」「seeding」为前提的处方
   （含附录 A 里 `currentSource`、`deleteUserImported`、`getCount` 的引用）一律按此理解。

## 目标与范围

- **目标**：从「全局单一书源」升级为「多书源共存 + 每本书绑源 + 聚合搜索 + 阅读中换源」。
- **范围内**：数据层（Room schema）、共享层（`BookSourceManager`）、业务层（书架/搜索/书城/阅读器/下载）、
  UI 层（module_me 书源设置页、module_find 顶部切换器、module_book 换源弹层）、文档与术语。
- **范围外**（留到 P4 或独立立项，见 ADR-0016 遗留清单）：网络订阅 URL 导入、完整书源连通性测试、
  跨源换源的全文匹配定位、书源分组折叠、拖拽排序。

## 阶段总览

| 阶段 | 交付内容 | 前置依赖 | 工作量 | 可发布性 |
|---|---|---|---|---|
| **P0** ✅ | ADR-0016 + 本路线图 + CONTEXT.md 术语补充 + AGENTS.md 约定 | — | 0.5 天 | 纯文档，无需装机验证 |
| **P1** ✅ | Room schema v5 + `BookSourceEntity` + DAO + Migration + `BookSourceManager` 重构 | P0 | 2-3 天 | 单独可发布（UI 无感知；当时靠 `requireParser` 兜底，该兜底出口后来已删除） |
| **P2** ✅ | `module_me` 书源设置页（列表 + 导入 + 导出 + 启用/禁用 + 删除） | P1 | 2 天 | 单独可发布（用户能看到并管理书源，业务层仍走默认源） |
| **P3-a** ✅ | 书架/阅读器/下载按 `tag` 走独立 parser（当时盘点的 8 处 `requireParser` 全部改造，方法已删） | P1 | 1-2 天 | 单独可发布（多书源共存在数据流层生效） |
| **P3-b** ✅ | 聚合搜索（`searchAcross` + `SearchViewModel` 重写 + 结果打标签） | P3-a | 2-3 天 | 单独可发布（搜索体验升级） |
| **P3-c** ✅ | 书城顶部书源切换器 + 缓存按源分区 | P1 | 1 天 | 单独可发布 |
| **P3-d** ✅ | 阅读中换源（`SourceSwitchSheet` + `BookRepository.switchSource` 事务） | P3-a、P3-b | 3-4 天 | 单独可发布（用户可在阅读器内跨源换书） |
| **P4**（本轮） | 装机验证 + 文档收尾 + 提交规范审查 | P1-P3 全部 | 1 天 | 版本发布前 |
| **总计** | | | **~13-17 天** | |

**依赖关系**：
```
P0 → P1 → P2 → P3-a → P3-b → P3-d → P4
              ↘ P3-c ↗
```
P3-a 和 P3-c 可并行；P3-b 依赖 P3-a（`searchAcross` 需要 `getParserFor`）；P3-d 依赖 P3-b（换源弹层用聚合搜索）。

---

## P1 · 数据层与 Manager 重构

### 目标
Room schema 升级到 v5（当前 v4）；`BookSourceManager` 从「单 parser」重构为「按 URL 查 parser + Room 持久化」；
业务代码**零改动**（`requireParser()` 保留兜底）。
> **落地实况**：本阶段确实零改动，但「保留兜底」只活了不到一个阶段——P3-a/P3-b 把调用点迁到归零后，
> `requireParser()` 与 `currentParser` **一并从接口与实现删除**，没有走「标 `@Deprecated` 留一版」。

### 前置依赖
P0 完成。

### 具体改动清单

**`lib_ebook_db`**：

1. 新增 [`lib_ebook_db/src/main/java/com/ebook/db/entity/BookSourceEntity.kt`](../lib_ebook_db/src/main/java/com/ebook/db/entity/BookSourceEntity.kt)：
   ```kotlin
   @Entity(tableName = "book_source")
   data class BookSourceEntity(
       @PrimaryKey @ColumnInfo(name = "url") val url: String,
       @ColumnInfo(name = "name") val name: String,
       @ColumnInfo(name = "rule_json") val ruleJson: String,
       @ColumnInfo(name = "enabled") val enabled: Boolean = true,
       @ColumnInfo(name = "weight") val weight: Int = 0,
       @ColumnInfo(name = "group_name") val group: String = "小说",  // group 是 SQL 关键字，列名改 group_name
       @ColumnInfo(name = "is_user_imported") val isUserImported: Boolean = true,
       @ColumnInfo(name = "added_at") val addedAt: Long = System.currentTimeMillis(),
   )
   ```

2. 新增 [`lib_ebook_db/src/main/java/com/ebook/db/dao/BookSourceDao.kt`](../lib_ebook_db/src/main/java/com/ebook/db/dao/BookSourceDao.kt)：
   ```kotlin
   @Dao
   interface BookSourceDao {
       @Query("SELECT * FROM book_source ORDER BY weight ASC, added_at ASC")
       fun observeAll(): Flow<List<BookSourceEntity>>

       @Query("SELECT * FROM book_source ORDER BY weight ASC, added_at ASC")
       suspend fun getAll(): List<BookSourceEntity>

       @Query("SELECT * FROM book_source WHERE url = :url")
       suspend fun getByUrl(url: String): BookSourceEntity?

       @Query("SELECT * FROM book_source WHERE enabled = 1 ORDER BY weight ASC")
       suspend fun getEnabled(): List<BookSourceEntity>

       @Insert(onConflict = OnConflictStrategy.REPLACE)
       suspend fun upsert(source: BookSourceEntity)

       @Insert(onConflict = OnConflictStrategy.REPLACE)
       suspend fun upsertAll(sources: List<BookSourceEntity>)

       @Query("UPDATE book_source SET enabled = :enabled WHERE url = :url")
       suspend fun setEnabled(url: String, enabled: Boolean)

       @Query("DELETE FROM book_source WHERE url = :url AND is_user_imported = 1")
       suspend fun deleteUserImported(url: String): Int  // 返回受影响行数，默认源删除时返回 0

       @Query("SELECT COUNT(*) FROM book_source")
       suspend fun getCount(): Int
   }
   ```

3. 修改 [`AppDatabase.kt`](../lib_ebook_db/src/main/java/com/ebook/db/AppDatabase.kt)：
   - `entities` 数组加 `BookSourceEntity::class`
   - `version = 4` → `version = 5`
   - 新增 `abstract fun bookSourceDao(): BookSourceDao`

4. 修改 [`DatabaseModule.kt`](../lib_ebook_db/src/main/java/com/ebook/db/di/DatabaseModule.kt)：
   - 新增 `MIGRATION_4_5`（见附录 B）
   - `addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)` → 末尾追加 `MIGRATION_4_5`
   - 新增 `provideBookSourceDao(db)` 方法

5. 编译后 Room 自动生成 `schemas/com.ebook.db.AppDatabase/5.json`，加入 git 提交。

**`lib_book_common`**：

6. 扩展 [`BookSourceManager.kt`](../lib_book_common/src/main/java/com/ebook/common/analyze/source/BookSourceManager.kt) 接口（完整签名见附录 A）：
   - 保留：`currentSource`、`getAllSources()`、`getEnabledSources()`、`switchSource(rule)`、
     `importFromJson(str)`、`exportToJson(rule)`、`saveCurrentSource(context)`
   - **标 `@Deprecated`**：`requireParser()`（兜底为默认源 parser，业务代码逐步迁移）
   - 新增：`getSourceByUrl(url)`、`getParserFor(sourceUrl)`、`addSource(rule)`、`removeSource(url)`、
     `setEnabled(url, enabled)`、`observeSources()`、`observeDefaultSource()`、`setDefaultSource(url)`、
     `searchAcross(keyword, page)`（P3-b 才实现，本阶段可先声明为 `TODO()`）

   > **落地实况**（签名以源码为事实源，本文不维护一份会漂移的副本）：上面这份「保留」清单里
   > `switchSource(rule)` 与 `saveCurrentSource(context)` 都没有留下——前者与书架换源那条事务同名、
   > 两个「换源」共用一个词，后者职责由 `setDefaultSource` 承担；`importFromJson` / `exportToJson`
   > 作为不落状态的纯函数保留。其余四点与本处方不同：
   > ① `getAllSources` / `getEnabledSources` / `getSourceByUrl` / `getParserFor` 与全部增删改**都是 suspend**
   > （事实源在 Room，同步方法只能靠 `runBlocking` 堵主线程）；同步面只剩 `currentSource` 一个成员；
   > ② `searchAcross` 多出 `skipSourceUrls` 入参（Manager 不持有搜索会话状态，「哪些源已到底」由调用方带）；
   > ③ `observeSources()` 发的是 `BookSourceItem`（规则 + `isUserImported`），不是裸 `BookSourceRule`；
   > ④ `requireParser()` 没标废弃而是删除（见本节开头的实况）。

7. 重写 [`BookSourceManagerImpl.kt`](../lib_book_common/src/main/java/com/ebook/common/analyze/source/BookSourceManagerImpl.kt)：
   - 注入 `BookSourceDao`（构造参数）
   - init 逻辑：
     1. 检查 Room `getCount()`，若为 0 → 从 assets 读 `default_sources.json` → `upsertAll(isUserImported=false)`
     2. 从 SharedPreferences 读 `KEY_CURRENT_SOURCE`，若不存在则用 Room 中 `weight` 最小的启用源
     3. `switchSource(defaultSource)`（内部用，兜底给 `requireParser()`）
   - 内部维护 `parserCache: LinkedHashMap<String, BookParser>`（LRU，容量 3）
   - `getParserFor(url)`：先查缓存 → 未命中则 `getSourceByUrl(url)` → 构建 `JsoupBookParser` → 存缓存
   - `addSource/removeSource/setEnabled` 全部走 Room（同步方法用 `runBlocking`，或改 suspend 由 VM 层调度）
     **决策**：改 suspend 更契合协程架构，接口相应改成 `suspend fun addSource(...)`
   - `observeSources/observeDefaultSource` 直接返回 Room 的 Flow 映射（`rule_json` → `BookSourceRule`）

   > **落地实况**（这三点原处方没有）：
   > ① 灌库与默认源回填**不在构造里同步跑**：构造只同步读 assets 得到「冷启动快照」（保证
   > `currentSource` 首帧可用、且这一步**不回写 SP**——SP 原值是后续回填唯一的线索），真正的
   > seeding 与回填在后台协程里，暴露成一个可 await 的 `seeding: Deferred`；**所有依赖 Room 的入口
   > （含两个冷流，在收集时等）第一行都 await 它**，于是挂起面永远看不到「未 seed 的空表」，写侧也不再
   > 与灌库抢 TOCTOU（窗口内 `addSource` 一条与内置源同 URL 的规则，会被随后的批量灌库整行 REPLACE、
   > `isUserImported` 被冲回 false——用户导入的源就此变成不可删）。`getParserFor` 的 await 刻意放在
   > 取缓存锁**之前**，否则未命中的请求会把已缓存的源一起排在一次 Room 冷开之后。
   > ② 第 3 步那句「兜底给 `requireParser()`」随兜底出口一并失效：实际是把「规则 + 它的 parser」
   > 装进同一个不可变持有者、一次赋值换掉整对（拆成两个字段会留错配窗口）。
   > ③ 「有没有可用默认源」在同步面与订阅面**必须同为 null**（用户把源全禁用时），否则书城首帧还在解析
   > 一个已被全部禁掉的源；`setEnabled` 因此反向承担提升职责——无可用默认源时启用任意一条源就让它顶上。

8. 修改 [`AnalyzeModule.kt`](../lib_book_common/src/main/java/com/ebook/common/di/AnalyzeModule.kt)：无需改动，
   `@Binds` 已覆盖新构造参数（Hilt 自动注入 `BookSourceDao`）。

9. 新增 [`BookSourceNotFoundException.kt`](../lib_book_source/src/main/java/com/ebook/source/analyze/BookSourceNotFoundException.kt)：
   ```kotlin
   /** 书源已被用户删除或禁用，无法为 entity.tag 找到对应 parser 时抛出 */
   class BookSourceNotFoundException(val sourceUrl: String) :
       IllegalStateException("书源已失效：$sourceUrl")
   ```

   > **落地实况**：真实签名多一个可选入参 `detail: String? = null`（现场描述，如 `bookId=xxx`——
   > `sourceUrl` 为空白时它本身指不出是哪本书，日志需要锚点）；且**空白 `sourceUrl` 的消息换了一句人话**
   > （「这本书没有书源信息」）：空白不是「源被删了」，而是网络书压根没有归属（脏数据），照原写法会在
   > 冒号后空无一物。消息只用于排查、从不上屏——上屏文案一律走各模块的字符串资源。
   > 「禁用不切断归属」也与本注释相反：禁用中的源仍能被自己那本书解析到（见 `setEnabled` 的 KDoc）。
   > 位置也与处方不同：本类最终不住 `lib_book_common`，而随原生解析器一族迁进 `lib_book_source` 的
   > `com.ebook.source.analyze`（书源解析专用层独立成模块时搬的），上方链接已按实况指向那里。

10. 新增 [`AggregateSearchEvent.kt`](../lib_book_source/src/main/java/com/ebook/source/analyze/AggregateSearchEvent.kt)（P3-b 使用，本阶段仅定义 sealed class 骨架）。
    落地位置同上条：随解析器一族在 `lib_book_source` 的 `com.ebook.source.analyze`。

**测试**：

11. 新增 `lib_ebook_db/src/test/java/com/ebook/db/dao/BookSourceDaoTest.kt`：
    - 覆盖 upsert / getByUrl / setEnabled / deleteUserImported（默认源删除返回 0）/ observeAll 排序
12. 新增 `lib_book_common/src/test/java/com/ebook/common/analyze/source/BookSourceManagerImplTest.kt`：
    - 覆盖 init 首次加载 assets、init 二次加载 Room、`getParserFor` LRU 淘汰、
      `addSource` 同 URL 覆盖（REPLACE，见校准记录第 4 条）、`removeSource` 默认源拒绝

### 验证方式

- `./gradlew :lib_ebook_db:testDebugUnitTest` — DAO 测试全绿
- `./gradlew :lib_book_common:testDebugUnitTest` — Manager 测试全绿
- `./gradlew :module_app:assembleRealDebug` — 编译通过
- **人工装机验证**（P1 阶段用户无感知，验证目的是确认无回归）：
  1. 覆盖安装（保留数据）→ 打开 App → 书架/搜索/书城/阅读/下载**全部功能正常**
  2. 全新安装 → 打开 App → 搜索一本书 → 加入书架 → 阅读 → 下载 → **全部功能正常**
  3. `adb shell run-as com.ebook sqlite3 databases/ebook_db ".schema book_source"` 确认表已创建
  4. `adb shell run-as com.ebook sqlite3 databases/ebook_db "SELECT url, name, is_user_imported FROM book_source"` 确认默认源已入库

### 提交拆分

按 Conventional Commits：
```
feat(lib_ebook_db): 新增 BookSourceEntity 与 DAO 支持多书源持久化

- Room schema v4 → v5，新增 book_source 表存书源规则
- BookSourceDao 提供 CRUD 与启用状态更新，默认源删除保护
- 迁移仅 CREATE TABLE，无破坏性变更

Refs: ADR-0016
```
```
refactor(lib_book_common): BookSourceManager 支持按 URL 查 parser

- getParserFor/addSource/removeSource/setEnabled/observeSources 全部走 Room
- 内部 LRU 缓存 3 个 parser 实例，避免每次解析都重建
- requireParser 与 currentParser 在调用点归零后从接口与实现删除（P3-a/P3-b 完成）
- assets 默认源在首启由后台 seeding 写入 Room，挂起入口先 await 该次 seeding；此后 Room 是唯一事实源

Refs: ADR-0016
```

### 可发布状态
P1 完成后 App 行为对用户**完全一致**（当时靠 `requireParser()` 兜底为默认源 parser；该出口后来随调用点
归零被删除，见本节开头的实况），可安全合入 develop。

---

## P2 · module_me 书源设置页

### 目标
用户能在设置页看到书源列表、导入 JSON、导出、启用/禁用、删除用户导入源。

### 前置依赖
P1 完成。

### 具体改动清单

**`lib_book_common`**：

1. 修改 [`KeyCode.kt`](../lib_book_common/src/main/java/com/ebook/common/event/KeyCode.kt) `Me` 伴生对象新增：
   ```kotlin
   /** 书源管理页（列表/导入/导出/启用/删除） */
   const val BOOK_SOURCE_PATH = BASE_PATH + "book_source"
   ```

**`module_me`**：

2. 新增 [`BookSourceViewModel.kt`](../module_me/src/main/java/com/ebook/me/mvvm/viewmodel/BookSourceViewModel.kt)：
   ```kotlin
   data class BookSourceUiState(
       val sources: List<BookSourceRule> = emptyList(),
       val defaultSourceUrl: String? = null,
       val importPreview: List<ImportPreviewItem>? = null,  // 导入预览弹层数据
       val toast: String? = null,
   )
   data class ImportPreviewItem(
       val rule: BookSourceRule,
       val validation: ValidationResult,  // Valid / Invalid(reason) / WillOverwrite
   )

   @HiltViewModel
   class BookSourceViewModel @Inject constructor(
       private val bookSourceManager: BookSourceManager,
   ) : ViewModel() {
       val uiState: StateFlow<BookSourceUiState> = combine(
           bookSourceManager.observeSources(),
           bookSourceManager.observeDefaultSource(),
       ) { sources, defaultSource -> ... }.stateIn(...)

       fun setDefault(url: String) { ... }
       fun setEnabled(url: String, enabled: Boolean) { viewModelScope.launch { ... } }
       fun removeSource(url: String) { viewModelScope.launch { ... } }
       fun parseImportJson(jsonStr: String): List<ImportPreviewItem> { ... }  // 结构校验，不落库
       fun confirmImport(items: List<ImportPreviewItem>) { viewModelScope.launch { ... } }
       fun exportAll(): String { ... }  // 返回 JSON 字符串，UI 层负责写文件
   }
   ```

   > **落地实况**：三点与本片段不同——① 它**不**直继 `androidx.lifecycle.ViewModel`，而是
   > `BaseViewModel<NoOpModel>(NoOpModel())`（本仓约定：纯展示页也要走基类，无 Model 门面用
   > `NoOpModel` 占位，否则日后接 `BaseMvvmActivity` / 一次性命令通道时要连带改页面）；
   > ② 状态流叫 `bookSourceState: StateFlow<BookSourcePageState>`，**不叫 `uiState`**——那个名字在本仓
   > 是基类覆盖层专用；导入预览（`_preview`）与提示（`_notice`）各占一份独立状态，不塞进一个大 UiState；
   > ③ `ImportPreviewItem` 是 VM 的嵌套类型（它不出现在 VM 之外，没有独立成顶层类的必要）。

3. 新增 [`BookSourceManageActivity.kt`](../module_me/src/main/java/com/ebook/me/view/BookSourceManageActivity.kt)：
   - 继承 `BaseMvvmActivity<BookSourceViewModel>`
   - `@Route(path = KeyCode.Me.BOOK_SOURCE_PATH)`
   - Toolbar 标题「书源管理」+ 右上角两个 IconButton（导入/导出）
   - `PageContent`：
     - 顶部 `SectionLabel`「当前书源 X 个，已启用 Y 个」
     - `LazyColumn` 列表，每项 `CommonListItem`：
       - 图标：`Icons.Outlined.Source`（或 `Icons.Outlined.MenuBook`）
       - 标题：书源名
       - 副标题：URL
       - `trailingContent`：`Switch`（启用/禁用）+ 标记 `InfoChip`「默认」与「内置」（可同时命中，
         内置身份取自清单条目 `BookSourceItem.isUserImported`）
       - 长按弹层：设为默认 / 导出单个 / 删除（内置源的「删除」项置灰不可点并在行尾给解释；
         `removeSource` 的拒绝路径同时保留作第二道防线）
     - 空态：`NoDataView`「暂无书源，点击右上角导入」

4. 新增导入流程（SAF）：
   ```kotlin
   val openDocumentLauncher = rememberLauncherForActivityResult(
       ActivityResultContracts.OpenDocument()
   ) { uri -> uri?.let { viewModel.readAndParse(it) } }
   // 点击「导入」→ openDocumentLauncher.launch(arrayOf("application/json", "text/plain"))
   ```
   - 读文件用 `context.contentResolver.openInputStream(uri)`
   - 解析成功 → 弹预览层（`ModalBottomSheet` 展示待导入清单，每项标校验结果）
   - 用户点「确认导入」→ `viewModel.confirmImport(...)` → Toast 反馈「导入成功 X 条 / 覆盖 Y 条 / 失败 Z 条」

5. 新增导出流程（SAF）：
   ```kotlin
   val createDocumentLauncher = rememberLauncherForActivityResult(
       ActivityResultContracts.CreateDocument("application/json")
   ) { uri -> uri?.let { viewModel.writeExport(it) } }
   // 点击「导出」→ createDocumentLauncher.launch("book_sources_${yyyyMMdd}.json")
   ```

6. 新增导入预览弹层组件（`ImportPreviewSheet`，Compose `ModalBottomSheet`）：
   - 每行展示：书源名 / URL / 校验结果图标（绿勾/红叉/黄警告「将覆盖」）
   - 底部按钮：「取消」/「确认导入 N 条」

7. **结构校验规则**（放在 `BookSourceValidator` 单独类，方便单测）：
   - `name` 非空
   - `url` 匹配 `^https?://` 前缀
   - `searchUrl` 或 `ruleFind.url` 至少一个非空（否则书源无法完成搜索/书城任一核心链路）
   - `ruleSearch.list` 与 `ruleContent.content` 均非空（否则书源无法解析搜索结果与正文）
   - 校验失败返回具体原因（供预览层展示）

8. 修改 [`SettingActivity.kt`](../module_me/src/main/java/com/ebook/me/view/SettingActivity.kt) 通用组：
   - 在「清除缓存」下方新增「书源管理」入口行
   - `trailingText` 显示当前书源数（`viewModel.sourcesCount.collectAsState()`）
   - 点击跳 `KeyCode.Me.BOOK_SOURCE_PATH`
   - `SettingViewModel` 加 `sourcesCount: StateFlow<Int>`（订阅 `observeSources().map { it.size }`）

9. 新增字符串资源 [`module_me/src/main/res/values/strings.xml`](../module_me/src/main/res/values/strings.xml)：
   - `book_source_title`、`book_source_import`、`book_source_export`、`book_source_empty`
   - `book_source_import_preview_title`、`book_source_import_confirm`
   - `book_source_import_success`（含占位符 `%1$d/%2$d/%3$d`）
   - `book_source_validation_*`（每个校验规则对应一条错误文案）
   - `book_source_default_badge`、`book_source_set_default`、`book_source_delete_confirm`
   - `setting_book_source_entry`（设置页入口标题）

**测试**：

10. 新增 `module_me/src/test/java/com/ebook/me/mvvm/viewmodel/BookSourceViewModelTest.kt`：
    - 覆盖 `parseImportJson` 单条/数组自适应、结构校验各分支、`confirmImport` 冲突覆盖
11. 新增 `module_me/src/test/java/com/ebook/me/domain/BookSourceValidatorTest.kt`：
    - 覆盖每个校验规则的通过/失败分支

### 验证方式

- `./gradlew :module_me:testDebugUnitTest` — VM 与 Validator 测试全绿
- `./gradlew :module_app:assembleRealDebug` — 编译通过
- **人工装机验证**：
  1. 打开「我的」→「设置」→ 确认「书源管理」入口行存在，副标题显示「1 个书源」
  2. 点入书源管理页 → 确认列表显示默认源「笔趣阁」，右侧「默认」+「内置」两个标记可见，Switch 处于开启状态
  3. 点开内置源所在行 → 确认操作层里「删除」项置灰不可点、行尾显示「内置源不可删除，可禁用」
  4. 点击右上角「导入」→ 从手机选一份合法的书源 JSON → 确认预览层显示校验通过 → 确认导入 → Toast「导入成功 1 条」→ 列表刷新出现新书源
  5. 导入一份**非法** JSON（缺 `ruleContent.content`）→ 预览层显示红叉 + 具体原因 → 确认导入被拒绝或跳过
  6. 导入一份**已存在** URL 的 JSON → 预览层显示黄警告「将覆盖」→ 确认后原书源被覆盖
  7. 点击「导出」→ 选择保存位置 → 确认导出的 JSON 文件可被再次导入（往返一致）
  8. 长按用户导入源 → 「删除」→ 确认弹层 → 列表刷新，该书源消失
  9. Switch 关闭某个用户源 → 重启 App → 确认状态被持久化

### 提交拆分
```
feat(module_me): 新增书源管理页支持导入导出与启用禁用

- BookSourceManageActivity 展示书源列表，长按弹层管理默认/删除
- SAF OpenDocument 导入 JSON，结构校验 + 预览确认后落 Room
- SAF CreateDocument 导出全部书源，文件名带日期
- SettingActivity 通用组新增入口，副标题显示书源总数

Refs: ADR-0016
```

### 可发布状态
P2 完成后用户能管理书源，但业务层（搜索/阅读/下载）仍走默认源。多书源共存尚未生效，属**渐进式发布**中间态。

---

## P3-a · 书架/阅读器/下载按 tag 走独立 parser

### 目标
8 处 `bookSourceManager.requireParser()` 全部改造为按 `tag` 取 parser；
多书源共存在数据流层生效（用户从书源 A 加的书 + 从书源 B 加的书可同时存在于书架，各自走独立 parser）。

> **落地实况**：8 处调用点已全部迁到「按归属取源」，`requireParser()` / `currentParser` 随之**删除**。
> 其中 `SearchViewModel` 那一处按原计划留到 P3-b，但两个阶段落在同一条未提交的分支里，
> 所以本文几处「过渡期继续用 `requireParser()` 兜底」的说法都没有对应的真实状态。

现状盘点（2026-09-07 重新 grep，共 8 处）：`BookShelfManager`×2、`BookDetailViewModel`×2、
`JsoupSourceReader.fetchAndStore`×1、`SearchViewModel`×1（P3-b 改聚合搜索）、`BookSourceRepository`×2。

### 前置依赖
P1 完成（P2 可选，用户没有导入其他书源时 P3-a 无感知）。

### 具体改动清单

**`lib_book_common`**：

1. 修改 [`BookShelfManager.kt`](../lib_book_common/src/main/java/com/ebook/common/manager/BookShelfManager.kt)：
   - `addFromSearch(searchBook)`：
     ```kotlin
     val parser = bookSourceManager.getParserFor(searchBook.tag)
         ?: throw BookSourceNotFoundException(searchBook.tag)
     val bookInfo = parser.getBookInfo(shelf)
     val chapterResult = parser.getChapterList(bookInfo)
     ```
   - 保留 `markShelfStatus` 不变（按 `noteUrl` 匹配已足够，多书源下 `noteUrl` 全局唯一）

2. 正文路径按书绑源（取代旧稿的「`BookRepository.loadBookContent` 加 `sourceTag` 参数」——该方法与
   `book_content` 表已在 v3→v4 迁移中一并删除，正文现由 `chapterReaders[BookFormat].readChapter(entry, location)` 供）：
   - `BookLocation` 加 `sourceUrl` 字段。它是**书级**定位值类型（`bookId` + `format`），
     书源归属同样是书级属性，放这里 `readChapter` 签名不动；本地书两支一律传 `BookShelfEntity.LOCAL_TAG`
     （`getParserFor` 对它短路返回 null，且本地书按 `format` 路由到 TXT/EPUB reader，取不到源也走不到网络解析，
     传空串与传 `LOCAL_TAG` 对 reader **不等价**：`LOCAL_TAG` 是编程错误（本地书压根没有源可导），
     空白则是网络书的脏数据（报成可展示的「这本书没有书源信息」），判据见下方「统一异常处理」第 8 条；
     详见 `BookLocation` 的 KDoc）
   - `BookRepository` 的 `loadChapter`/`refreshChapter`/`getCachedChapterIndices` 构造 `BookLocation`
     时传 `bookShelf.tag`
   - `JsoupSourceReader.fetchAndStore` 改为 `getParserFor(location.sourceUrl)`，取不到抛
     `BookSourceNotFoundException`（不再 `requireParser() as? JsoupBookParser`）
   - `DownloadService.downloading` 与 `DownloadRepository` 构造 `BookLocation` 时传
     `DownloadChapterEntity.tag`（该列已存在，值即书源 URL）

**`module_book`**：

3. 修改 [`BookDetailViewModel.kt`](../module_book/src/main/java/com/ebook/book/mvvm/viewmodel/BookDetailViewModel.kt)
   （2026-09-08 按实名更正：两个私有方法叫 `fetchBookInfo` / `fetchChapterList`，不叫 `refreshBook`/`refreshChapterList`）：
   - `fetchBookInfo(searchBook)`：`getParserFor(searchBook.tag)` 取不到即抛 `BookSourceNotFoundException`
   - `fetchChapterList(bookShelf)`：同上按 `bookShelf.tag`
   - 两条都 catch 该异常 → `reportFailure` 提示 + 返回 null，调用方置 `loading=false, loadError=true`

4. ~~修改 `BookReadViewModel.kt`：调 `bookRepository.loadBookContent(url, bookShelf.tag)`~~
   （2026-09-08 更正：`loadBookContent` 与 `book_content` 表已在那轮 v3→v4 迁移中一并删除，
   阅读器现经 `BookRepository.loadChapter(bookShelf, ...)` 取正文——整条 `bookShelf` 就在手上，
   归属由 `BookLocation.sourceUrl` 承载，**该 VM 无需改动**）

5. 修改 [`DownloadService.kt`](../module_book/src/main/java/com/ebook/book/service/DownloadService.kt)：
   - 服务不直调 parser：`downloading` 构造 `BookLocation(noteUrl, NETWORK, data.tag)` 交给
     `JsoupSourceReader`，由它按 `location.sourceUrl` 取源（`data.tag` 即 `DownloadChapterEntity.tag`）
   - `BookSourceNotFoundException` 走**既有的普通失败通道**：重试计数递增 → 耗尽后出队 →
     计入 `skippedCount` → 收尾文案说明跳过 N 章
   - ~~不删任务，等用户重新导入书源后恢复下载~~（2026-09-08 **否决**，本条与 P3-a 处方直接冲突）：
     本仓有一条铁律是「**失败章必须在重试耗尽后出队**」——`findNextDownloadChapter` 取的是队头，
     一个当下注定失败的队头会让服务在同一章上无限重试（常驻通知不消失、这本书后续章节全被阻塞）。
     结论：书源失效**不加特例**，按普通失败走完那条通道（`skippedCount` 把跳过章数带进收尾文案，
     不静默丢章）；用户重导书源后重新发起下载即可续跑。唯一保留的分支是**暂停**（`isStartDownload`
     置 false）中断重试时**不出队**——那不是失败，而是任务待续跑。

**`module_find`**：

6. 修改 [`BookSourceRepository.kt`](../module_find/src/main/java/com/ebook/find/repository/BookSourceRepository.kt)：
   - `getKindBook(url, page)`：`getParserFor(currentSource.url)` （书城仍用默认书源）
   - `getLibraryData()`：同上
   - `currentSource` 为空时返回空数据，UI 展示「请先在设置中导入书源」

   > **落地实况**：三条处方都要改——① 仓库的**三个**方法（`getBookTypeList` / `getKindBook` /
   > `getLibraryData`）一律以 `sourceUrl` 为入参、**不自己读全局默认源**：顶部切换器要浏览的恰恰
   > 可能不是默认源，仓库去读全局值等于把切换器的一半语义吞掉；
   > ② **「没有源」与「有源但那个源坏了」必须分开**：前者（`sourceUrl` 空白 / 无启用源）返回空数据、
   > 页面进引导态且不发请求，后者抛 `BookSourceNotFoundException`——否则一条坏源会以「这个源没书」
   > 的样子骗过用户；③ 引导文案实况是「还没有可用书源 / 请先在书源管理中启用或导入书源」，入口是
   > `module_me` 的书源管理页而非「设置中导入书源」；且该引导态**刻意不给「去书源管理」按钮**——
   > 那条路由属于另一个模块，独立运行时 TheRouter 找不到路由只记一行日志，按钮会变成第二处「点了没反应」。

7. **暂不改** [`SearchViewModel.kt`](../module_find/src/main/java/com/ebook/find/mvvm/viewmodel/SearchViewModel.kt)（P3-b 处理）。
   过渡期：`searchBook` 保持 `requireParser()`，兜底走默认源，避免 P3-a 影响搜索。
   （2026-09-08 实况：这个过渡期从未真实存在——P3-b 与 P3-a 落在同一条未提交分支里，
   `SearchViewModel` 直接改成消费 `searchAcross`，`requireParser()` 也在那一步被删除。）

**统一异常处理**：

8. 所有 `getParserFor` 调用点处理 null（2026-09-08 按落地实况更正口径）：
   - ViewModel 层：catch `BookSourceNotFoundException` → **经 `lib_book_common` 的 `reportFailure` 上报**
     （它内部只记日志、不重复提示，禁止再手写 `isSessionExpiredHandled` 分支或在它之外补 Toast）
     + 收掉 loading/Overlay 并进错误态；文案「书源已失效，请重新导入或换源」放各模块 `res/values/strings.xml`
     （异常自身消息形如「书源已失效：<URL>」，不适合直接上屏）
   - Service 层（DownloadService）：复用既有失败通道（重试 → 出队 → `skippedCount`），不加特例
   - Repository 层：向上抛，由 VM 层统一处置
   - 阅读器正文（`ReadBookActivity.loadPage`）：取正文在 Activity 里做，没有 VM 钩子可挂，
     故 catch 该异常后经**页面自己的 viewModel** 调 `reportFailure`（文案仍走基类命令通道），
     其余异常照旧静默降级成错误页

   落地时额外定下的两条（原清单没有）：
   - `BookLocation.sourceUrl` **刻意不给默认值**——有默认值就等于允许新构造点忘记表态，而漏传是静默的
     （正文会退回默认源去抓），编译器逐个点名 15 处构造点才是想要的迁移方式
   - 「没有归属」要按**用户能不能处置**拆成两支，而不是一概当编程错误：`loc_book` 走到网络 reader 是
     接线 bug（reader 按格式路由，本地书压根走不到取源），报成「书源已失效」会把数据 bug 转嫁成用户操作；
     而**网络书的 `tag` 为空白是脏数据**（历史行/导入链漏传），代码改不动它，用户能做的仍与「源被删」相同，
     故抛 `BookSourceNotFoundException`（消息「这本书没有书源信息」）。此前两支合并报编程错误，
     后果是阅读正文这条主路径把可展示的失败按「不该发生」吞掉，只剩一行日志（ADR-0016 的提示承诺落空）

### 验证方式

- `./gradlew :module_app:assembleRealDebug` — 编译通过
- `./gradlew test` — 全模块单测通过（新增/修改的 VM 测试覆盖 parser 为空的分支）
- **人工装机验证**（关键：多书源共存的核心验证）：
  1. 从书源 A（默认笔趣阁）搜索并加入书架一本书 X
  2. 在书源管理页导入书源 B（准备一份可用的 JSON，如另一站点）
  3. 在书源管理页将书源 B 设为默认
  4. 从书源 B 搜索并加入书架一本书 Y
  5. 打开书架 → 确认书 X 和书 Y 都在
  6. 点开书 X → **确认能正常打开、章节列表能加载、正文能阅读**（走书源 A parser）
  7. 点开书 Y → **确认能正常打开、章节列表能加载、正文能阅读**（走书源 B parser）
  8. 书架长按书 X → 「刷新」→ 确认刷新走书源 A（章节更新正常）
  9. 书架长按书 Y → 「刷新」→ 确认刷新走书源 B
  10. 阅读器下载书 X 的几章 → 确认下载任务走书源 A parser
  11. 阅读器下载书 Y 的几章 → 确认下载任务走书源 B parser
  12. 在书源管理页**删除书源 B** → 打开书 Y → 确认弹提示「书源已失效，请重新导入或换源」
  13. 重新导入书源 B → 打开书 Y → 确认恢复正常

### 提交拆分
```
refactor(all): 书源解析按 tag 走独立 parser 支持多书源共存

- BookShelfManager/BookDetailViewModel/JsoupSourceReader 改用 getParserFor(tag)
- DownloadService 按下载任务的 tag 找 parser，书源失效走既有失败通道（重试耗尽后出队并计入跳过章数）
- BookLocation 加 sourceUrl，正文抓取按书绑源而非按全局当前源
- 找不到 parser 时抛 BookSourceNotFoundException，UI 提示重新导入或换源
- 搜索路径改由 P3-b 的聚合搜索承接，requireParser/currentParser 在调用点归零后删除

Refs: ADR-0016
```

### 可发布状态
P3-a 完成后多书源共存在数据流层完全生效，是**用户真正感知到「多书源」的第一个里程碑**。

---

## P3-b · 聚合搜索

### 目标
`SearchViewModel` 从单书源分页改为多书源并发聚合搜索；每条结果自带书源标签；某书源失败不影响其他书源。

### 前置依赖
P3-a 完成。

### 具体改动清单

**`lib_book_common`**：

1. 实现 `BookSourceManager.searchAcross(keyword, page): Flow<AggregateSearchEvent>`：
   ```kotlin
   sealed class AggregateSearchEvent {
       data class SourceStarted(val sourceUrl: String, val sourceName: String) : AggregateSearchEvent()
       data class SourceResult(val sourceUrl: String, val books: List<SearchBookEntity>) : AggregateSearchEvent()
       data class SourceFailed(val sourceUrl: String, val error: Throwable) : AggregateSearchEvent()
       data class SourceFinished(val sourceUrl: String, val hasMore: Boolean) : AggregateSearchEvent()
       data object AllFinished : AggregateSearchEvent()
   }

   fun searchAcross(keyword: String, page: Int): Flow<AggregateSearchEvent> = flow {
       val sources = getEnabledSources()
       emitAll(sources.asFlow().flatMapMerge(concurrency = 5) { rule ->
           flow {
               emit(AggregateSearchEvent.SourceStarted(rule.url, rule.name))
               try {
                   val parser = getParserFor(rule.url)!!
                   val books = parser.searchBook(keyword, page)
                   emit(AggregateSearchEvent.SourceResult(rule.url, books))
                   emit(AggregateSearchEvent.SourceFinished(rule.url, books.isNotEmpty()))
               } catch (e: Throwable) {
                   emit(AggregateSearchEvent.SourceFailed(rule.url, e))
                   emit(AggregateSearchEvent.SourceFinished(rule.url, false))
               }
           }
       })
       emit(AggregateSearchEvent.AllFinished)
   }
   ```

   > **落地实况**（四处与本片段不同，理由都在代码 KDoc 里）：
   > ① 签名是 `searchAcross(keyword, page, skipSourceUrls: Set<String> = emptySet())`——Manager 不持有
   > 搜索会话状态，「哪些源已到底」只有调用方知道，不带进来就会对已结束的源重复发请求；
   > ② `getParserFor(rule.url)!!` 这个写法被否掉：取不到源（源被删/规则解不出）是**该源的失败**，
   > `!!` 抛出的 NPE 会沿 `flatMapMerge` 冒到整条聚合流，一条坏源让全站搜索结果一起消失。
   > 实况是当作该源的失败发 `SourceFailed` + `SourceFinished(hasMore = false)`；
   > ③ 单源异常一律收敛成事件，**但 `CancellationException` 原样上抛**（咽掉它会让旧轮次继续往新结果里灌）；
   > ④ 由此推出收尾判据：**某一路被取消时 `flatMapMerge` 丢弃整路、该源连 `SourceFinished` 都不发**，
   > 所以「本轮结束了没有」只能以 `AllFinished` 为准，数「Finished == Started」会让进度永远差一格。

**`module_find`**：

2. 重写 [`SearchViewModel.kt`](../module_find/src/main/java/com/ebook/find/mvvm/viewmodel/SearchViewModel.kt)：
   - 新增 `pageBySource: MutableMap<String, Int>`（每源独立分页游标）
   - 新增 `finishedSources: MutableSet<String>`（本轮已完成的书源）
   - `toSearchBooks(content)`：清空 `pageBySource`/`finishedSources`，触发聚合搜索
   - `loadMore()`：只对 `hasMore=true` 的书源触发下一页（不重置全部）
   - `searchBook(content)` 改成消费 `searchAcross(...)`：
     - `SourceResult(url, books)` → `markShelfStatus` → 追加到 `list.value`（按 noteUrl 去重）
     - `SourceFinished(url, hasMore)` → `pageBySource[url]++`；`hasMore=false` 时加入 `finishedSources`
     - `AllFinished` → `updateOverlay(Overlay.None)` + 收掉本轮进度
       （2026-09-08 更正：原处方在此比较「`finishedSources.size == sources.size`」，与落地契约冲突——
       收尾只看 `AllFinished` 这个事件本身；`finishedSources` 是**跨轮累积**的排除集（下一轮经
       `skipSourceUrls` 排除），不是本轮计数。而「某源到底」的判据也不是「返回空页」：越界页会以
       HTTP 200 重复返回首页书目（软 404），故按 `noteUrl` 去重后「这一页没带来新条目」才算到底）
   - **UI 反馈**：新增 `searchProgress: StateFlow<Pair<Int, Int>>`（已完成书源数 / 总书源数），供 UI 渲染进度条

3. 修改搜索结果 UI（`SearchActivity` / `SearchScreen`）：
   - 列表项 `SearchBookEntity.origin` 已存书源名（`JsoupBookParser.parseSearchBookWithRule` 已写入），直接在 UI 显示
   - 用 [`InfoChip`](../lib_book_common/src/main/java/com/ebook/common/ui) 展示 `origin`
   - 顶部搜索进度条（`LinearProgressIndicator` + 文案「已收到 X/Y 书源结果」）

**测试**：

4. 新增/扩展 `BookSourceManagerImplTest`：
   - 覆盖 `searchAcross` 并发聚合、某源失败不阻塞其他源、去重按 noteUrl
5. 新增 `SearchViewModelTest`：
   - 覆盖 `pageBySource` 独立游标、`loadMore` 只翻未完成的源、`searchProgress` 递增

### 验证方式

- `./gradlew :module_find:testDebugUnitTest` — 全绿
- `./gradlew :module_app:assembleRealDebug` — 编译通过
- **人工装机验证**：
  1. 至少导入 2 个可用书源（默认笔趣阁 + 另一个）
  2. 打开搜索页 → 搜一个常见关键词（如「都市」）
  3. **观察进度条**：确认「已收到 X/Y 书源结果」逐步递增
  4. **观察结果列表**：确认结果项右侧显示书源名标签（`InfoChip`）
  5. **观察结果来源多样性**：确认结果同时包含两个书源的书籍（不同 noteUrl 前缀）
  6. **上拉加载更多**：确认新结果继续追加，进度条重新走一遍
  7. **禁用一个书源**（在书源管理页 Switch 关闭）→ 重新搜索 → 确认结果只来自启用的书源，进度条 Y 值 -1
  8. **模拟书源失败**（断开网络或导入一个 URL 无效的书源）→ 搜索 → 确认失败书源不阻塞其他书源结果展示
  9. **点击结果加入书架** → 确认 `tag` 是**该书所属书源的 URL**（不是当前默认源）→ 打开书架点开这本书 → 确认能正常阅读

### 提交拆分
```
feat(module_find): 搜索改为多书源并发聚合

- BookSourceManager.searchAcross 用 flatMapMerge concurrency=5 并发所有启用书源
- 每源独立分页游标，某源失败不阻塞其他源，某源返回空只标记本源 hasMore=false
- 结果按 noteUrl 全局去重，同名不同源的书保留作为换源备选
- SearchViewModel 新增 searchProgress 供 UI 渲染进度条
- 结果项右侧用 InfoChip 展示书源名（origin 字段已由解析器写入）

Refs: ADR-0016
```

### 可发布状态
P3-b 完成后搜索体验大幅升级（一次搜全站），是**用户感知最强的功能点**。

---

## P3-c · 书城顶部书源切换器

### 目标
书城 Tab 顶部加书源切换胶囊，切换后重新拉当前书源的书库数据；缓存按源分区。

### 前置依赖
P1 完成（可与 P3-a/P3-b 并行）。

### 具体改动清单

**`lib_book_common`**：

1. 修改 `JsoupBookParser.getLibraryData(aCache)`：
   - 缓存 key 从 `LIBRARY_CACHE_KEY` 改为 `"$LIBRARY_CACHE_KEY:${rule.url}"`
   - 或将 key 作为参数传入：`getLibraryData(aCache, cacheKey)`（更灵活）
   - **决策**：改参数化 cache key，`BookSourceRepository` 传入
   - **落地实况**：签名取「传 `sourceUrl`」这一支（`getLibraryData(aCache, sourceUrl)`），
     但 key 的拼装既不留在解析器也不交给仓库，而是收成 `lib_book_common` 的
     `libraryCacheKey(sourceUrl)` 一处——两侧各拼一次时写法稍有差别就变成「写完永远读不到」，
     表现为每次进书城都重拉网络而功能上看不出毛病。测试见 `LibraryCacheKeyTest`。

**`module_find`**：

2. 修改 [`LibraryViewModel.kt`](../module_find/src/main/java/com/ebook/find/mvvm/viewmodel/LibraryViewModel.kt)：
   ```kotlin
   val sources: StateFlow<List<BookSourceRule>> = bookSourceManager.observeSources()
       .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

   val currentSource: StateFlow<BookSourceRule?> = bookSourceManager.observeDefaultSource()
       .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

   fun switchSource(url: String) { viewModelScope.launch { bookSourceManager.setDefaultSource(url) } }

   override fun refreshData() {
       viewModelScope.launch {
           // 依赖 currentSource.value 拉对应书源数据
           val source = currentSource.value ?: return@launch
           val value = bookSourceRepository.getLibraryData(source.url)
           ...
       }
   }
   ```
   - `bookTypeList` 从「一次性同步读」改为「响应 `currentSource` 变化的 StateFlow」
   - **落地实况**：两条流都用 `SharingStarted.Eagerly` 而不是 `WhileSubscribed(5_000)`——书城是主
     Tab，VM 与宿主同生命周期，且加载管线（`init` 里订阅 `currentSource`）不能等 UI 来订阅才启动；
     `currentSource` 的初始值取 Manager 的同步快照，首帧不空白。首屏刷新由这条订阅驱动，
     页面里旧的 `LaunchedEffect(Unit) { refreshData() }` 随之删除（留着会与它并发各拉一次同一个源）。
     `currentSource == null`（用户把源全禁用）时进引导空态「请先在书源管理中启用或导入书源」，
     不发任何请求；「有源但该源坏了」是另一件事（仓库抛 `BookSourceNotFoundException`），
     判据只看这条流、不看加载结果。

3. 修改 [`BookSourceRepository.kt`](../module_find/src/main/java/com/ebook/find/repository/BookSourceRepository.kt)：
   - `getBookTypeList(sourceUrl)`、`getKindBook(sourceUrl, url, page)`、`getLibraryData(sourceUrl)` 都加 sourceUrl 参数
   - 内部 `getParserFor(sourceUrl)` 拿 parser

4. 修改书城 UI（`LibraryScreen` 或 `FindPage`）：
   - 顶部加书源切换胶囊：`CommonCard` + 当前书源名 + 下拉箭头 → 点击弹 `DropdownMenu` 展示所有启用书源
   - 切换后自动触发 `refreshData`
   - `bookTypeList` 响应式渲染（当前书源变化 → 分类胶囊列表刷新）
- **落地实况**：胶囊固定在 `TopAppBar` 下方、不随列表滚动（换源是整页数据的基准，滚走了就纠不回来）；
  形态沿用本仓既有胶囊语言（`RoundedCornerShape(50)` + primaryContainer，`InfoChip` 的内容槽放不下
  箭头故就地拼一行）。**只有一条启用源时照常显示源名，但不画箭头、不挂点击面**——弹一份只有一条
  「已选中」的菜单就是「点了没反应」。**无可用书源时整颗胶囊不渲染**，页面只剩引导态那一句；
  引导态刻意不给「去书源管理」按钮：那条路由属于 `module_me`，模块独立运行时 TheRouter 找不到路由
  只记一行日志，按钮会变成点了没反应的第二处。

### 验证方式

- `./gradlew :module_find:testDebugUnitTest` — 全绿
- **人工装机验证**：
  1. 至少导入 2 个书源，其中一个设为默认
  2. 打开书城 Tab → 确认顶部胶囊显示当前默认书源名
  3. 点击胶囊 → 弹下拉 → 选另一个书源 → 确认胶囊文案更新
  4. 观察书库数据 → 确认分类胶囊与书籍列表刷新为**新书源的**（分类可能不同）
  5. 切回原书源 → 确认书库数据**立即命中缓存**（不重新拉网络，因为按源分区）
  6. 重启 App → 书城仍展示上次选中的书源（SharedPreferences 持久化）
  7. 到书源管理页把**所有**源禁用 → 回书城 Tab：应看到「还没有可用书源 / 请先在书源管理中启用或
     导入书源」，不白屏、不转圈，且 logcat 里没有对第三方站点的请求
  8. 只保留 1 条启用源 → 胶囊仍显示源名、无下拉箭头、点了没反应也不该有涟漪

### 提交拆分
```
feat(module_find): 书城顶部加书源切换器与缓存按源分区

- LibraryViewModel 订阅 observeDefaultSource/observeSources，切换后自动重拉
- 书库缓存 key 按书源分区，生成规则收在 libraryCacheKey(sourceUrl) 一处（读写两侧都从这里取值）
- 顶部胶囊 + DropdownMenu 展示启用书源清单，切换即持久化

Refs: ADR-0016
```

---

## P3-d · 阅读中换源

### 目标
阅读器菜单/目录页新增「换源」入口；用当前书书名+作者跨源聚合搜索；用户选中后重写书架记录，
按章序号映射进度；旧源的**章文件与内存缓存**保留（库内那四张表的行一律删净）。
> **落地实况**：这份「保留」只在**同一次进程内**成立——旧目录此刻正是启动对账眼里的无主目录，
> 下一次启动会被回收（理由见 `BookRepository.switchSource` 的 KDoc）。

### 前置依赖
P3-a、P3-b 完成。

### 具体改动清单

**`lib_book_common`**：

1. `BookRepository` 新增 `switchSource(bookShelf, newSearchBook): Result<BookShelfEntity>`（事务）：
   ```kotlin
   @Transaction
   suspend fun switchSource(
       oldShelf: BookShelfEntity,
       newSearchBook: SearchBookEntity,
   ): Result<BookShelfEntity> = try {
       val newParser = bookSourceManager.getParserFor(newSearchBook.tag)
           ?: return Result.failure(BookSourceNotFoundException(newSearchBook.tag))

       // 1. 记录旧进度
       val oldChapterIndex = oldShelf.durChapter

       // 2. 用新 parser 拉详情+目录
       val newShelfStub = BookShelfEntity().apply {
           noteUrl = newSearchBook.noteUrl
           tag = newSearchBook.tag
       }
       val withInfo = newParser.getBookInfo(newShelfStub)
       val withChapters = newParser.getChapterList(withInfo).data

       // 3. 章序号映射（越界回落到末章）
       val newChapterCount = withChapters.chapterList.size
       val mappedIndex = if (newChapterCount == 0) 0 else minOf(oldChapterIndex, newChapterCount - 1)

       withChapters.apply {
           durChapter = mappedIndex
           durChapterPage = 0  // 页级不跨源
           finalDate = oldShelf.finalDate  // 保留阅读时间
       }

       // 4. 事务：删旧 + 插新 + 章节列表落库（旧章节/内容缓存保留，无冲突）
       bookShelfDao.deleteByUrl(oldShelf.noteUrl)
       bookShelfDao.insert(withChapters)
       chapterListDao.insertAll(withChapters.chapterList)
       bookInfoDao.insert(withChapters.bookInfo!!)

       // 5. 发事件
       _bookShelfEvents.emit(BookShelfEvent.Removed(oldShelf))
       _bookShelfEvents.emit(BookShelfEvent.Added(withChapters))

       Result.success(withChapters)
   } catch (e: Exception) {
       Result.failure(e)
   }
   ```

   > **落地实况**（片段是当时的草案，六点与最终实现不同，逐条理由见 `BookRepository.switchSource` 的 KDoc）：
   > ① 写序是**先插新、后删旧**，不是片段里的「先删后插」——反过来一旦中途失败，这本书就从书架上消失了；
   > ② `durChapterPage` 复位取 `DBCode.BookContentView.DUR_PAGE_INDEX_BEGIN`（不是字面 `0`）；
   > ③ 除了 `finalDate`，还要把 `matchName`/`matchAuthor` 从旧条目带过来——那是用户可能在修键面板上
   > 手工校准过的事实，不带就会让评论桶随换源漂移（用户感知是「我的评论不见了」）；
   > ④ 旧条目的 `book_group` 关联键由新条目**吸收**（`absorbKeysInto`，与 `absorbGroupKeys` 同一套逻辑的
   > 不换线程版本，故能待在同一个写事务里）；删的只是四张表的行，**章文件与内存缓存刻意不删**——
   > 即 `removeFromShelf` 里那两行 `bookStore.deleteBook` / `contentCache.invalidateBook` 在换源路径上不执行；
   > ⑤ 片段外还有两条守卫：本地书（`tag == loc_book`）直接按参数失败返回（本地书没有源可换，
   > 报成「书源已失效」是把正常状态说成故障）；`newBook.noteUrl == oldShelf.noteUrl` 也判失败——
   > 否则「先插新、后删旧」会把刚写进去的那一行删掉，这本书直接从书架上消失；
   > ⑥ 两次网络解析留在事务外，库内写收进**一个** `WriteTransactionRunner` 事务，
   > 书架事件只在提交成功之后发（未提交的写不是事实）。

**`module_book`**：

2. 修改 `ReadBookActivity`：
   - 阅读菜单/目录页新增「换源」按钮（图标 `Icons.Outlined.SwapHoriz`）
   - 点击弹 `SourceSwitchSheet`

   > **落地实况**：入口落在阅读器**顶栏「更多」菜单**里（`ReaderPanels` 的 `ReaderMenuItem`，图标同处方），
   > 且**只对非本地书出现**——本地书没有源可换，给用户留一个点了必定失败的按钮不如不留。
   > 点击只是把面板状态置为 `ReaderPanel.SOURCE_SWITCH`，与其余五个面板同一套进出场机制。

3. 新增 [`SourceSwitchSheet.kt`](../module_book/src/main/java/com/ebook/book/reader/SourceSwitchSheet.kt)：
   ```kotlin
   @Composable
   fun SourceSwitchSheet(
       currentBook: BookInfoEntity,
       currentSourceUrl: String,
       onDismiss: () -> Unit,
       onSwitch: (SearchBookEntity) -> Unit,
       viewModel: SourceSwitchViewModel = hiltViewModel(),
   ) {
       // 弹层展示：搜索进度条 + 结果列表（按匹配度排序）
       // 每行：书名 / 作者 / 书源名 / 最新章节 / 匹配度标签
       // 点击某行 → onSwitch(book)
   }
   ```

   > **落地实况**：文件真实位置是 [`module_book/src/main/java/com/ebook/book/reader/SourceSwitchSheet.kt`](../module_book/src/main/java/com/ebook/book/reader/SourceSwitchSheet.kt)
   > （与其余阅读器面板同目录，不放 `view/widget`）；签名是 `(viewModel, oldShelf, onDismiss, onSwitched)`——
   > ① **VM 由宿主用 `hiltViewModel()` 取到后传入**，组件自己不拿（与 `ChapterDownloadSheet` 一样保持
   > 「纯展示 + 回调」，也方便宿主决定用哪个作用域的 VM）；② 搜索词与「排除哪个源」都从传入的
   > `oldShelf` 现取（`bookInfo.name`/`author`/`tag`），不需要单独的两个入参；
   > ③ 成功回调给的是 `SourceSwitchOutcome`（新条目 + 旧进度 + 是否被截断），不是裸的 `SearchBookEntity`——
   > 「已跳到第 X 章」与「新源章节较少」这两句话只有仓库现场才说得清。

4. 新增 [`SourceSwitchViewModel.kt`](../module_book/src/main/java/com/ebook/book/mvvm/viewmodel/SourceSwitchViewModel.kt)：
   ```kotlin
   @HiltViewModel
   class SourceSwitchViewModel @Inject constructor(
       private val bookSourceManager: BookSourceManager,
       private val bookRepository: BookRepository,
   ) : ViewModel() {
       private val _candidates = MutableStateFlow<List<SearchBookEntity>>(emptyList())
       val candidates: StateFlow<List<SearchBookEntity>> = _candidates

       private val _progress = MutableStateFlow(0 to 0)
       val progress: StateFlow<Pair<Int, Int>> = _progress

       fun searchCandidates(name: String, author: String, excludeSourceUrl: String) {
           viewModelScope.launch {
               bookSourceManager.searchAcross(name, 1)
                   .filter { event -> event is SourceResult && event.sourceUrl != excludeSourceUrl }
                   .collect { event ->
                       when (event) {
                           is SourceResult -> {
                               val filtered = event.books.filter { matches(it, name, author) }
                               _candidates.update { (it + filtered).sortedByDescending { b -> matchScore(b, name, author) } }
                           }
                           is SourceFinished -> _progress.update { (it.first + 1) to it.second }
                           else -> Unit
                       }
                   }
           }
       }

       /** 匹配度打分：完全匹配 100 > 部分匹配 50 > 仅作者匹配 20 > 其他 0 */
       private fun matchScore(book: SearchBookEntity, name: String, author: String): Int = when {
           book.name == name && book.author == author -> 100
           book.name == name -> 80
           book.name.contains(name) || name.contains(book.name) -> 50
           book.author == author -> 20
           else -> 0
       }

       private fun matches(book: SearchBookEntity, name: String, author: String) =
           matchScore(book, name, author) > 0

       fun switchSource(oldShelf: BookShelfEntity, newBook: SearchBookEntity, onResult: (Result<BookShelfEntity>) -> Unit) {
           viewModelScope.launch {
               onResult(bookRepository.switchSource(oldShelf, newBook))
           }
       }
   }
   ```

   > **落地实况**（五处与片段不同）：
   > ① 基类是 `BaseViewModel<NoOpModel>(NoOpModel())`，**不是** `androidx.lifecycle.ViewModel`
   > （本仓约定：无 Model 门面的纯展示页用 `NoOpModel` 占位；状态流命名也避开基类的 `uiState`）；
   > ② 进度是 `CandidateProgress(finished, total)` 而不是 `Pair<Int, Int>`——除零兜底与 `isRunning`
   > 收在一处，UI 只读不猜；
   > ③ `searchCandidates` 开头**先取消上一轮**（两轮流同时在跑会让旧关键词的书灌进新列表），
   > 且排除的源是经 `skipSourceUrls` **交给 `searchAcross` 的**：片段那种「收回来再 filter」等于
   > 把当前正在读的站点又请求了一遍；
   > ④ 打分比的是 `CommentKey.normalize` 之后的形态（第三方站点的书名常带《》、全角空格、繁简混排，
   > 逐字比会让最像的那本掉到 50 分甚至 0 分），且「作者相同」一档要求两边归一化后**非空**；
   > ⑤ `switchSource` 交回的是 `Result<SourceSwitchOutcome>`（片段交回裸条目，UI 就说不清
   > 「跳到第几章」与「有没有被截断」）；整条聚合流出问题时异常写进 `searchFailure` 状态（片段没有这一面）。

5. 换源结果的 UI 反馈：
   - 成功 → Toast「换源成功，已跳转到第 X 章」→ 重新加载章节内容
   - 失败 → Toast「换源失败：{原因}」→ 弹层保留，用户可另选一本

   > **落地实况**：
   > **成功**是三句话里选一句（纯函数 `switchFeedbackOf`：「已换源，跳到第 X 章」/
   > 「新书源章节较少，已落到最后一章」/「新书源没有解出任何章节，暂时翻不出正文」），
   > **先判目录是否为空再判截断**——顺序反了会把坏消息报成一句报喜的话。章号上屏 +1（VM 交回的是 0 基）。
   > 之后由宿主整体替换 `BookReadViewModel.bookShelf`、重分页并从目标章第一页起排（页级进度不跨源）。
   > **失败不弹 Toast，改为面板内联一句 + 保留弹层**。原处方走不通：`SourceSwitchViewModel` 是弹层用
   > `hiltViewModel()` 起的第二个 VM，`MvvmBinder` 只绑宿主的 `BookReadViewModel`，它发出的
   > `sendToast` 没有任何收集方，只会堆在 Channel 里随 VM 销毁丢弃（不报错、不闪退，就是永远不响）。
   > 失败原因因此一律经状态与 `Result` 交给面板渲染（详见 `SourceSwitchViewModel` 的类 KDoc）。

### 验证方式

- `./gradlew :module_book:testDebugUnitTest` — 全绿（`SourceSwitchViewModelTest` 覆盖匹配度打分与换源流程）
- **人工装机验证**（**最复杂的一步，务必全跑**）：
  1. 至少准备 2 个书源，都能搜到同一本热门书（如「斗破苍穹」）
  2. 从书源 A 加入书架并阅读到第 50 章
  3. 打开阅读菜单 → 点「换源」→ 弹层出现
  4. 观察进度条 → 确认「已收到 X/Y 书源结果」递增
  5. 观察候选列表 → 确认书源 B 的同名书排在最前（完全匹配标签）
  6. 点击书源 B 的候选 → 确认弹层关闭 + Toast「换源成功」
  7. 阅读器 → 确认**跳转到第 50 章**（章序号映射生效）
  8. 阅读器 → 确认正文来自书源 B（内容格式/字体/分页可能不同）
  9. 打开目录 → 确认章节列表已刷新为书源 B 的
  10. 打开书架 → 确认书的标签/书源名已更新为书源 B
  11. 阅读器 → 再点「换源」→ 选择书源 A → 确认能切回，跳到第 50 章（**同一次进程内**旧章文件仍在，
      已读章节命中盘上文件故秒开；跨启动则由内容仓库对账回收旧目录，切回要重拉一次目录——
      这不是 bug，是 ADR-0016 决策 8 的纠偏段写明的取舍）
  12. 阅读到第 60 章 → 换源到书源 B → 确认跳到第 60 章（不是第 50 章，进度已更新）
  13. **边界**：书源 A 有 100 章，书源 B 只有 30 章 → 从 A 第 50 章换到 B → 确认跳到 B 的第 30 章（末章回落）+ 提示「新书源章节较少，已落到最后一章」
  14. **边界**：换源过程中网络失败 → 确认**面板内联一句失败提示**（不是 Toast）+ 弹层保留 + 书架记录未变（解析阶段失败时一行库都没动）

### 提交拆分
```
feat(module_book): 阅读器新增换源功能支持跨书源切换

- SourceSwitchSheet 用当前书书名+作者跨源聚合搜索，按匹配度打分排序
- BookRepository.switchSource 先插新后删旧，章序号映射进度、越界回落末章、页级进度复位
- 旧源的章文件与内存缓存保留（库内四张表的行删净），同进程内切回可秒开
- 换源失败在解析阶段一行库都不动，书架保持原样；失败提示由面板内联渲染并保留弹层

Refs: ADR-0016
```

### 可发布状态
P3-d 完成后多书源架构**全部核心能力就绪**，具备发 minor 版本（0.x.0 → 0.(x+1).0）的条件——
**版本号是否 bump、何时发版归人决定**，收尾阶段不代改版本号。

---

## P4 · 装机验证与文档收尾

### 目标
全链路回归验证 + 文档同步 + 提交规范审查 + 版本号 bump。

### 前置依赖
P1-P3 全部完成。

### 具体改动清单

1. **全链路回归**（按附录 D 完整清单跑一遍）
2. **文档同步**：
   - CONTEXT.md 补充新术语（P0 已完成草稿，此处审查）
   - AGENTS.md「Agent 实战建议」新增一条（P0 已完成草稿，此处审查）
   - README.md 视需要更新（新增「书源管理」章节，指向 ADR-0016）
3. **代码清理**：
   - 检查 `requireParser()` 调用点是否全部迁移，未迁移的补完
   - 迁移完成后删除 `requireParser()`（或保留 `@Deprecated` 一个版本周期再删）
4. **提交规范审查**：
   - 每个 commit 符合 Conventional Commits（type/scope/description/body/footer）
   - ADR 引用写在 footer `Refs: ADR-0016`
5. **版本号 bump**：本轮为 `feat` 主导，触发 MINOR bump（如 0.5.0 → 0.6.0）

### 验证方式
- 完整走一遍附录 D 装机验证清单
- `./gradlew clean build` — 全项目构建通过
- `./gradlew test` — 全模块单测通过
- `./gradlew lint` — Lint 无新增警告

---

## 附录 A · BookSourceManager v2 接口要点

**签名以 [`BookSourceManager.kt`](../lib_book_common/src/main/java/com/ebook/common/analyze/source/BookSourceManager.kt) 为事实源**——本附录原是落地前的处方，逐字抄签名会随实现腐化（事实上就腐化过：`requireParser`/`currentParser` 已随 P3-b 删除、`searchAcross` 落地时多了 `skipSourceUrls`、`switchSource`/`saveCurrentSource` 未保留），故这里只留仍成立的语义要点，具体形参与返回类型看源码。

下面这份签名保留**已实现且语义未变**的部分，供读路线图的人对照概念；它与源码有出入时以源码为准。

```kotlin
package com.ebook.common.analyze.source

import com.ebook.api.entity.BookSourceRule
import com.ebook.db.entity.SearchBookEntity
import kotlinx.coroutines.flow.Flow

/**
 * 书源管理器接口（v2，多书源共存架构）。
 *
 * 语义：
 * - `currentSource` 收敛为「书城用的默认源」，不再影响书架/阅读/下载
 * - 书架/阅读/下载走 `getParserFor(entity.tag)`，各书独立
 */
interface BookSourceManager {
    /** 当前默认书源（书城用），Flow 版本见 [observeDefaultSource]；无启用源时为 null */
    val currentSource: BookSourceRule?

    /** 全部书源（含禁用），按 weight ASC, added_at ASC, url ASC 排序（url 是最终 tiebreaker：批量导入同毫秒时顺序仍需稳定） */
    suspend fun getAllSources(): List<BookSourceRule>

    /** 已启用的书源 */
    suspend fun getEnabledSources(): List<BookSourceRule>

    /** 按 URL 查书源，不存在返回 null */
    suspend fun getSourceByUrl(url: String): BookSourceRule?

    /**
     * 按书源 URL 拿 parser（LRU 缓存 3 个实例）。
     * @return parser 或 null（书源不存在/已删除）；调用方负责处理 null 或抛 [BookSourceNotFoundException]
     */
    suspend fun getParserFor(sourceUrl: String): BookParser?

    /** 新增书源（Room 插入，url 冲突时覆盖） */
    suspend fun addSource(rule: BookSourceRule): Result<Unit>

    /** 删除用户导入的书源；默认源拒绝删除，返回 failure */
    suspend fun removeSource(url: String): Result<Unit>

    /** 启用/禁用书源 */
    suspend fun setEnabled(url: String, enabled: Boolean)

    /** 设置默认书源（书城用），持久化到 SharedPreferences */
    suspend fun setDefaultSource(url: String)

    /** 订阅书源列表变化（唯一携带元数据的一面：条目 = 规则 + isUserImported，管理页据此置灰删除项） */
    fun observeSources(): Flow<List<BookSourceItem>>

    /** 订阅默认书源变化 */
    fun observeDefaultSource(): Flow<BookSourceRule?>

    /**
     * 聚合搜索：多书源并发（上限 5），事件流反馈进度与结果。
     * [skipSourceUrls] 由调用方带进来——「哪些源已经到底」只有调用方知道，
     * Manager 不持有搜索会话状态，免得长出第二个事实源。
     */
    fun searchAcross(
        keyword: String,
        page: Int,
        skipSourceUrls: Set<String> = emptySet(),
    ): Flow<AggregateSearchEvent>

    // 落地时的 API 取舍：
    // - `switchSource(rule)` 与 `saveCurrentSource(context)` **未保留**——全仓无调用方，
    //   职责由 `setDefaultSource(url)` 与两个私有内部方法承接（本轮一次迁完，不需要跨版本兼容层）
    // - 下面两条保留：书源管理页的导入与导出在用
    fun importFromJson(jsonStr: String): BookSourceRule?
    fun exportToJson(rule: BookSourceRule): String
}

sealed class AggregateSearchEvent {
    data class SourceStarted(val sourceUrl: String, val sourceName: String) : AggregateSearchEvent()
    data class SourceResult(val sourceUrl: String, val books: List<SearchBookEntity>) : AggregateSearchEvent()
    data class SourceFailed(val sourceUrl: String, val error: Throwable) : AggregateSearchEvent()
    data class SourceFinished(val sourceUrl: String, val hasMore: Boolean) : AggregateSearchEvent()
    data object AllFinished : AggregateSearchEvent()
}
```

---

## 附录 B · Room Migration 脚本

> **实况（2026-09-08）**：下面这份 DDL 里的 `DEFAULT` 子句**在实现中已全部删除**——实体没有用
> `@ColumnInfo(defaultValue = ...)` 声明默认值，Room 生成的建表语句也就没有 DEFAULT；迁移里多写
> 会让「覆盖安装得到的库」与「全新安装的库」结构不一致，而 Room 的 schema 校验只比实体声明过的
> 东西，这种漂移查不出来。DAO 一律全列绑定，不依赖 DB 侧默认值。最终形态以
> [`DatabaseModule.kt`](../lib_ebook_db/src/main/java/com/ebook/db/di/DatabaseModule.kt) 的
> `MIGRATION_4_5` 为事实源。

```kotlin
/**
 * v4 → v5：新增 book_source 表存书源规则。
 *
 * 无破坏性变更（仅 CREATE TABLE），老用户升级后书架/缓存/下载数据完全保留；
 * 首次启动时 BookSourceManagerImpl 会自动将 assets 默认书源写入本表。
 */
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("""
            CREATE TABLE IF NOT EXISTS `book_source` (
                `url` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `rule_json` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL DEFAULT 1,
                `weight` INTEGER NOT NULL DEFAULT 0,
                `group_name` TEXT NOT NULL DEFAULT '小说',
                `is_user_imported` INTEGER NOT NULL DEFAULT 1,
                `added_at` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`url`)
            )
        """.trimIndent())
    }
}
```

**回滚脚本**（如需，Room 官方不支持降级，只能手工）：
```sql
DROP TABLE IF EXISTS book_source;
-- 并将 AppDatabase.version 改回 4
```

---

## 附录 C · 风险与回滚

| 风险 | 概率 | 影响 | 缓解措施 |
|---|---|---|---|
| Migration 失败导致老用户升级崩溃 | 低 | 高 | `CREATE TABLE IF NOT EXISTS` 幂等；先在 emulator 覆盖安装测试 |
| 聚合搜索触发第三方站点风控 | 中 | 中 | concurrency 上限 5；用户可在书源管理页禁用有问题的书源 |
| 换源事务中途失败导致书架数据错乱 | 低 | 高 | `@Transaction` 注解保证原子性；单测覆盖各失败分支 |
| 章序号映射偏差让用户困惑 | 高 | 低 | Toast 提示「已跳转到第 X 章」；用户可通过目录手动微调 |
| 用户导入恶意/损坏 JSON 导致解析崩溃 | 中 | 中 | 结构校验 + `runCatching` 包裹解析；失败项跳过不影响其他 |
| `requireParser()` 迁移遗漏导致某路径走错书源 | 中 | 中 | grep 全库确认 0 处调用后再删除废弃方法；code review 把关 |
| 旧缓存膨胀占用存储 | 低 | 低 | 后续可加「清理失效书源缓存」入口（P4 遗留） |

**回滚策略**：
- P1 回滚：`git revert` 数据层 commit + 卸载重装（Migration 不可逆，但开发期用户基数小）
- P2 回滚：`git revert` UI commit（数据层保留，用户不可见但无副作用）
- P3-* 回滚：`git revert` 对应 commit；`requireParser()` 兜底路径仍可用

---

## 附录 D · 每阶段装机验证清单

按 AGENTS.md 分工约定：**Agent 止于编译与静态检查**（`assembleDebug` 通过 + 单测全绿 + lint 无新增警告）；
**装机验证由人工在提交前完成**（覆盖安装 → 走路径 → 看现象 → `adb logcat -b crash` 无 FATAL EXCEPTION）。

### P1 验证清单
- [ ] 覆盖安装（保留数据）：书架/搜索/书城/阅读/下载全部功能无回归
- [ ] 全新安装：assets 默认书源自动入库（sqlite3 查 `book_source` 表）
- [ ] `book_source` 表结构符合 schema v5 定义
- [ ] `requireParser()` 兜底为默认源 parser，业务代码无感知

### P2 验证清单
- [ ] 设置页「书源管理」入口可见，副标题显示书源数
- [ ] 书源管理页列表展示默认源，「默认」+「内置」标记可见，Switch 开启
- [ ] 内置源（含默认源）：操作层里「删除」项置灰不可点，行尾给出解释
- [ ] 用户导入源：「删除」项可点，走确认框后真的删掉
- [ ] SAF 导入合法 JSON：预览层显示校验通过 → 确认 → Toast 成功 → 列表刷新
- [ ] SAF 导入非法 JSON：预览层显示红叉 + 具体原因 → 拒绝导入
- [ ] SAF 导入已存在 URL：预览层黄警告「将覆盖」→ 确认后覆盖
- [ ] SAF 导出：文件可再次导入（往返一致）
- [ ] 长按用户源删除：确认弹层 → 列表刷新
- [ ] Switch 切换：重启 App 状态持久化

### P3-a 验证清单
- [ ] 从书源 A 加书 X → 从书源 B 加书 Y → 书架同时展示两本
- [ ] 打开书 X：章节/正文走书源 A parser
- [ ] 打开书 Y：章节/正文走书源 B parser
- [ ] 书架刷新书 X：走书源 A
- [ ] 书架刷新书 Y：走书源 B
- [ ] 下载书 X 章节：任务走书源 A
- [ ] 下载书 Y 章节：任务走书源 B
- [ ] 删除书源 B → 打开书 Y → 提示「书源已失效」
- [ ] 重新导入书源 B → 打开书 Y → 恢复正常

### P3-b 验证清单
- [ ] 至少 2 个启用书源，搜索进度条递增「X/Y」
- [ ] 结果项右侧显示书源名 InfoChip
- [ ] 结果同时来自多个书源（noteUrl 前缀不同）
- [ ] 上拉加载更多：新结果追加，进度条重走
- [ ] 禁用一个书源后重搜：Y 值 -1，结果不含该书源
- [ ] 断网或坏书源：失败不阻塞其他书源结果
- [ ] 点击结果加书架：`tag` 是**该书所属书源 URL**（不是默认源）
- [ ] 打开该书：走**加书架时的书源**，能正常阅读

### P3-c 验证清单
- [ ] 书城顶部胶囊显示当前默认书源名
- [ ] 点击胶囊弹下拉，展示启用书源清单
- [ ] 切换书源：胶囊文案更新 + 书库数据刷新
- [ ] 切回原书源：立即命中缓存（无网络请求日志）
- [ ] 重启 App：书城仍展示上次选中的书源

### P3-d 验证清单
- [ ] 阅读菜单/目录页「换源」入口可见
- [ ] 弹层展示进度条 + 候选列表（按匹配度排序）
- [ ] 完全匹配（书名+作者）的候选排最前，标「完全匹配」
- [ ] 部分匹配的候选排后，标「部分匹配」
- [ ] 点候选 → 弹层关闭 + Toast「换源成功，已跳转到第 X 章」
- [ ] 阅读器跳到**旧书相同的章序号**
- [ ] 正文来自新书源（格式可能不同）
- [ ] 目录列表已刷新为新书源的
- [ ] 书架标签/书源名已更新
- [ ] 再换回原书源：能切回，秒开（旧缓存仍在）
- [ ] 边界：新书源章节较少时跳末章 + Toast 提示
- [ ] 边界：换源网络失败 → 事务回滚 → 书架未变 → 弹层保留

### P4 全链路回归
- [ ] `./gradlew clean build` 全项目构建通过
- [ ] `./gradlew test` 全模块单测通过
- [ ] `./gradlew lint` 无新增警告
- [ ] `./gradlew :module_app:assembleRealDebug` + `assembleMockDebug` 均通过
- [ ] `adb logcat -b crash` 无 FATAL EXCEPTION
- [ ] CONTEXT.md / AGENTS.md / README.md 术语与代码一致

---

## 附录 E · 提交规范速查

按 AGENTS.md 的 Conventional Commits 约定，本轮改动主要涉及：

- `feat(lib_ebook_db)`：P1 新增 BookSourceEntity + DAO
- `refactor(lib_book_common)`：P1 BookSourceManager 重构
- `feat(module_me)`：P2 书源设置页
- `refactor(all)`：P3-a 10 处 requireParser 改造
- `feat(module_find)`：P3-b 聚合搜索、P3-c 书城切换器
- `feat(module_book)`：P3-d 阅读中换源
- `docs`：P0 ADR/CONTEXT/AGENTS/plan 文档

每个 commit footer 加 `Refs: ADR-0016`。

**版本 bump**：本轮以 `feat` 为主，触发 MINOR bump（如 0.5.0 → 0.6.0）。
