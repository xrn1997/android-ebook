# 应用零内置书源 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 APK 不再携带任何书源，代码里不再存在「内置源」概念及其派生机制（首启 seeding、删除保护、冷启动同步快照）。

**Architecture:** 删掉 assets 内置源与整条 seeding 链路；`is_user_imported` 列经一次迁移删除；默认源由「内存快照」改为「按 Room 行现算」；书城空态补一条直达书源管理的入口（独立模式用占位路由承接）；真源语料夹具移出 git。

**Tech Stack:** Kotlin / Room 3（`androidx.room3`，`BundledSQLiteDriver`）/ Hilt / Jetpack Compose / TheRouter / Robolectric + JUnit4

**规格来源:** `docs/superpowers/specs/2026-09-11-zero-builtin-book-source-design.md`

**约定:** 每个 Task 末尾提交一次；提交信息遵循仓库 Conventional Commits（type 白名单、description 中文动词前置、不加句号）。Agent 侧止于 `./gradlew test` 与编译，装机验证留给人工（见 Task 9）。

---

## Task 1: DB 层去掉 `is_user_imported` 列

**Files:**
- Modify: `lib_ebook_db/src/main/java/com/ebook/db/entity/BookSourceEntity.kt`
- Modify: `lib_ebook_db/src/main/java/com/ebook/db/dao/BookSourceDao.kt`
- Modify: `lib_ebook_db/src/main/java/com/ebook/db/AppDatabase.kt`
- Modify: `lib_ebook_db/src/main/java/com/ebook/db/di/DatabaseModule.kt`
- Modify: `lib_ebook_db/src/test/java/com/ebook/db/AppDatabaseSchemaTest.kt`
- Modify: `lib_ebook_db/src/test/java/com/ebook/db/dao/BookSourceDaoTest.kt`
- Generate: `lib_ebook_db/schemas/com.ebook.db.AppDatabase/7.json`（构建产物，需提交）

- [ ] **Step 1: 先改 schema 契约测试，让它指向 v7（此刻会红）**

`AppDatabaseSchemaTest` 里：
- 把 `companion object` 的 `SCHEMA_LATEST_VERSION = 6` 改成 `7`；
- 新增一条用例（放在 `v6 相对 v5 只多出 format 一列` 之后）：

```kotlin
    @Test
    fun `v7 相对 v6 只少 is_user_imported 一列`() {
        val before = fieldsOf(6, TABLE_BOOK_SOURCE).keys
        val after = fieldsOf(7, TABLE_BOOK_SOURCE).keys

        assertTrue("v6 本应有 is_user_imported 列（否则迁移就成了空操作）", before.contains("is_user_imported"))
        assertEquals(setOf("is_user_imported"), before - after)
        assertEquals(before - "is_user_imported", after)
    }

    @Test
    fun `v7 的 book_source 建表语句不含 is_user_imported`() {
        val createSql = entityOf(7, TABLE_BOOK_SOURCE).getString("createSql")
        assertFalse("7.json 的建表语句不该再含 is_user_imported，实际为 $createSql", createSql.contains("is_user_imported"))
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :lib_ebook_db:testDebugUnitTest --tests "com.ebook.db.AppDatabaseSchemaTest"`
Expected: FAIL —— `找不到 schema 目录（期望 com.ebook.db.AppDatabase/7.json 存在）`（7.json 还没生成）

- [ ] **Step 3: 删实体字段**

`BookSourceEntity.kt`：删掉 `isUserImported` 属性及其 KDoc（`@ColumnInfo(name = "is_user_imported")` 那一整段）。

- [ ] **Step 4: 改 DAO**

`BookSourceDao.kt`：
- `deleteUserImported(url: String): Int` 改名为 `deleteByUrl(url: String): Int`，SQL 去掉保护条件，KDoc 重写：

```kotlin
    /**
     * 按 URL 删除书源，返回受影响行数。
     *
     * **没有任何保护条件**：应用不随包携带书源（见 `docs/adr/` 中「应用零内置书源」一篇），
     * 表里每一行都是用户自己导入的，都可删。返回 0 只有一种成因——这行本就不存在。
     */
    @Query("DELETE FROM book_source WHERE url = :url")
    suspend fun deleteByUrl(url: String): Int
```

- 类 KDoc 第一段里「assets 只承担冷启动同步快照与首启 seeding 两件事」的描述改为：本表是书源清单的**唯一事实源**，没有任何 assets 形态的书源。
- `getCount()` 的 KDoc 删掉「首启 seeding 的判据」整段（它现在只是设置页「共 N 个书源」的数据源）。

- [ ] **Step 5: 升版本 + 加迁移**

`AppDatabase.kt`：`version = 6` → `version = 7`。类 KDoc 里「`book_source` 承载多书源共存时代的书源规则（见 ADR-0016）」保持，无需改动。

`DatabaseModule.kt`：在 `MIGRATION_5_6` 之后新增，并加进 `addMigrations(...)` 列表末尾：

```kotlin
    /**
     * v6 → v7：应用不再随包携带任何书源，`book_source` 去掉 `is_user_imported` 列。
     *
     * 两条语句，顺序不可颠倒：
     * 1. 先按该列清掉内置行——`is_user_imported = 0` 只可能由内置源种下（首启灌库，或用户以同 URL
     *    覆盖过它）；列一删就再也分不出来这些行了；
     * 2. 再删列，删除保护随之整体下线。
     *
     * `DROP COLUMN` 可用：本仓 `MIGRATION_3_4` 已在用（`chapter_list DROP COLUMN has_cache`），
     * 且数据库由 `BundledSQLiteDriver` 承接，DDL 能力取决于随包 SQLite 而非设备系统版本；
     * `is_user_imported` 是普通列（非主键、无索引），不触及 `DROP COLUMN` 的限制。
     *
     * 后果：装过开发包的设备上，原本绑定该内置源的书升级后按既有语义显示「书源已失效」。
     * 这正是本迁移的目的（该源本就不该随包下发），不是回归。发布基线 `master` 从未有过本表
     * （其 `AppDatabase` 是 v2），正式用户的升级路径由 `MIGRATION_4_5` 建出一张空表，不受本迁移影响。
     *
     * 声明为 `internal` 而非 `private`：`AppDatabaseSchemaTest` 的 KDoc 已交代迁移链在本模块
     * 跑不动（`BundledSQLiteDriver` 的原生库 JVM 加载不到、Room 3 不公开裸 SQL 通道），
     * 故迁移的**行为**归人工装机验证，这里只保证它可被同模块测试引用以便将来有条件时补测。
     */
    internal val MIGRATION_6_7 = object : Migration(6, 7) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("DELETE FROM book_source WHERE is_user_imported = 0")
            connection.execSQL("ALTER TABLE book_source DROP COLUMN is_user_imported")
        }
    }
```

同时把 `MIGRATION_4_5` KDoc 里「表初次建成时是**空的**——首启由 `lib_book_common` 的 BookSourceManager 数到 `BookSourceDao.getCount() == 0` 后把 assets `default_sources.json` 里的默认源写进来」这段改写为：**表建成即空、且此后不会有任何代码往里灌数据**——书源一律由用户导入，应用不随包携带任何书源。

- [ ] **Step 6: 改 DAO 测试**

`BookSourceDaoTest.kt`：
- `source(...)` 工厂去掉 `isUserImported` 形参与其赋值（第 73、83 行一带）；
- 类 KDoc 里「默认源删除保护：`isUserImported = false` 的行删不掉」一条改为「**删除无保护**：表里每一行都是用户导入的，`deleteByUrl` 不带任何条件」；
- `整行替换会把未赋值字段写回默认值`：删掉 `isUserImported = false` 实参与该用例里 `assertTrue(current.isUserImported)` 一行；
- `未显式赋值的字段取实体声明的默认值`：删掉 `assertTrue(saved.isUserImported)`；
- `删除用户导入书源返回受影响行数` / `删除内置默认源被拒绝且行仍保留` / `删除不存在的 url 返回零行` 三条改写为两条：

```kotlin
    @Test
    fun `删除任意书源返回受影响行数`(): Unit = runBlocking {
        dao.upsert(source(url = "https://user.test"))

        assertEquals(1, dao.deleteByUrl("https://user.test"))
        assertEquals(0, dao.getCount())
    }

    @Test
    fun `删除不存在的 url 返回零行`(): Unit = runBlocking {
        dao.upsert(source(url = "https://b.test"))

        assertEquals(0, dao.deleteByUrl("https://missing.test"))
        assertEquals(1, dao.getCount())
    }
```

- [ ] **Step 7: 构建生成 7.json**

Run: `./gradlew :lib_ebook_db:kspDebugKotlin`
Expected: BUILD SUCCESSFUL；`lib_ebook_db/schemas/com.ebook.db.AppDatabase/7.json` 出现

- [ ] **Step 8: 跑本模块测试**

Run: `./gradlew :lib_ebook_db:testDebugUnitTest`
Expected: PASS（含 Step 1 新增的两条）

- [ ] **Step 9: 提交**

```bash
git add lib_ebook_db/src/main/java/com/ebook/db/entity/BookSourceEntity.kt \
        lib_ebook_db/src/main/java/com/ebook/db/dao/BookSourceDao.kt \
        lib_ebook_db/src/main/java/com/ebook/db/AppDatabase.kt \
        lib_ebook_db/src/main/java/com/ebook/db/di/DatabaseModule.kt \
        lib_ebook_db/src/test/java/com/ebook/db/AppDatabaseSchemaTest.kt \
        lib_ebook_db/src/test/java/com/ebook/db/dao/BookSourceDaoTest.kt \
        lib_ebook_db/schemas/com.ebook.db.AppDatabase/7.json
git commit -m "refactor(lib_ebook_db): 书源表去掉 is_user_imported 列"
```

---

## Task 2: `lib_book_common` 生产代码拆掉 seeding / 快照 / `currentSource` / `isUserImported`

**Files:**
- Delete: `lib_book_common/src/main/assets/default_sources.json`
- Modify: `lib_book_common/src/main/java/com/ebook/common/analyze/source/BookSourceItem.kt`
- Modify: `lib_book_common/src/main/java/com/ebook/common/analyze/source/BookSourceManager.kt`
- Modify: `lib_book_common/src/main/java/com/ebook/common/analyze/source/BookSourceManagerImpl.kt`
- Modify: `lib_book_common/build.gradle.kts`

> **注意**：本 Task 之后测试源码集尚未同步，`./gradlew test` 会编译失败——这是预期的，Task 3/4 修完。

- [ ] **Step 1: 删资产**

```bash
git rm lib_book_common/src/main/assets/default_sources.json
```

- [ ] **Step 2: `BookSourceItem` 去掉 `isUserImported`**

删 `isUserImported` 属性及其 KDoc；把类 KDoc 的「存在的唯一理由：管理页要按是否用户导入把内置源的删除项置灰」改写为：**存在理由已变为承载管理面专属的 `format` 出身位**（解析链路只关心规则，只有管理页要按出身决定渲染与导出入口），并保留原有「为什么不直接递实体」的两条理由。`format` 属性与其 KDoc 保持不变。

- [ ] **Step 3: `BookSourceManager` 接口去掉 `currentSource`**

删 `currentSource` 属性声明；接口 KDoc 中以下几处改写：
- 顶部「两类 API 的信任源不同」段：把「同步面（currentSource）只依赖 assets 冷启动快照」整段删掉，改为「本接口不再有同步读面——应用不随包携带书源，启动时没有任何可同步给出的默认源；默认源一律经 `observeDefaultSource()` 订阅」；
- 「读面返回的类型按谁消费分两种」段里 `observeSources` 的理由改为只讲 `format`；
- 「挂起面的不变式：一律先等首启 seeding 完成」整段删除（没有 seeding 了）；
- 「两个面关于有没有可用默认源的答案一致」段：保留结论（无启用行时 `observeDefaultSource()` 发 null，调用方进引导态），删掉与同步快照同口径的表述；
- `getAllSources` / `getSourceByUrl` KDoc 里「先等首启 seeding」「空表在 seeding 成功后不该出现」等表述改为：空表就是零书源（用户还没导入），调用方按「无可用书源」走引导态；
- `removeSource` KDoc 删掉「内置默认源受删除保护」「先等 seeding（否则窗口内删内置源会误报书源不存在）」，改为：任何一行都可删，返回 0 即该行不存在；
- `addSource` / `addScriptSource` KDoc 里「先等首启 seeding」与「REPLACE 陷阱要带 `isUserImported`」两处删掉，保留「覆盖当前默认源那一行时快照要收敛」的相关表述改为 Task 下的新模型（写后收敛见实现）。

- [ ] **Step 4: 重写 `BookSourceManagerImpl`**

按下列要点改（逐条对照实现里的 KDoc 一并改写，别留下与代码矛盾的说明）：

1. **删常量与字段**：`DEFAULT_SOURCES_FILE`、`coldStartSources`、`loadAssetsRules()`、内部类 `DefaultSource`、`defaultSnapshot`、`seeding`。
2. **删方法**：`ensureSeeded()`、`refreshDefaultFromRoom()`、`evictParser` 不变。
3. **init 块整体删除**：没有同步快照要猜、也没有后台任务要跑。
4. **`applyDefault` 退化**：不再构造 parser、不再写 `defaultSnapshot`，只做两件事——写 SP、推 `defaultUrlFlow`。并入 `persistDefaultUrl` 一处：

```kotlin
    /**
     * 定下当前默认源并落库意义之外的持久化：写 SP + 推 [defaultUrlFlow]。
     *
     * 两件事必须成对完成：SP 是跨启动的记忆，[defaultUrlFlow] 是本次会话里订阅面的重推信号
     * （`observeDefaultSource` 的 combine 依赖它——SP 变更不产生 Room 失效事件）。
     *
     * [url] 为 null（库里没有任何启用行）时**不写 SP**：SP 记的是「用户上次主动选了哪个源」，
     * 抹掉它就只剩「按权重取第一条」这个次优答案了。
     */
    private fun persistDefaultUrl(url: String?) {
        if (url != null) prefs.edit { putString(KEY_CURRENT_SOURCE, url) }
        defaultUrlFlow.value = url
    }
```

5. **默认源判定**：保留 `defaultFromRows(rows)` 不变（它本来就是「条目面启用行 + SP 命中优先」），它成为默认源的唯一计算处。
6. **`getParserFor`**：删掉 `defaultSnapshot?.takeIf { ... }?.parser` 那一段，取值顺序只剩「LRU → 查 DAO 建 parser 入 LRU」；KDoc 里关于「默认源走快照不进 LRU」的整段删除，改为一句：默认源与其它源同构，都在 LRU 里，写路径的 `evictParser` 覆盖它。
7. **写路径收敛**：`addSource` / `addScriptSource` / `removeSource` / `setEnabled` / `setDefaultSource` 中原本判断 `defaultSnapshot` 的地方，改为现算：

```kotlin
    /** 现算当前默认源；库里没有任何启用行时为 null */
    private suspend fun resolveDefault(): SourceDefinition? = defaultFromRows(dao.getAll())

    /**
     写操作之后收敛默认源：重新现算一次并持久化。
     落库已经成功，回填读失败不该让调用方以为写失败，故只记日志。
     */
    private suspend fun syncDefaultAfterWrite() {
        runCatching { persistDefaultUrl(resolveDefault()?.sourceUrl) }
            .onFailure { Logger.e(TAG, "写操作后收敛默认源失败", it) }
    }
```

  调用条件相应改为：
  - `addSource` / `addScriptSource`：`if (resolveDefault() == null || prefs.getString(KEY_CURRENT_SOURCE, null) == rule.url) syncDefaultAfterWrite()`；
  - `removeSource`：`if (prefs.getString(KEY_CURRENT_SOURCE, null) == url) syncDefaultAfterWrite()`；
  - `setEnabled`：`if (enabled) { if (resolveDefault() == null) syncDefaultAfterWrite() } else if (prefs.getString(KEY_CURRENT_SOURCE, null) == url) syncDefaultAfterWrite()`；
  - `setDefaultSource`：库里有该行且（原本禁用）→ 先 `dao.setEnabled(url, true)`，然后 `persistDefaultUrl(url)`，最后 `evictParser(url)`。
8. **`toEntity`**：签名从 `toEntity(isUserImported: Boolean, addedAt: Long)` 变为 `toEntity(addedAt: Long)`，落库不再写 `isUserImported`；`addSource` 里 `existing?.isUserImported ?: true` 一并删除，只留 `addedAt` 的回填。`addScriptSource` 同理删掉 `isUserImported = existing?.isUserImported ?: true`。
9. **`toItem`**：构造 `BookSourceItem` 时去掉 `isUserImported = entity.isUserImported`。
10. **删所有 `seeding.await()`**：`getAllSources` / `getEnabledSources` / `getSourceByUrl` / `getParserFor` / `searchAcross` / `addSource` / `addScriptSource` / `getFormatByUrl` / `getExploreEntries` / `removeSource` / `setEnabled` / `setDefaultSource` 以及两个冷流的第一行都删掉。
11. **`observeSources` / `observeDefaultSource`**：`flow { emitAll(...) }` 的包裹可以直接退化为：

```kotlin
    override fun observeSources(): Flow<List<BookSourceItem>> =
        dao.observeAll().map { rows -> rows.mapNotNull { toItem(it) } }
            .flowOn(Dispatchers.IO)

    override fun observeDefaultSource(): Flow<SourceDefinition?> =
        combine(defaultUrlFlow, dao.observeAll()) { _, rows -> defaultFromRows(rows) }
            .flowOn(Dispatchers.IO)
```

12. **类 KDoc 整段重写**：删「冷启动：同步快照 + 后台落库/回填」「Room 是唯一事实源（seed 之后）」里关于 seeding 的部分，保留并强化「Room 是唯一事实源」与「两条求值后端只在 `toDefinition` 一处按 `format` 分岔」，新增一段说明默认源模型：**默认源每次由 `defaultFromRows(Room 行)` 现算，不再有内存快照**，`defaultUrlFlow` 只是 SP 变更信号。

- [ ] **Step 5: 移除 Robolectric 读 assets 的开关**

`lib_book_common/build.gradle.kts`：先确认没有别的测试读 `src/main/assets`：

Run: `grep -rn "assets" lib_book_common/src/test lib_book_common/src/main/java --include=*.kt | grep -v "\.qoder"`
Expected: 无命中（`default_sources.json` 是唯一读者）

确认后删掉 `android { testOptions { unitTests { isIncludeAndroidResources = true } } }` 整块及其上方那段注释。

- [ ] **Step 6: 编译生产代码**

Run: `./gradlew :lib_book_common:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 提交**

```bash
git add -A lib_book_common/src/main lib_book_common/build.gradle.kts
git commit -m "refactor(lib_book_common): 拆掉内置书源 seeding 与冷启动快照"
```

---

## Task 3: `lib_book_common` 测试与假件同步

**Files:**
- Modify: `lib_book_common/src/test/java/com/ebook/common/analyze/source/BookSourceManagerImplTest.kt`
- Modify: `lib_book_common/src/test/java/com/ebook/common/analyze/source/FakeBookSourceManager.kt`
- Modify: `lib_book_common/src/test/java/com/ebook/common/analyze/source/JsoupSourceReaderTest.kt`（若引用了被删成员）

- [ ] **Step 1: 删掉随内置源一起失效的用例**

`BookSourceManagerImplTest` 中删除下列用例（用 `grep -n "fun \`" 定位后逐个删函数体）：
`构造完成即有默认源快照，不等后台灌库`、`首次启动把 assets 内置源以非用户导入身份灌库且只灌一次`、`二次启动不再读 assets 灌库，用户对默认源的配置不被覆盖`、`SP 指向用户导入源时后台回填把默认源纠正过来`、`挂起读会等首启 seeding 完成，不会看到未灌库的空表`、`seeding 未完成时 getParserFor 也能按 URL 取到内置源的 parser`、`首启窗口内导入同 URL 的规则不会被随后的灌库整行覆盖`、`observeSources 首帧不返回未灌库的空清单`、`冷启动时库里全是禁用行，回填会清空按 assets 猜出的快照`、`灌库写失败时快照按空表收敛且不抛进业务栈`、`取默认源时复用快照 parser 不新建实例`、`覆盖当前默认源的规则后快照与 parser 都换成新规则`、`重启后默认源规则以 Room 为准不被 assets 冷启动快照顶掉`、`addScriptSource 在首启窗口内覆盖内置 URL 时不被随后的灌库冲掉`、`脚本格式覆盖当前默认源那一行后快照换成 Script 定义`、`getFormatByUrl 会等首启 seeding 不把还没灌库当成没这个源`、`observeSources 把内置与用户导入的身份一并带出`。

同文件里：
- companion 里的 `ASSET_URL`（`https://www.bqquge.com`）与 `SOURCE_A`/`SOURCE_B` 的用途注释按新语义改写（`ASSET_URL` 若不再被任何用例引用则删除）；
- 类 KDoc 里「冷启动同步快照不等后台 seeding」「assets 只在表为空时灌一次」等条目删掉；
- `newManager(...)` 的 `scope` 参数与 `backgroundScopeOnScheduler()` / `settleBackground()` 已无用（没有后台任务），一并删除，调用点同步去掉 `scope = ...` 实参；
- `entityOf(rule, isUserImported, addedAt)` 去掉 `isUserImported` 形参与赋值；
- `FakeBookSourceDao`（文件末尾的内联假件）里 `deleteUserImported` → `deleteByUrl`，SQL 语义按 Task 1 的 DAO 对齐（任何行都可删），KDoc 同步；`isUserImported` 相关字段删除。

- [ ] **Step 2: 新增零源行为的用例**

在文件里新增（放在原「冷启动与 seeding」区域的位置）：

```kotlin
    @Test
    fun `零源时清单为空且默认源为 null`(): Unit = runTest {
        val manager = newManager()

        assertTrue(manager.getAllSources().isEmpty())
        assertNull("没有任何启用源时订阅面必须发 null，页面据此进引导态", manager.observeDefaultSource().first())
    }

    @Test
    fun `导入第一条启用源即成为默认源`(): Unit = runTest {
        val manager = newManager()

        manager.addSource(rule("https://only.example"))

        assertEquals("https://only.example", manager.observeDefaultSource().first()?.sourceUrl)
    }

    @Test
    fun `任意一条源都可删除`(): Unit = runTest {
        val manager = newManager()
        manager.addSource(rule("https://a.example"))

        assertTrue(manager.removeSource("https://a.example").isSuccess)

        assertTrue(manager.getAllSources().isEmpty())
        assertNull(manager.observeDefaultSource().first())
    }
```

- [ ] **Step 3: 修具名假件**

`FakeBookSourceManager.kt`：删 `currentSource` 覆写与 `snapshotDefinition` 字段；`observeSources` 改为 `flowOf(rules.values.map { BookSourceItem(it) })`；类 KDoc 里关于 `currentSource` 与 `isUserImported` 的段落删掉。

- [ ] **Step 4: 反向实验**

把 Task 2 里 `addSource` 的 `if (resolveDefault() == null || ...) syncDefaultAfterWrite()` 临时改成 `if (false) syncDefaultAfterWrite()`，跑：
Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.analyze.source.BookSourceManagerImplTest"`
Expected: `导入第一条启用源即成为默认源` FAIL
随后还原，复跑 Expected: PASS

- [ ] **Step 5: 全模块测试**

Run: `./gradlew :lib_book_common:testDebugUnitTest`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add lib_book_common/src/test
git commit -m "test(lib_book_common): 书源管理测试改锁零内置源行为"
```

---

## Task 4: 其余 9 份假件与跨模块测试同步

**Files（逐个 `grep ": BookSourceManager {"` 现数，下列为当前实况）:**
- Modify: `module_find/src/test/java/com/ebook/find/mvvm/viewmodel/LibraryViewModelTest.kt`
- Modify: `module_find/src/test/java/com/ebook/find/mvvm/viewmodel/SearchViewModelTest.kt`
- Modify: `module_find/src/test/java/com/ebook/find/repository/BookSourceRepositoryLibraryCacheTest.kt`
- Modify: `module_find/src/test/java/com/ebook/find/repository/BookSourceRepositoryNoSourceTest.kt`
- Modify: `module_book/src/test/java/com/ebook/book/mvvm/viewmodel/BookDetailViewModelSourceTest.kt`
- Modify: `module_book/src/test/java/com/ebook/book/mvvm/viewmodel/SourceSwitchViewModelTest.kt`
- Modify: `module_me/src/test/java/com/ebook/me/mvvm/viewmodel/FakeBookSourceManager.kt`
- Modify: `module_me/src/test/java/com/ebook/me/mvvm/viewmodel/BookSourceViewModelTest.kt`
- Modify: `module_me/src/test/java/com/ebook/me/mvvm/viewmodel/SettingViewModelTest.kt`

- [ ] **Step 1: 现数一遍实现点**

Run: `grep -rn ": BookSourceManager {" --include=*.kt . | grep -v "/build/"`
Expected: 10 处（1 生产实现 + 2 具名假件 + 7 内联）

- [ ] **Step 2: `module_find` 四处内联假件**

每个内联实现里删掉 `override val currentSource: SourceDefinition? get() = ...` 整行（连同其上方注释）。`LibraryViewModelTest` 与 `SearchViewModelTest` 里断言默认源的地方改用 `observeDefaultSource()` 的返回流。

- [ ] **Step 3: `module_book` 两处内联假件**

同上删 `currentSource` 覆写。`SourceSwitchViewModelTest` / `BookDetailViewModelSourceTest` 里若有用例专门测「同步快照」，删除该用例。

- [ ] **Step 4: `module_me` 具名假件**

`FakeBookSourceManager.kt`：
- 删构造参数 `protectedUrls` 与 `protected` 字段；
- 删 `currentSource` 覆写；
- `observeSources` 改为 `BookSourceItem(rule)`（不再传 `isUserImported`）；
- `removeSource` 里「不存在的 URL」判据保留，删掉 `url in protected` 那条 failure 分支；
- `addSource` 里「覆盖时保留内置身份」注释与 `protected` 相关逻辑删掉；
- 类 KDoc 里关于内置源/删除保护的段落改写为「表里每一行都是用户导入的，删除无保护」。

- [ ] **Step 5: 跑三模块测试**

Run: `./gradlew :module_find:testDebugUnitTest :module_book:testDebugUnitTest :module_me:testDebugUnitTest`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add module_find/src/test module_book/src/test module_me/src/test
git commit -m "test(all): 书源假件同步去掉内置源语义"
```

---

## Task 5: `module_me` 生产 UI/VM 清理

**Files:**
- Modify: `module_me/src/main/java/com/ebook/me/view/BookSourceManageActivity.kt`
- Modify: `module_me/src/main/java/com/ebook/me/mvvm/viewmodel/BookSourceViewModel.kt`
- Modify: `module_me/src/main/java/com/ebook/me/mvvm/viewmodel/SettingViewModel.kt`

- [ ] **Step 1: 删掉「内置」标记与置灰**

`BookSourceManageActivity.kt`：
- `SourceRow`（约 386–445 行）：删掉 `!item.isUserImported` 分支与「内置」`InfoChip`，`if (isDefault || !item.isUserImported || item.format == SourceFormat.SCRIPT)` 条件去掉中间一项；KDoc 里「三个标记各答一个问题」改为两个标记（默认 / 脚本），并删掉「内置 = 这条删不掉」的整句；
- 操作弹层（约 530–560 行）：删掉「内置源：整项置灰不可点」的分支与其理由文案，删除项对每一行都可用；
- 函数 KDoc（约 466–480 行）里「『删除』对内置源照常出现但点不动」整段删除，改为：删除对每一行都可用，失败只可能是该行已不存在；
- 顶部「存 `BookSourceItem` 而不是 rule：操作层要按 `isUserImported` 决定删除项是否置灰」的注释改为：按 `format` 决定要不要给导出入口。

- [ ] **Step 2: `BookSourceViewModel` 去掉内置源分流**

- 删 `Notice.DeleteDefaultBlocked`（约 140 行）；
- `removeSource`（约 258–269 行）分流简化为：

```kotlin
    fun removeSource(url: String) {
        viewModelScope.launch {
            bookSourceManager.removeSource(url).onFailure { e ->
                // 清单里已经没有这一行 = 别处刚删过（本页自己刚删的那次不会走到这里）；
                // 其余失败交回 reportFailure 的共享口径，不编一句指导不了行动的话
                if (bookSourceState.value.sources.none { it.rule.url == url }) {
                    _notice.value = Notice.DeleteMissing
                } else {
                    reportFailure(e)
                }
            }
        }
    }
```

- 类 KDoc 与 `BookSourcePageState` KDoc 里所有 `isUserImported` 相关表述删掉。

- [ ] **Step 3: `SettingViewModel`**

`SettingViewModel.kt:148-155` 一带的注释里提到 `observeSources` 内部先等 seeding 的表述删掉（现在它直接是 DAO 流）。

- [ ] **Step 4: 跑测试 + 编译**

Run: `./gradlew :module_me:testDebugUnitTest :module_me:assembleDebug`
Expected: PASS / BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add module_me/src/main
git commit -m "refactor(module_me): 书源管理页去掉内置源标记与删除保护"
```

---

## Task 6: 书城空态补导入入口（含独立模式占位）

**Files:**
- Modify: `lib_book_common/src/main/java/com/ebook/common/event/KeyCode.kt`
- Modify: `module_find/src/main/java/com/ebook/find/page/BookstorePage.kt`
- Modify: `module_find/src/main/res/values/strings.xml`
- Modify: `module_find/src/main/test/debug/TestApplication.kt`
- Create: `module_find/src/main/test/debug/TestBookSourceActivity.kt`
- Modify: `module_find/src/main/module/AndroidManifest.xml`

- [ ] **Step 1: 加占位路由常量**

`KeyCode.kt` 的 `interface Find` 里，在 `TEST_DETAIL_PATH` 之后加：

```kotlin
            /** 独立运行时的书源管理占位页（替代 module_me 的 [Me.BOOK_SOURCE_PATH]） */
            const val TEST_BOOK_SOURCE_PATH = BASE_PATH + "test_book_source"
```

- [ ] **Step 2: 加字符串资源**

`module_find/src/main/res/values/strings.xml` 增加：

```xml
    <string name="go_import_book_source">去导入书源</string>
```

- [ ] **Step 3: 加按钮**

`BookstorePage.kt` 的 `SourceGuidance`：在末尾 `Text` 之后追加按钮，并更新其 KDoc（原文「这里刻意不给『去书源管理』按钮」整段替换为：按钮的跨模块路由在独立模式下由 `TestBookSourceActivity` 占位承接，见 `TestApplication`）。

```kotlin
@Composable
private fun SourceGuidance(state: LibrarySourceState) {
    val context = LocalContext.current
    val (titleRes, bodyRes) = when (state) {
        LibrarySourceState.NoSource ->
            R.string.no_book_source_title to R.string.no_book_source_guidance
        LibrarySourceState.BrokenSource ->
            R.string.broken_book_source_title to R.string.broken_book_source_guidance
        LibrarySourceState.Ready -> error("Ready 档位不该渲染引导态")
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SectionTitle(text = stringResource(titleRes))
        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        // 只在「一条源都没有」这一档给入口：BrokenSource 有源可用（顶部切换器就在页面上），
        // 那一刻用户要的是换源或重导，不是「去导入一个新源」
        if (state == LibrarySourceState.NoSource) {
            TextButton(onClick = {
                TheRouter.build(KeyCode.Me.BOOK_SOURCE_PATH).navigation(context)
            }) {
                Text(text = stringResource(R.string.go_import_book_source))
            }
        }
    }
}
```

需要的 import：`androidx.compose.material3.TextButton`（`LocalContext`、`TheRouter`、`KeyCode`、`R` 在本文件里已有）。

- [ ] **Step 4: 独立模式占位页**

新建 `module_find/src/main/test/debug/TestBookSourceActivity.kt`：

```kotlin
package debug

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ebook.common.event.KeyCode
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseActivity

/**
 * 模块独立运行时的书源管理桩页：替代 module_me 的真实书源管理页，
 * 使 module_find 可脱离宿主独立调试「零源 → 去导入」这条链路。
 *
 * 路由由 [TestApplication] 的 PathReplaceInterceptor 从
 * [KeyCode.Me.BOOK_SOURCE_PATH] 重定向到 [KeyCode.Find.TEST_BOOK_SOURCE_PATH]。
 * 与 [TestDetailActivity] 同一套写法。
 */
@Route(path = KeyCode.Find.TEST_BOOK_SOURCE_PATH)
class TestBookSourceActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        TheRouter.inject(this)
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun PageContent() {
        Column(Modifier.fillMaxSize()) {
            Text("独立运行：书源管理占位页")
        }
    }

    override fun initData() {

    }
}
```

（`import com.therouter.TheRouter` 一并补上。）

- [ ] **Step 5: 接上重定向与清单声明**

`TestApplication.kt` 的 `replace` 增加一条分支：

```kotlin
            override fun replace(path: String?): String? {
                if (path == KeyCode.Book.DETAIL_PATH) {
                    return KeyCode.Find.TEST_DETAIL_PATH
                }
                if (path == KeyCode.Me.BOOK_SOURCE_PATH) {
                    return KeyCode.Find.TEST_BOOK_SOURCE_PATH
                }
                return path
            }
```

类 KDoc 同步补一句：书源管理路由同样被替换为模块内的 [TestBookSourceActivity]。

`module_find/src/main/module/AndroidManifest.xml` 的 `<application>` 内，紧随 `debug.TestDetailActivity` 加：

```xml
        <activity android:name="debug.TestBookSourceActivity" />
```

- [ ] **Step 6: 编译并跑测试**

Run: `./gradlew :module_find:testDebugUnitTest :module_find:assembleDebug :module_find:compileDebugKotlin -PisModule=true`
Expected: BUILD SUCCESSFUL

> 注：`-PisModule=true` 会渗进 `settings.gradle.kts` 的 `includeBuild` 导致 lib_common 构建失败（见 AGENTS.md）。若命中该失败，改为把 `gradle.properties` 的 `isModule` 临时改成 `true` 构建一次、**改回后再提交**，不要提交 `true`。

- [ ] **Step 7: 提交**

```bash
git add lib_book_common/src/main/java/com/ebook/common/event/KeyCode.kt \
        module_find/src/main/java/com/ebook/find/page/BookstorePage.kt \
        module_find/src/main/res/values/strings.xml \
        module_find/src/main/test/debug \
        module_find/src/main/module/AndroidManifest.xml
git commit -m "feat(module_find): 书城无源空态补导入入口"
```

---

## Task 7: 真源语料夹具移出 git

**Files:**
- Modify: `.gitignore`
- Modify: `lib_book_source/src/test/java/com/ebook/source/sandbox/ScriptedCorpusRecorderTest.kt`
- Modify: `lib_book_source/src/test/java/com/ebook/source/sandbox/ScriptedCorpusReplayTest.kt`

- [ ] **Step 1: 加排除规则**

`.gitignore` 末尾（现有 `lib_book_source/src/test/resources/book_source` 之后）加：

```
lib_book_source/src/test/resources/scripted_corpus
```

- [ ] **Step 2: 已入库夹具移出索引**

```bash
git rm -r --cached lib_book_source/src/test/resources/scripted_corpus
```

Expected: 输出 4 条 `rm '...'`；本地文件仍在（`ls lib_book_source/src/test/resources/scripted_corpus/wwwxs5300org-a5f34/` 应看到 4 个文件）

- [ ] **Step 3: 补 KDoc 说明**

`ScriptedCorpusRecorderTest` 类 KDoc 的「落盘位置」句后补一句：夹具目录**不入 git**（含真源规则与真站 HTML/正文，见 `.gitignore`），是本地录制产物。

`ScriptedCorpusReplayTest` 类 KDoc 补同样一句，并说明「无夹具时按 `assumeTrue` 跳过」这一行为在夹具不入库后是常态。

- [ ] **Step 4: 确认回放测试不因缺夹具而红**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.sandbox.ScriptedCorpusReplayTest"`
Expected: PASS（无夹具时按 assume 跳过，报告里显示 skipped）

- [ ] **Step 5: 提交**

```bash
git add .gitignore lib_book_source/src/test
git commit -m "chore(lib_book_source): 真源语料夹具移出仓库"
```

---

## Task 8: 文档与 ADR

**Files:**
- Create: `docs/adr/0033-app-ships-no-book-source.md`（编号以 `ls docs/adr/` 现数为准，取紧邻的下一个）
- Modify: `docs/adr/0016-multi-book-source-architecture.md`
- Modify: `docs/adr/0022-manifest-permission-minimization.md`（若述及内置源）
- Modify: `docs/adr/0027-source-pagination-boundary.md`（若述及内置源）
- Modify: `AGENTS.md`
- Modify: `CONTEXT.md`（若有「内置书源」术语）

- [ ] **Step 1: 写 ADR**

Run: `ls docs/adr/` 确认下一个可用编号与 `ADR-FORMAT.md` 的写作规范，然后新建 ADR。内容须**自足**（不引用本仓其他 ADR 编号），覆盖：

- 决策：本应用不随包携带任何书源，书源一律由用户自行导入；
- **Why**：书源是指向第三方内容站点的访问配置，随包分发等于由应用内置一份这样的配置；用户自备不受此限；
- 连带取舍：删除保护（`is_user_imported` 列）、首启 seeding、冷启动同步快照 `currentSource` 与内存快照 `defaultSnapshot` 全部下线——它们的存在前提都是「应用自带一份书源」；
- 已入库的真源规则与真站 HTML 快照不入仓（录制产物本地留存，回放测试无夹具时跳过）；
- 已知结果：装过携带内置源版本的设备升级后，绑定该书源的书显示「书源已失效」；
- 边界：应用仍支持导入、解析、管理用户自备书源。

- [ ] **Step 2: 就地更新既有 ADR 与 AGENTS.md**

- ADR-0016：把「内置默认源由 assets 首启灌入、不可删」当事实写的段落就地更正——补一句事实更新（标日期），说明该机制已随「应用不随包携带书源」的决策下线；
- ADR-0022 / ADR-0027：仅在与内置源相关处做同样的就地更正；
- `AGENTS.md`：
  - 第 215 条开头「先读 `lib_book_common/src/main/assets/default_sources.json` 获取默认内置书源实况」整句删除，改为「本仓不内置书源（见 ADR-xxxx），书源一律由用户导入；涉及书源改动时不要去找内置清单——它不存在」；同条内「内置清单至今只有一条（笔趣阁）」删掉；
  - 第 217 条「内置默认源由 assets 在首启 seeding 写入 Room（`isUserImported=false`：可被同 URL 导入覆盖、可禁用、不可删），此后 Room 是唯一事实源——改 `default_sources.json` 不会回流给已装机的用户。」整句删除，改为「`book_source` 表是书源清单的**唯一**事实源，应用不随包携带任何书源，也不存在首启 seeding」；
  - 第 231 条「（内置书源曾如此）」括注删掉。
- `CONTEXT.md` 若有「内置书源」条目，删除或改为「用户自备书源」。

- [ ] **Step 3: 提交**

```bash
git add docs AGENTS.md CONTEXT.md
git commit -m "docs(adr): 记录应用零内置书源决策并同步文档"
```

---

## Task 9: 全量验证

- [ ] **Step 1: 全量单测**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL（聚合 `*/build/test-results/*DebugUnitTest/*.xml` 核对 tests/failures 总数）

- [ ] **Step 2: 两个 flavor 构建**

Run: `./gradlew :module_app:assembleMockDebug :module_app:assembleRealDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 确认 APK 里已无书源资产**

Run: `unzip -l module_app/build/outputs/apk/mock/debug/*.apk | grep -i "default_sources\|book_source"`
Expected: 无输出（`default_sources.json` 不再被打包）

- [ ] **Step 4: 确认工作区状态**

Run: `git status --short`
Expected: 干净；`.gitignore` 的两条排除规则已随 Task 7 提交

- [ ] **Step 5: 交代人工验证项（**不得以构建通过暗示已验证**）**

在交付说明里明确列出以下未由 Agent 验证的项：

1. 用装过旧包（含内置源）的设备覆盖安装新包 → 书架/书城/阅读/下载无回归，书源清单里原本的内置源消失，绑定它的书按「书源已失效」提示；
2. 清数据全新安装 → 书城空态出现「去导入书源」按钮，点击进入书源管理页；
3. `isModule=true` 独立运行 `module_find` → 同一按钮进入占位页而非「点了没反应」；
4. 导入一条真实书源 → 书城立即以它为准渲染；
5. `lib_book_source` 的真内核用例（`-PlibebookJs.build` 那一族）在夹具不入库后按跳过处理，行为符合预期。

---

## 计划自检

**spec 覆盖**：§4.1 → Task 1/2/5；§4.2 → Task 2；§4.3 → Task 1；§4.4 → Task 6；§4.5 → Task 7；§4.6 → Task 8；§5 测试 → Task 1/3/4；§6 验证分工 → Task 9。

**与 spec 的偏差（已确认，须同步回写 spec）**：spec §5 写的「新增 `MigrationTestHelper` 用例（仓库第一条迁移测试）」在本仓不可行——`AppDatabaseSchemaTest` 的 KDoc 已交代：迁移是 `DatabaseModule` 的私有成员、`provideAppDatabase` 写死 `BundledSQLiteDriver`（其原生库只随 APK 打包、JVM 加载不到）、Room 3 不公开裸 SQL 通道。实际做法改为**扩充 `AppDatabaseSchemaTest` 的 v7 schema 契约断言**（本仓既有机制），迁移行为留人工装机验证。另：spec 说「仓库目前没有迁移测试」不准确，`AppDatabaseSchemaTest` 就是本仓的迁移/schema 契约测试，只是文件名不含 migrate。

**类型一致性**：`deleteByUrl`（Task 1 DAO 与 Task 3 假件一致）；`resolveDefault()` 只在 Task 2 引入并被写路径使用；`BookSourceItem(rule, format)` 两参构造在 Task 2/3/4 一致；`KeyCode.Find.TEST_BOOK_SOURCE_PATH` 在 Task 6 定义并使用。
