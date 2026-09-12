# 2e 书城接线（脚本书源成为书城默认源）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **状态说明（2026-09-10 追加）**：本计划**已执行完毕**，产出在 HEAD `85af3409` 内（可核对的落点：
> `BookSourceManager.getExploreEntries`、`BookSourceManagerImpl.defaultFromRows`、
> `LibraryViewModel.sources` 放开 format 过滤）。勾选框未回写——本文是一次性的施工记录，
> **实况以代码、KDoc 与 ADR 为准**，不要把空勾选框当成待办。

**Goal:** 让脚本书源成为书城可切换的默认源：默认源载体从 `BookSourceRule` 换成格式中立的 `SourceDefinition`，书城切换器放开 `format` 过滤，分类入口经按格式路由的新读面 `getExploreEntries` 读出（脚本行承载 `exploreUrl` 的 URL 规则串）。

**Architecture:** 默认源的「候选挑选」从两侧各筛一次 format 收敛成 `BookSourceManagerImpl` 里的一个纯函数 `defaultFromRows`（同步回填与订阅面共用）；展示信息（name/url）由 `toDefinition` 从实体列填进 `SourceDefinition.Script`，UI 只读 `sourceUrl`/`displayName`；求值链路（parserFactory、getParserFor）一行不改——2e 只换「谁有资格被立为默认源」与「书城读什么」，不碰任何求值语义。

**Tech Stack:** Kotlin、Coroutines/Flow、Room（无 schema 变更）、Compose、JUnit4 + Robolectric（现状不变）。

---

## 本计划的边界

- **不做**：`ScriptRuleSet.unsupported` 能力警示上 UI（独立小任务）；`jsLib` 源级脚本库；脚本源导出；`getAllSources`/`getEnabledSources`/`getSourceByUrl` 三面的语义放开（它们继续只含原生规则，2e 只改 KDoc 措辞）；`observeSources` 条目不新增字段。
- **不做**：Room 迁移（`book_source` 表结构不变）、TheRouter/Hilt 接线变更（`BookSourceManager` 的注入面不变）。
- **判据**（来自 `2026-09-09-script-source-book-parser.md` 尾段）：`currentSource`/`observeDefaultSource` 载体换成密封 `SourceDefinition` + 展示信息；`LibraryViewModel.sources` 放开 format 过滤；`getBookTypeList` 的脚本分支（本计划把分支上收到 Manager 的 `getExploreEntries`，module_find 变格式盲）；`setDefaultSource` 对脚本行的启用路径。三条「脚本行不当默认源」锁形测试随机制重构改写。

## 关键事实（写代码前必读）

1. **默认源快照的三条既有不变式一条都不能丢**（`BookSourceManagerImpl` 220~410 行的 KDoc 是事实源）：
   - 「规则与 parser 同生同灭」——快照是一个不可变持有者整体换掉（`DefaultSource`），拆字段会留「A 站 URL 配 B 站 parser」的错配窗口；
   - 「回填比整条规则、不比 URL」——2e 后比的是**整个 `SourceDefinition`**（`Script` 分支即 `rawJson+name+url` 的结构相等），同 URL 换格式/换原文仍能触发快照重建；
   - 「同步面不碰 Room」——冷启动猜测仍只来自 assets（原生规则）。SP 指向用户导入源（原生或脚本）时首帧回落 assets 第一条启用源、随后 Room 回填纠正。**脚本默认源的冷启动行为与「导入的原生默认源」完全一致**，不是新问题。
2. **两口径一致的落点变了**：2e 前是「refreshDefaultFromRoom 经 toRule 筛原生、observeDefaultSource 在条目面上再筛一次 format」两侧各筛；2e 后是两侧**共用同一个 `defaultFromRows(rows)`**——纪律约束变成结构约束。
3. **脚本行的展示信息只从实体列来**：`BookSourceItem.rule` 对脚本行是展示空壳（`toItem` 合成），`SourceDefinition.Script` 的 `name`/`url` 同理由 `toDefinition` 填充——**任何地方都不许去解 `rule_json` 取展示信息**。
4. **`ExploreUrlFormat`/`ScriptRuleSet` 是 internal**（`lib_book_source`），跨模块（lib_book_common）够不着——Task 2 的公开门面 `ScriptExplore` 是唯一通道，**不许**为了省这层把求值内部改 public。
5. **`BookType.url` 对两种出身都承载「分类地址/规则串」**：原生 `KindItem.url` 本来就由 `JsoupBookParser.getKindBook` 现场渲染（`{{kind}}` 替换、`{{page}}` 换算），脚本条目的 urlRule 由 `ScriptBookParser.getKindBook` 按脚本 URL 语义渲染（2d 已实现，`loadExplorePage`）——**消费方（ChoiceBookActivity）一行不改**。
6. **接口成员签名变更会打穿 8 处测试假件**（跨模块看不到测试源码，两份 `FakeBookSourceManager` + 6 处内联假件）：清单见「文件结构」；漏改的一侧直接编译失败，这是安全网不是麻烦。
7. **每步提交必须整仓编译**：Task 3 是加法提交（接口新增成员 + 8 处假件补 override，消费方不动）；Task 5 是载体切换的**原子提交**（接口类型 + 实现 + 全部消费方 + 全部假件 + 测试反转一次做完）——拆开必然出现半编译状态。
8. `SourceDefinition.Script` 的新参数带默认值，历史构造点（工厂分支、测试）不破；`name`/`url` 的**生产填充点只有 `toDefinition` 一处**。

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `lib_ebook_api/src/main/java/com/ebook/api/entity/SourceFormat.kt` | 修改 | `SourceDefinition` 加 `sourceUrl`/`displayName` 成员与 `Script(name,url)` |
| `lib_ebook_api/src/test/java/com/ebook/api/entity/SourceDefinitionTest.kt` | 新建 | 载体口径测试 |
| `lib_book_source/src/main/java/com/ebook/source/script/ScriptExplore.kt` | 新建 | 发现条目公开只读门面（纯解析） |
| `lib_book_source/src/test/java/com/ebook/source/script/ScriptExploreTest.kt` | 新建 | 门面测试 |
| `lib_book_common/src/main/java/com/ebook/common/analyze/source/SourceExploreEntry.kt` | 新建 | 分类条目契约类型 |
| `lib_book_common/src/main/java/com/ebook/common/analyze/source/BookSourceManager.kt` | 修改 | 接口：载体类型 + 新读面 + KDoc |
| `lib_book_common/src/main/java/com/ebook/common/analyze/source/BookSourceManagerImpl.kt` | 修改 | 实现：defaultFromRows 收敛 + applyDefault/setDefaultSource/getExploreEntries |
| `lib_book_common/src/main/java/com/ebook/common/analyze/source/FakeBookSourceManager.kt` | 修改 | 假件① |
| `lib_book_common/src/test/java/com/ebook/common/analyze/source/BookSourceManagerImplTest.kt` | 修改 | 三条锁形反转 + 机械迁移 + 新用例 |
| `lib_book_common/src/test/java/com/ebook/common/analyze/source/JsoupSourceReaderTest.kt` | 修改 | `snapshotRule` 赋值改包 Native |
| `module_find/src/main/java/com/ebook/find/repository/BookSourceRepository.kt` | 修改 | `getBookTypeList` 改经 getExploreEntries |
| `module_find/src/main/java/com/ebook/find/mvvm/viewmodel/LibraryViewModel.kt` | 修改 | sources 放开 format、currentSource 换载体 |
| `module_find/src/main/java/com/ebook/find/mvvm/viewmodel/ChoiceBookViewModel.kt` | 修改 | `currentSource?.sourceUrl` |
| `module_find/src/main/java/com/ebook/find/page/BookstorePage.kt` | 修改 | 切换器渲染 displayName |
| `module_find/src/test/.../LibraryViewModelTest.kt` | 修改 | 假件② + 「脚本不进切换器」反转 |
| `module_find/src/test/.../SearchViewModelTest.kt` | 修改 | 假件③ |
| `module_find/src/test/.../BookSourceRepositoryLibraryCacheTest.kt` | 修改 | 假件④ |
| `module_find/src/test/.../BookSourceRepositoryNoSourceTest.kt` | 修改 | 假件⑤ + 分类入口用例改造 |
| `module_me/src/main/java/com/ebook/me/mvvm/viewmodel/BookSourceViewModel.kt` | 修改 | `defaultSource?.sourceUrl` + KDoc |
| `module_me/src/test/java/com/ebook/me/mvvm/viewmodel/FakeBookSourceManager.kt` | 修改 | 假件⑥ |
| `module_book/src/test/.../BookDetailViewModelSourceTest.kt` | 修改 | 假件⑦ |
| `module_book/src/test/.../SourceSwitchViewModelTest.kt` | 修改 | 假件⑧ |
| `AGENTS.md` / `docs/adr/0029-script-book-source-import.md` / `docs/superpowers/specs/2026-09-08-script-source-rule-grammar.md` / `docs/test-coverage-todo.md` | 修改 | 文档同步 |

提交授权：本计划按既有惯例逐 Task 提交（Conventional Commits，scope 见各 Task）；提交前跑该 Task 涉及模块的测试。整仓验证（`test` + `:module_app:assembleDebug`）在 Task 6。

---

### Task 1: `SourceDefinition` 升格为格式中立默认源载体（lib_ebook_api，加法提交）

**Files:**
- Modify: `lib_ebook_api/src/main/java/com/ebook/api/entity/SourceFormat.kt`（仅 `SourceDefinition` 段，55~66 行）
- Test: `lib_ebook_api/src/test/java/com/ebook/api/entity/SourceDefinitionTest.kt`（新建）

- [ ] **Step 1: 写失败测试**

```kotlin
package com.ebook.api.entity

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SourceDefinition] 展示信息成员的载体口径：Native 读规则自带字段、Script 读实体列合成字段。
 * 2e 起本类型是默认源的载体（`BookSourceManager.currentSource`），展示信息不再要求调用方分格式取。
 */
class SourceDefinitionTest {

    @Test
    fun `Native 的展示信息来自规则`() {
        val definition = SourceDefinition.Native(BookSourceRule(name = "原生源", url = "https://a.example"))

        assertEquals("https://a.example", definition.sourceUrl)
        assertEquals("原生源", definition.displayName)
    }

    @Test
    fun `Script 的展示信息来自合成字段而非原始 JSON`() {
        val definition = SourceDefinition.Script(rawJson = "{}", name = "脚本源", url = "https://s.example")

        assertEquals("https://s.example", definition.sourceUrl)
        assertEquals("脚本源", definition.displayName)
    }

    @Test
    fun `Script 的 name 与 url 缺省为空串 历史构造点不破`() {
        val definition = SourceDefinition.Script(rawJson = "{}")

        assertEquals("", definition.sourceUrl)
        assertEquals("", definition.displayName)
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

Run: `./gradlew :lib_ebook_api:testDebugUnitTest --tests "com.ebook.api.entity.SourceDefinitionTest"`
Expected: 编译失败 `Unresolved reference: sourceUrl`

- [ ] **Step 3: 实现**

把 `SourceFormat.kt` 里现有的 `SourceDefinition` 整段（`sealed interface SourceDefinition { ... }`）替换为：

```kotlin
/**
 * 已落库书源的**求值输入与默认源载体**：Manager 按实体 `format` 列构造，交给解析器工厂分发；
 * 2e 起同时是默认源（`BookSourceManager.currentSource`）的载体——密封类型同时表达两种出身，
 * 各自带展示信息（[sourceUrl]/[displayName]），调用方不再需要在「规则字段」与「实体列」之间分格式取值。
 * 密封保证新增格式时编译器逼出所有分支。
 */
sealed interface SourceDefinition {

    /** 书源 URL：默认源路由、解析归属与缓存键都是它 */
    val sourceUrl: String

    /** 展示名：书城切换器等 UI 渲染用 */
    val displayName: String

    /** 原生规则书源：已解码的 [BookSourceRule]，展示信息随规则自带 */
    data class Native(val rule: BookSourceRule) : SourceDefinition {
        override val sourceUrl: String get() = rule.url
        override val displayName: String get() = rule.name
    }

    /**
     * 脚本书源：原始 JSON 文本，解释器按其自身语义求值。
     *
     * [name]/[url] 是**展示信息**，生产填充点只有 `BookSourceManagerImpl.toDefinition`
     * （从实体列取；脚本的 `rule_json` 是社区格式原文，本类型从不解析它），求值链路不读这两位。
     * 默认空串让既有构造点（解析器工厂、测试假件）不必逐处改。
     */
    data class Script(
        val rawJson: String,
        val name: String = "",
        val url: String = "",
    ) : SourceDefinition {
        override val sourceUrl: String get() = url
        override val displayName: String get() = name
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :lib_ebook_api:testDebugUnitTest`
Expected: 全绿（含既有 29 例）

- [ ] **Step 5: 提交**

```bash
git add lib_ebook_api/src/main/java/com/ebook/api/entity/SourceFormat.kt lib_ebook_api/src/test/java/com/ebook/api/entity/SourceDefinitionTest.kt
git commit -m "feat(lib_ebook_api): SourceDefinition 升格为格式中立默认源载体并自带展示信息"
```

---

### Task 2: 脚本发现条目的公开只读门面 `ScriptExplore`（lib_book_source，加法提交）

**Files:**
- Create: `lib_book_source/src/main/java/com/ebook/source/script/ScriptExplore.kt`
- Test: `lib_book_source/src/test/java/com/ebook/source/script/ScriptExploreTest.kt`（新建）

- [ ] **Step 1: 写失败测试**

```kotlin
package com.ebook.source.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ScriptExplore.entries] 的只读口径：纯解析零网络，条目形状与 ExploreUrlFormat 的切分一致 */
class ScriptExploreTest {

    @Test
    fun `JSON 形态的 exploreUrl 给出标题与 URL 规则串`() {
        val raw = """
            {"bookSourceName":"脚本站","bookSourceUrl":"https://s.example",
             "exploreUrl":"[{\"title\":\"玄幻\",\"url\":\"/xuanhuan/{{page}}\"}]"}
        """.trimIndent()

        val entries = ScriptExplore.entries(raw)

        assertEquals(listOf(ScriptExploreEntry("玄幻", "/xuanhuan/{{page}}")), entries)
    }

    @Test
    fun `文本形态按 名称双冒号URL 逐行给出`() {
        val raw = """
            {"bookSourceName":"脚本站","bookSourceUrl":"https://s.example",
             "exploreUrl":"玄幻::/xuanhuan/{{page}}\n都市::/dushi"}
        """.trimIndent()

        val entries = ScriptExplore.entries(raw)

        assertEquals(
            listOf(
                ScriptExploreEntry("玄幻", "/xuanhuan/{{page}}"),
                ScriptExploreEntry("都市", "/dushi"),
            ),
            entries,
        )
    }

    @Test
    fun `未配 exploreUrl 给空列表而不是异常`() {
        val entries = ScriptExplore.entries("""{"bookSourceName":"脚本站","bookSourceUrl":"https://s.example"}""")

        assertTrue(entries.isEmpty())
    }

    @Test
    fun `坏 JSON 抛类型化装载失败且话术与求值链路一致`() {
        val failure = runCatching { ScriptExplore.entries("{ 这不是合法 JSON") }.exceptionOrNull()

        assertTrue(
            "消息要能直接进用户文案：${failure?.message}",
            failure?.message?.contains("脚本书源 JSON 无法解析") == true,
        )
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.ScriptExploreTest"`
Expected: 编译失败 `Unresolved reference: ScriptExplore`

- [ ] **Step 3: 实现（新建 `ScriptExplore.kt`）**

```kotlin
package com.ebook.source.script

/**
 * 脚本书源发现页条目的公开只读面：原始 JSON → 分类条目（标题 + URL 规则串）。
 *
 * **纯解析**：零网络、零 JS、不触发任何取文——书城分类胶囊只是「这源有哪些入口」的清单，
 * 渲染一个胶囊不允许付出一次请求。坏 JSON 抛类型化 [ScriptRuleParseException]
 * （与求值链路同一句话术），调用方按「这条源解不动」处置；未配 `exploreUrl` 返回空列表
 * （配置形态而非错误，与 `ScriptBookParser.fetchLibraryData` 同口径）。
 *
 * 为什么要这一层：[ExploreUrlFormat] 与 [ScriptRuleSet] 都是 internal，跨模块
 * （lib_book_common 的 Manager 要做「按格式路由的分类条目」读面）够不着——这里只递出
 * 「条目清单」这一件事，不暴露任何求值内部。别为了省这一层把求值内部改成 public。
 */
data class ScriptExploreEntry(val title: String, val urlRule: String)

object ScriptExplore {

    fun entries(rawJson: String): List<ScriptExploreEntry> {
        val exploreUrl = ScriptRuleSet.load(rawJson).exploreUrl ?: return emptyList()
        return ExploreUrlFormat.split(exploreUrl).map { ScriptExploreEntry(it.title, it.urlRule) }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest`
Expected: 465 + 4 = 469 例全绿

- [ ] **Step 5: 提交**

```bash
git add lib_book_source/src/main/java/com/ebook/source/script/ScriptExplore.kt lib_book_source/src/test/java/com/ebook/source/script/ScriptExploreTest.kt
git commit -m "feat(lib_book_source): 新增脚本书源发现条目的公开只读门面 ScriptExplore"
```

---

### Task 3: Manager 新读面 `getExploreEntries`（接口加法 + 8 处假件补成员）

**Files:**
- Create: `lib_book_common/src/main/java/com/ebook/common/analyze/source/SourceExploreEntry.kt`
- Modify: `lib_book_common/src/main/java/com/ebook/common/analyze/source/BookSourceManager.kt`（新增成员 + import）
- Modify: `lib_book_common/src/main/java/com/ebook/common/analyze/source/BookSourceManagerImpl.kt`（实现）
- Modify（补 override，`unsupported` 风格随各假件既有惯例）: `lib_book_common/src/test/java/com/ebook/common/analyze/source/FakeBookSourceManager.kt`、`module_me/src/test/java/com/ebook/me/mvvm/viewmodel/FakeBookSourceManager.kt`、`module_find/src/test/java/com/ebook/find/mvvm/viewmodel/LibraryViewModelTest.kt`、`module_find/src/test/java/com/ebook/find/mvvm/viewmodel/SearchViewModelTest.kt`、`module_find/src/test/java/com/ebook/find/repository/BookSourceRepositoryLibraryCacheTest.kt`、`module_find/src/test/java/com/ebook/find/repository/BookSourceRepositoryNoSourceTest.kt`、`module_book/src/test/java/com/ebook/book/mvvm/viewmodel/BookDetailViewModelSourceTest.kt`、`module_book/src/test/java/com/ebook/book/mvvm/viewmodel/SourceSwitchViewModelTest.kt`
- Test: `lib_book_common/src/test/java/com/ebook/common/analyze/source/BookSourceManagerImplTest.kt`（新增 3 例）

- [ ] **Step 1: 新建契约类型 `SourceExploreEntry.kt`**

```kotlin
package com.ebook.common.analyze.source

/**
 * 书城分类条目：[BookSourceManager.getExploreEntries] 的返回元素。
 *
 * 两种格式共用这一个形状——原生行来自 `ruleFind.kinds`，脚本行来自 `exploreUrl` 条目切分，
 * [url] 对两种出身都承载「分类地址/URL 规则串」，由对应解析器在 `getKindBook` 里渲染
 * （原生 `JsoupBookParser` 做 `{{kind}}` 替换与页码换算；脚本 `ScriptBookParser` 走脚本 URL 语义）。
 * 与 `module_find` 的 `BookType` 的分工：本类是 Manager 读面的契约类型，BookType 是页面 UI 模型
 * （「url 只对生成它的源有意义」的语义挂在那边）；空白标题的过滤归 UI 侧，本面原样递出。
 */
data class SourceExploreEntry(
    /** 分类标题（书城分类胶囊文案）。空白 = 书源规则漏写字段，调用方过滤 */
    val title: String,
    /** 分类地址：原生为 KindItem.url；脚本为 exploreUrl 条目的 URL 规则串（可含 {{page}}） */
    val url: String,
)
```

- [ ] **Step 2: 接口加成员（`BookSourceManager.kt`，放在 `getFormatByUrl` 之后）**

```kotlin
    /**
     * 书城分类条目（分类胶囊的数据源）：按 [sourceUrl] 那一行的格式路由读出「标题 + 分类地址」。
     *
     * - 原生行：`ruleFind.kinds` 逐项映射（url 是模板/分类标识，由解析器在 `getKindBook` 里渲染）；
     * - 脚本行：`exploreUrl` 条目切分（`com.ebook.source.script.ScriptExplore`），url 承载
     *   **URL 规则串**（可含 `{{page}}`），由脚本解析器按脚本 URL 语义渲染——消费方对两种出身
     *   拿到的是同一个形状。
     *
     * 空白 [sourceUrl]、库里没有该行、原生行 `rule_json` 解不出（脏行），都返回空列表；
     * 脚本行的 `rule_json` 坏掉时抛类型化 `ScriptRuleParseException`（消息含「脚本书源 JSON 无法解析」），
     * 调用方（书城 VM）按「加载失败给空列表」兜底。**纯解析零网络**：分类胶囊不能因为渲染就发请求。
     * 先等首启 seeding（与全部挂起读同一不变式）。
     */
    suspend fun getExploreEntries(sourceUrl: String): List<SourceExploreEntry>
```

同时在文件头 import 区加 `import com.ebook.api.entity.SourceDefinition`（Task 5 用，这里先不加类型）。**本 Task 只加 `getExploreEntries` 一个成员。**

- [ ] **Step 3: 实现（`BookSourceManagerImpl.kt`，放在 `getFormatByUrl` 实现之后）**

import 区加：`import com.ebook.source.script.ScriptExplore`

```kotlin
    override suspend fun getExploreEntries(sourceUrl: String): List<SourceExploreEntry> {
        if (sourceUrl.isBlank()) return emptyList()
        seeding.await()
        val entity = dao.getByUrl(sourceUrl) ?: return emptyList()
        return when (SourceFormat.fromRaw(entity.format)) {
            SourceFormat.NATIVE ->
                toItem(entity)?.rule?.ruleFind?.kinds?.map { SourceExploreEntry(it.title, it.url) } ?: emptyList()

            SourceFormat.SCRIPT ->
                ScriptExplore.entries(entity.ruleJson).map { SourceExploreEntry(it.title, it.urlRule) }
        }
    }
```

- [ ] **Step 4: 8 处假件补 override**

7 处「本假件用不到」的按各自惯例补一行（`unsupported` 或 throw 风格照抄同文件相邻成员）：

```kotlin
override suspend fun getExploreEntries(sourceUrl: String): List<SourceExploreEntry> = unsupported("getExploreEntries")
```

例外两处要**建模**（它们的被测对象会真调这个面，本 Task 先按既有读面实现，保证行为不变）：

`LibraryViewModelTest.FakeBookSourceManager`（module_find）：

```kotlin
override suspend fun getExploreEntries(sourceUrl: String): List<SourceExploreEntry> {
    urlByQueryCalls += sourceUrl
    if (sourceUrl.isBlank()) return emptyList()
    // 条目壳 rule 的 ruleFind.kinds 对脚本行是默认空表：映射结果自然为空，不必按 format 分岔
    return items.firstOrNull { it.rule.url == sourceUrl }
        ?.rule?.ruleFind?.kinds?.map { SourceExploreEntry(it.title, it.url) }
        ?: emptyList()
}
```

`BookSourceRepositoryNoSourceTest.FakeSourceManager`（module_find）——本 Task 先建模成「按既有 getSourceByUrl 读出 kinds」，Task 4 改造时再换：

```kotlin
override suspend fun getExploreEntries(sourceUrl: String): List<SourceExploreEntry> {
    urlByQueryCalls += sourceUrl
    if (sourceUrl.isBlank()) return emptyList()
    return rules[sourceUrl]?.ruleFind?.kinds?.map { SourceExploreEntry(it.title, it.url) } ?: emptyList()
}
```

- [ ] **Step 5: 新增 3 例 Manager 测试（`BookSourceManagerImplTest.kt`，加进「脚本书源」region）**

先在测试夹具区（`scriptSourceJson` 附近）加一个带 exploreUrl 的脚本 JSON 工厂：

```kotlin
    /** 带 exploreUrl（JSON 形态）的脚本源 JSON：分类条目读面的用例用 */
    private fun scriptSourceWithExplore(
        name: String = "脚本源A",
        url: String = SCRIPT_URL,
        exploreUrl: String = """[{"title":"玄幻","url":"/xuanhuan/{{page}}"},{"title":"","url":"/ghost"}]""",
    ) = """{"bookSourceName":"$name","bookSourceUrl":"$url","exploreUrl":"${exploreUrl.replace("\"", "\\\"")}"}"""
```

三个用例：

```kotlin
    @Test
    fun `getExploreEntries 对脚本行给出 exploreUrl 条目且 url 承载规则串`(): Unit = runTest {
        manager().apply { /* 占位：见下 */ }
    }
```

——**写成三条完整用例**（`manager()` 不是既有夹具，按同文件惯例用 `newManager(scope = backgroundScopeOnScheduler())` + `settleBackground()`）：

```kotlin
    @Test
    fun `getExploreEntries 对脚本行给出 exploreUrl 条目且 url 承载规则串`(): Unit = runTest {
        dao.seed(BookSourceEntity(url = SCRIPT_URL, name = "脚本站", ruleJson = scriptSourceWithExplore(), format = SourceFormat.SCRIPT.raw))
        val manager = newManager(scope = backgroundScopeOnScheduler())
        settleBackground()

        val entries = manager.getExploreEntries(SCRIPT_URL)

        assertEquals(
            "条目原样递出（含空白标题——过滤是 UI 侧的事），url 是 URL 规则串不是渲染后的地址",
            listOf(
                com.ebook.common.analyze.source.SourceExploreEntry("玄幻", "/xuanhuan/{{page}}"),
                com.ebook.common.analyze.source.SourceExploreEntry("", "/ghost"),
            ),
            entries,
        )
    }

    @Test
    fun `getExploreEntries 对原生行给出 ruleFind kinds`(): Unit = runTest {
        dao.seed(
            BookSourceEntity(
                url = "https://native.example",
                name = "原生站",
                ruleJson = storageJsonForRule(rule("https://native.example")),
                format = SourceFormat.NATIVE.raw,
            )
        )
        val manager = newManager(scope = backgroundScopeOnScheduler())
        settleBackground()

        val entries = manager.getExploreEntries("https://native.example")

        assertEquals(
            "原生 kinds 映射成同一个形状（本测试类 rule() 的 kinds 含 玄幻/都市 两条，见其定义）",
            2,
            entries.size,
        )
        assertEquals("玄幻", entries.first().title)
    }

    @Test
    fun `getExploreEntries 对空白 URL 与缺失行给空列表`(): Unit = runTest {
        val manager = newManager(scope = backgroundScopeOnScheduler())
        settleBackground()

        assertTrue(manager.getExploreEntries("").isEmpty())
        assertTrue(manager.getExploreEntries("https://no.where").isEmpty())
    }
```

注意：上面第二例里 `storageJsonForRule` 若测试类没有现成helper，用同文件既有方式造行（参考 `同 URL 改回原生格式后脚本 parser 不残留在缓存里` 用的 `dao.seed(entityOf(rule(...), ...))`）——以文件内既有 helper 为准，`rule()` 的 `ruleFind.kinds` 形状先 grep 确认（该文件用例 `初始默认源到位后分类入口…` 属 module_find；本类的 `rule()` 在 fixture 区，kinds 若为空则本例断言改为「空列表不抛」并补一条显式带 kinds 的 rule 构造）。

- [ ] **Step 6: 跑测试**

Run: `./gradlew :lib_book_common:testDebugUnitTest :lib_book_source:testDebugUnitTest :module_find:testDebugUnitTest :module_me:testDebugUnitTest :module_book:testDebugUnitTest`
Expected: 全绿（既有用例零改动通过——本 Task 是纯加法）

- [ ] **Step 7: 提交**

```bash
git add lib_book_common/src/main/java/com/ebook/common/analyze/source/ lib_book_common/src/test/java/com/ebook/common/analyze/source/ module_me/src/test module_find/src/test module_book/src/test
git commit -m "feat(lib_book_common): 新增按格式路由的书城分类条目读面 getExploreEntries"
```

---

### Task 4: `getBookTypeList` 切到新读面（module_find 变格式盲）

**Files:**
- Modify: `module_find/src/main/java/com/ebook/find/repository/BookSourceRepository.kt`（`getBookTypeList`）
- Modify: `module_find/src/test/java/com/ebook/find/repository/BookSourceRepositoryNoSourceTest.kt`
- Modify: `module_find/src/test/java/com/ebook/find/mvvm/viewmodel/LibraryViewModelTest.kt`（无改动即可编译；如 Step 2 的断言涉及 `urlByQueryCalls` 归属，跟随调整）

- [ ] **Step 1: 改 `getBookTypeList`（160~167 行整段替换）**

```kotlin
    /**
     * 书籍类型列表：按 [sourceUrl] 取分类条目，空白标题过滤后映射成 [BookType]。
     *
     * 读经 [BookSourceManager.getExploreEntries]——格式路由（原生 `ruleFind.kinds` / 脚本
     * `exploreUrl` 条目）在 Manager 一处完成，本方法对两种出身**格式盲**：拿到的都是
     * 「标题 + 分类地址」，脚本条的 url 承载 URL 规则串，由对应解析器在分类页渲染。
     * 空白标题过滤保留在这里：`KindItem`/`exploreUrl` 条目的字段非空带默认值，书源规则
     * 少写字段得到空串，不过滤就会渲染出空白胶囊、并把空 url 传给分类选书页去请求。
     *
     * 空白 [sourceUrl]（当前没有可用书源）与库里已无该行（源刚被删）都返回空列表：
     * 分类入口没有内容可渲染就是正确表现，不必报错；脚本行 `rule_json` 坏掉的类型化异常
     * 由调用方（`LibraryViewModel.bookTypeList` 的 catch）按「加载失败给空列表」兜底。
     */
    suspend fun getBookTypeList(sourceUrl: String): List<BookType> =
        bookSourceManager.getExploreEntries(sourceUrl)
            .filter { it.title.isNotBlank() }
            .map { BookType(it.title, it.url) }
```

- [ ] **Step 2: 改造 `BookSourceRepositoryNoSourceTest`**

假件 `FakeSourceManager`：构造参数加 `exploreEntries`，`getExploreEntries` 改为按参数返回（替换 Task 3 的临时实现）：

```kotlin
        private val exploreEntries: Map<String, List<SourceExploreEntry>>,
        ...
        override suspend fun getExploreEntries(sourceUrl: String): List<SourceExploreEntry> {
            exploreCalls += sourceUrl
            if (sourceUrl.isBlank()) return emptyList()
            return exploreEntries[sourceUrl].orEmpty()
        }
```

`urlByQueryCalls` 改名 `exploreCalls`（语义归位）；`getSourceByUrl` 的 `urlByQueryCalls += url` 行删除（本假件再无消费方走它）。既有用例 `分类入口按入参源的规则给出并滤掉空白标题` 改为经 `exploreEntries` 供给（断言不变：`listOf(BookType("科幻", "/kehuan"))`、`exploreCalls == [SOURCE_B]`）。**新增**脚本条目用例：

```kotlin
    @Test
    fun `脚本源的分类条目经同一读面给出 url 承载规则串`() {
        val manager = FakeSourceManager(
            exploreEntries = mapOf(
                SOURCE_B to listOf(
                    com.ebook.common.analyze.source.SourceExploreEntry("玄幻", "/xuanhuan/{{page}}"),
                    com.ebook.common.analyze.source.SourceExploreEntry("", "/ghost"),
                )
            ),
        )

        val types = runBlocking { repositoryWith(manager).getBookTypeList(SOURCE_B) }

        assertEquals(
            "url 原样透传规则串：渲染归解析器；空白标题被滤掉",
            listOf(BookType("玄幻", "/xuanhuan/{{page}}")),
            types,
        )
    }
```

- [ ] **Step 3: 跑测试**

Run: `./gradlew :module_find:testDebugUnitTest`
Expected: 全绿（含 `LibraryViewModelTest` 的分类入口用例——它走 VM→repository→新读面，假件已在 Task 3 建模）

- [ ] **Step 4: 提交**

```bash
git add module_find
git commit -m "refactor(module_find): 分类入口改经格式中立的 getExploreEntries 读面"
```

---

### Task 5: 默认源载体切换（原子提交：接口 + 实现 + 全部消费方 + 假件 + 测试反转）

**Files:**
- Modify: `lib_book_common/src/main/java/com/ebook/common/analyze/source/BookSourceManager.kt`
- Modify: `lib_book_common/src/main/java/com/ebook/common/analyze/source/BookSourceManagerImpl.kt`
- Modify: `lib_book_common/src/main/java/com/ebook/common/analyze/source/FakeBookSourceManager.kt`
- Modify: `lib_book_common/src/test/java/com/ebook/common/analyze/source/BookSourceManagerImplTest.kt`
- Modify: `lib_book_common/src/test/java/com/ebook/common/analyze/source/JsoupSourceReaderTest.kt`
- Modify: `module_find/src/main/java/com/ebook/find/mvvm/viewmodel/LibraryViewModel.kt`
- Modify: `module_find/src/main/java/com/ebook/find/mvvm/viewmodel/ChoiceBookViewModel.kt`
- Modify: `module_find/src/main/java/com/ebook/find/page/BookstorePage.kt`
- Modify: `module_find/src/test/.../LibraryViewModelTest.kt`、`SearchViewModelTest.kt`、`BookSourceRepositoryLibraryCacheTest.kt`、`BookSourceRepositoryNoSourceTest.kt`
- Modify: `module_me/src/main/java/com/ebook/me/mvvm/viewmodel/BookSourceViewModel.kt`、`module_me/src/test/.../FakeBookSourceManager.kt`
- Modify: `module_book/src/test/.../BookDetailViewModelSourceTest.kt`、`SourceSwitchViewModelTest.kt`

**本 Task 一次提交完成，拆开会出半编译状态。**

- [ ] **Step 1: 接口（`BookSourceManager.kt`）**

1. import 区加 `import com.ebook.api.entity.SourceDefinition`。
2. 顶部 KDoc「规则类型化那几面还有一个共同的格式前提」段（37~45 行）替换为：

```
 * **规则类型化读面与默认源载体的分工（2e 起）**：[getAllSources]、[getEnabledSources] 与
 * [getSourceByUrl] 仍**只认原生规则书源**——脚本的社区 JSON 硬解成 [BookSourceRule] 有两种结局，
 * 都不是「可用规则」（键名对不上解出空规则静默零条目；同名不同形的键直接抛解码异常），理由见
 * [BookSourceManagerImpl.toRule]。但**默认源不再是原生专属**：[currentSource] 与
 * [observeDefaultSource] 以 [SourceDefinition] 为载体，脚本行同样有资格被立为默认源；
 * 脚本书源仍在 [observeSources]（条目带 `format`）、[getParserFor]（真解析器）、
 * [getFormatByUrl]（出身查询）与 [getExploreEntries]（分类条目）四处现身。
```

3. `currentSource` 整段（含 KDoc）替换：

```kotlin
    /**
     * 默认书源同步快照（书城首帧直接读，不等 Room）。
     *
     * **没有任何 enabled 行时为 null**（用户把源全禁用了），与 [observeDefaultSource] 同口径。
     * 载体是 [SourceDefinition]：默认源可以是脚本行（2e 起），两种出身的展示信息都在载体上。
     *
     * **冷启动窗口的特例**：快照在构造时按 assets 清单猜（assets 里只有原生规则），SP 里的
     * 默认 URL 指向用户导入的书源（原生或脚本）时首帧猜不中，会先回落到 assets 里第一条
     * 启用源、随后由 Room 回填纠正。「显示内置源后被纠正」与导入原生默认源的既有行为一致，
     * 调用方按「有值就渲染」写即可。
     */
    val currentSource: SourceDefinition?
```

4. `observeDefaultSource` 整段（含 KDoc）替换：

```kotlin
    /**
     * 订阅默认书源，供书城渲染；永不为禁用态，回落规则与同步快照一致。
     *
     * **没有任何 enabled 行时（用户全禁用）发 null**，与 [currentSource] 同口径。
     * 回落在**两种格式的启用行**里算（2e 起不再筛 format）：SP 命中项（且启用）→ 否则
     * 启用清单第一条——与实现类的 `defaultFromRows` 是同一个函数，两处口径不可能分裂。
     * 调用方（书城）收到 null 应展示「请先启用或导入书源」的引导态，而不是空白页。
     */
    fun observeDefaultSource(): Flow<SourceDefinition?>
```

5. `addScriptSource` KDoc 的「默认源资格」段（198~200 行）替换：

```
     * 「无默认源时提升为默认源」这一条对脚本行**同样成立**（2e 起）：默认源候选是条目面的
     * 全部启用行、不再筛格式，库里没有可用默认源时导入一条启用的脚本源会把它立为默认源。
```

- [ ] **Step 2: 实现（`BookSourceManagerImpl.kt`）**

1. `DefaultSource`（253~254 行）替换：

```kotlin
    /** 默认源快照的载体：求值输入（含展示信息）+ 按它构造的 parser，二者必须同生同灭（见 [defaultSnapshot]） */
    private class DefaultSource(val definition: SourceDefinition, val parser: BookParser)
```

`defaultSnapshot` KDoc 里「快照可以为 null」段保留，其余措辞把「规则」读作「定义」即可（250 行 `@Volatile` 块不动）。

2. `coldStartSources` 的格式前提段（227~231 行）替换：

```
     * **本清单派生的冷启动猜测永远是原生格式**——assets 里只有 [BookSourceRule] 形态的内置源。
     * 快照本体（[DefaultSource.definition]）2e 起是 [SourceDefinition]，Room 回填后可以指向
     * 脚本行；但「构造时按 assets 猜」只有原生可猜，SP 指向用户导入的源（原生或脚本）时
     * 首帧回落 assets 第一条启用源、随后由 [refreshDefaultFromRoom] 纠正（见接口 [currentSource]）。
```

3. `refreshDefaultFromRoom`（361~377 行）替换，并新增 `defaultFromRows`：

```kotlin
    private suspend fun refreshDefaultFromRoom() {
        val target = defaultFromRows(dao.getAll())
        if (target != defaultSnapshot?.definition) {
            applyDefault(target)
        } else {
            defaultUrlFlow.value = target?.sourceUrl
            // 快照没换，但 SP 可能仍是空的或指向已删/已禁用的源：收敛到现算出来的值。
            // 只写 SP、不重建 parser——定义相同，重建只是换个对象。
            val savedUrl = prefs.getString(KEY_CURRENT_SOURCE, null)
            if (target != null && savedUrl != target.sourceUrl) persistDefaultUrl(target.sourceUrl)
        }
    }

    /**
     * 默认源挑选的**唯一实现**：条目面（两种出身平等）的启用行里，SP 命中项优先、否则第一条。
     *
     * 2e 前回落只在原生行里算（[refreshDefaultFromRoom] 经 [toRule] 筛）、订阅面在条目上另筛一次
     * format——「两处口径一致」靠两侧纪律维持。现在统一成本函数，回填与 [observeDefaultSource]
     * 都调它，口径不可能再分裂。
     *
     * 候选先经 [toItem] 解码（原生行解不出即出局，与脏行同一口径；脚本行恒非 null），
     * 挑选在实体层做：条目 [BookSourceItem] 不携带脚本的原文，定义要经 [toDefinition] 从行造。
     * 没有任何候选返回 null——「库里没有可用源」对两种格式一视同仁。
     */
    private fun defaultFromRows(rows: List<BookSourceEntity>): SourceDefinition? {
        val candidates = rows.filter { toItem(it) != null }
        val savedUrl = prefs.getString(KEY_CURRENT_SOURCE, null)
        val target = candidates.firstOrNull { it.url == savedUrl && it.enabled }
            ?: candidates.firstOrNull { it.enabled }
            ?: return null
        return toDefinition(target)
    }
```

4. `applyDefault`（393~405 行）替换：

```kotlin
    private fun applyDefault(definition: SourceDefinition?, persistUrl: Boolean = true) {
        // 定义与 parser 一起换：见 [defaultSnapshot] 关于「拆成两个字段会留错配窗口」的说明。
        // parserFactory 两种出身都能造（Native→JsoupBookParser，Script→ScriptBookParser），
        // 快照不再是原生专属——格式路由仍只在 toDefinition 一处。
        defaultSnapshot = definition?.let { DefaultSource(it, parserFactory(it)) }
        defaultUrlFlow.value = definition?.sourceUrl
        if (definition == null) {
            Logger.w(TAG, "默认书源快照已清空：Room 中没有可用书源")
            return
        }
        if (persistUrl) persistDefaultUrl(definition.sourceUrl)
        Logger.d(TAG, "默认书源: ${definition.displayName} (${definition.sourceUrl})")
    }
```

5. `currentSource`（569 行）替换：

```kotlin
    override val currentSource: SourceDefinition? get() = defaultSnapshot?.definition
```

6. `getParserFor` 的快照命中行（632 行）替换：

```kotlin
                ?: defaultSnapshot?.takeIf { it.definition.sourceUrl == sourceUrl }?.parser
```

其 KDoc「这条优先路径……脚本行永远进不到这条优先路径上」（613~618 行）末句替换：

```
 *   快照只装原生规则（[coldStartSources] 的不变式），能改写默认源那一行的写入口都必须在写成功后
 *   收敛快照（见 [addSource] 的 `syncDefaultAfterWrite` 分支），脚本行永远进不到这条优先路径上。
```
→
```
 *   快照 2e 起可指向脚本行：脚本默认源同样走这条优先路径（复用快照 parser、不占 LRU 名额），
 *   且能改写默认源那一行的写入口都必须在写成功后收敛快照（见 [addSource] 的 `syncDefaultAfterWrite`）。
```

7. `setDefaultSource`（940~964 行）替换：

```kotlin
    override suspend fun setDefaultSource(url: String) {
        seeding.await()
        val entity = dao.getByUrl(url)
        val definition = when {
            entity == null -> {
                Logger.w(TAG, "设为默认源被忽略：库里没有该书源 $url")
                return
            }
            // 脏行（原生 rule_json 解不出）按「库里没有」同一口径忽略；脚本行恒解得出定义
            else -> toDefinition(entity)
        } ?: run {
            Logger.w(TAG, "设为默认源被忽略：该行的规则解不出来 $url")
            return
        }
        if (!entity.enabled) {
            try {
                dao.setEnabled(url, true)
            } catch (e: Exception) {
                Logger.e(TAG, "启用书源失败，设为默认源中止: $url", e)
                return
            }
            applyDefault(
                when (definition) {
                    // 「默认源永不为禁用态」：原生定义的 enabled 位要与库里的新状态一致；
                    // 脚本定义不携带 enabled（订阅面按行读列），无需补丁
                    is SourceDefinition.Native -> definition.copy(rule = definition.rule.copy(enabled = true))
                    is SourceDefinition.Script -> definition
                }
            )
        } else {
            applyDefault(definition)
        }
        evictParser(url)
    }
```

其 KDoc 的「先 await seeding……[getSourceByUrl] 会返回 null」句改为「……`dao.getByUrl` 会返回 null」。`setEnabled` KDoc（905~907 行）「被启用的是一条源」段补一句：`（2e 起脚本行同样参与提升，候选出自 defaultFromRows）`。`addScriptSource` 实现内 826~827 行注释替换：

```
            // 触发条件与 addSource 一致：无可用默认源时脚本行同样被提升为默认源；
            // 覆盖当前默认源那一行时快照必须整体换掉（见上方 KDoc）
```

8. `observeDefaultSource`（1046~1053 行）替换：

```kotlin
    override fun observeDefaultSource(): Flow<SourceDefinition?> = flow {
        seeding.await()
        emitAll(
            combine(defaultUrlFlow, dao.observeAll()) { _, rows -> defaultFromRows(rows) }
        )
    }.flowOn(Dispatchers.IO)
```

其 KDoc（1028~1045 行）整段替换：

```
     * 默认源订阅面。
     *
     * 回落规则与同步快照**共用同一个 [defaultFromRows]**（SP 命中且启用 → 否则第一条启用行，
     * 候选为两种格式的全部启用行）——「两处口径一致」从两侧各筛一次 format 的纪律约束，
     * 变成共用同一个挑选函数的结构约束。
     * SP 缺失、指向已删或已禁用的源时按清单现算，故只要清单里有启用中的源，返回的就一定可用。
     *
     * **没有任何 enabled 行时发 null**（用户把源全禁用了），与同步快照同口径——两边同时为 null
     * 才有「无源可用」这一个事实，调用方（书城）据此展示引导态。
     * `defaultUrlFlow` 参与 combine 的原因与 2e 前相同：`setDefaultSource` 对已启用目标**不写库**，
     * 没有它的话「换默认源」不会触发 observeAll 重推，订阅面就看不到切换。
```

- [ ] **Step 3: lib_book_common 假件与测试**

1. `FakeBookSourceManager`（lib_book_common）：`var snapshotRule: BookSourceRule?` → `var snapshotDefinition: SourceDefinition?`；`currentSource` → `override val currentSource: SourceDefinition? get() = snapshotDefinition`；`observeDefaultSource` → `Flow<SourceDefinition?> = emptyFlow()`。KDoc 同步一句：`snapshotDefinition` 可装 `SourceDefinition.Script`。
2. `JsoupSourceReaderTest:117` `snapshotRule = default` → `snapshotDefinition = SourceDefinition.Native(default)`。
3. `BookSourceManagerImplTest`：
   - **机械迁移**：全文件 `manager.currentSource?.url` → `manager.currentSource?.sourceUrl`；`observeDefaultSource().first()` 后取 `.url` 的断言 → `.sourceUrl`（grep `currentSource` 与 `observeDefaultSource` 逐一核对，共 32 处引用）。
   - **三条锁形反转**（整段替换，KDoc 一并重写）：

```kotlin
    /**
     * 2e 反转：脚本行**有**当默认源的资格，且两面口径一致。
     * 载体是 [SourceDefinition.Script]（展示信息来自实体列），按 URL 取 parser 仍是真解析器——
     * 「被立为默认源」不改变求值路由，改变的只是书城把它当基准。
     */
    @Test
    fun `只有脚本行时它被立为默认源两面同为 Script`(): Unit = runTest {
        seedScriptRow()
        val manager = newManager(scope = backgroundScopeOnScheduler())
        settleBackground()

        val snapshot = manager.currentSource
        assertTrue("同步快照应是 Script 载体，实际 $snapshot", snapshot is SourceDefinition.Script)
        assertEquals(SCRIPT_URL, snapshot?.sourceUrl)
        assertEquals("展示名来自实体列", "脚本测试源", snapshot?.displayName)
        val observed = manager.observeDefaultSource().first()
        assertTrue("订阅面同样是 Script 载体", observed is SourceDefinition.Script)
        assertEquals(SCRIPT_URL, observed?.sourceUrl)
        assertTrue(
            "立为默认源不改变求值路由：按 URL 仍是真解析器",
            manager.getParserFor(SCRIPT_URL) is ScriptBookParser,
        )
    }

    @Test
    fun `setDefaultSource 指向脚本行时生效且 SP 同步`(): Unit = runTest {
        seedScriptRow()
        val manager = newManager(scope = backgroundScopeOnScheduler())
        settleBackground()
        manager.addSource(rule("https://native.example", weight = 5))
        manager.setDefaultSource("https://native.example")
        assertEquals("https://native.example", manager.currentSource?.sourceUrl)

        manager.setDefaultSource(SCRIPT_URL)

        assertEquals(
            "脚本行是合法的默认源候选：设置必须生效",
            SCRIPT_URL,
            manager.currentSource?.sourceUrl,
        )
        assertEquals("SP 同步写脚本行 URL，冷启动回填才有线索", SCRIPT_URL, savedDefaultUrl())
    }

    @Test
    fun `没有可用原生源时脚本导入会被提升为默认源`(): Unit = runTest {
        val manager = newManager(scope = backgroundScopeOnScheduler())
        settleBackground()
        manager.setEnabled(ASSET_URL, false)
        assertNull("前置条件：用户把唯一的内置源禁了", manager.currentSource)

        manager.addScriptSource(scriptSourceJson()).getOrThrow()

        assertEquals(
            "无可用默认源时导入的启用脚本源就地顶上（与 addSource 同一条提升不变式）",
            SCRIPT_URL,
            manager.currentSource?.sourceUrl,
        )
        assertEquals(SCRIPT_URL, manager.observeDefaultSource().first()?.sourceUrl)
        assertTrue(manager.getParserFor(SCRIPT_URL) is ScriptBookParser)
    }
```

   - **格式翻转回落那条（`脚本格式覆盖当前默认源那一行后快照回落而不是留着旧原生规则`，1233~1248 行）整段替换**：

```kotlin
    /**
     * 用脚本格式**覆盖当前默认源那一行**时的收敛：快照必须换成 Script 定义。
     * 旧原生的 parser 不在 LRU 里、evictParser 清不到它；2e 起该行仍是默认源
     * （出身变了不是候选没了），SP 不动（URL 没变）。
     */
    @Test
    fun `脚本格式覆盖当前默认源那一行后快照换成 Script 定义`(): Unit = runTest {
        val manager = newManager(scope = backgroundScopeOnScheduler())
        settleBackground()
        manager.addSource(rule("https://native.example", weight = 5))
        assertEquals("前置条件：内置源是当前默认源", ASSET_URL, manager.currentSource?.sourceUrl)
        val before = manager.getParserFor(ASSET_URL)
        assertTrue(before is JsoupBookParser)

        manager.addScriptSource(scriptSourceJson(name = "脚本化笔趣阁", url = ASSET_URL)).getOrThrow()

        assertNotSame("旧快照的 parser 不能继续服务这个 URL", before, manager.getParserFor(ASSET_URL))
        assertTrue(manager.getParserFor(ASSET_URL) is ScriptBookParser)
        assertEquals("该行仍是默认源：出身变了不是候选没了", ASSET_URL, manager.currentSource?.sourceUrl)
        assertEquals("SP 不动（URL 没变）", ASSET_URL, savedDefaultUrl())
        assertEquals("展示名来自覆盖后的实体列", "脚本化笔趣阁", manager.currentSource?.displayName)
    }
```

   - 933 行用例的注释「脚本源 Plan 2 才参与」改为「脚本源参与聚合走条目面（见 searchAcross）」，断言不动（三面仍挡脚本行是 2e 保留语义）。

- [ ] **Step 4: module_find**

1. `LibraryViewModel`：
   - `librarySourceStateOf`（55 行）签名换 `currentSource: SourceDefinition?`（实现不变，null 判据同形）；
   - `sources`（109~113 行）去掉 format 过滤，KDoc 97~104 行「`format` 这一道过滤同样不可省……」整段替换为：

```
     * 2e 起脚本行**进**候选：脚本书源已是合法的默认源候选（`setDefaultSource` 对它生效），
     * 「点了没反应」的成因消失。候选条目的 `rule` 对脚本行是按实体列合成的展示空壳，
     * 本页（切换器）只读它的 name/url/enabled 三件真值——**别把空壳规则当可用规则用**：
     * 需要规则内容的场合一律经 `getParserFor(url)` 拿解析器。
```

   实现：

```kotlin
    val sources: StateFlow<List<BookSourceRule>> = bookSourceManager.observeSources()
        .map { items -> items.filter { it.rule.enabled }.map { it.rule } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
```

   - `currentSource`（122~123 行）换载体：

```kotlin
    val currentSource: StateFlow<SourceDefinition?> = bookSourceManager.observeDefaultSource()
        .stateIn(viewModelScope, SharingStarted.Eagerly, bookSourceManager.currentSource)
```

   - `bookTypeList`（159 行）：`getBookTypeList(source.url)` → `getBookTypeList(source.sourceUrl)`；
   - `init` collect（180 行）：`loadLibrary(source?.sourceUrl)`；
   - `refreshData`（203 行）：`loadLibrary(currentSource.value?.sourceUrl, force = true)`。

2. `ChoiceBookViewModel:53`：`bookSourceManager.currentSource?.url.orEmpty()` → `bookSourceManager.currentSource?.sourceUrl.orEmpty()`（KDoc 44 行「从 currentSource 取一次快照」不变）。
3. `BookstorePage`：`BookSourceSelector` 参数（159 行）`currentSource: BookSourceRule?` → `currentSource: SourceDefinition?`；166 行 `val rule = currentSource ?: return` → `val current = currentSource ?: return`；187 行 `text = rule.name` → `text = current.displayName`。import 区 `BookSourceRule` 若不再使用则移除。
4. `LibraryViewModelTest`：假件 `defaultSource` 换载体——

```kotlin
        private val defaultSource = MutableStateFlow(items.defaultDefinition(defaultUrl))

        /** 条目 → 载体：Native 用真规则；脚本行的壳没有原文，造一个空 rawJson 的 Script（本页只读展示位） */
        private fun List<BookSourceItem>.defaultDefinition(url: String?): SourceDefinition? {
            val item = firstOrNull { it.rule.url == url } ?: return null
            return when (item.format) {
                SourceFormat.NATIVE -> SourceDefinition.Native(item.rule)
                SourceFormat.SCRIPT -> SourceDefinition.Script(
                    rawJson = "{}",
                    name = item.rule.name,
                    url = item.rule.url,
                )
            }
        }
```

   `setDefaultSource` 改 `defaultSource.value = items.defaultDefinition(url)`（真实现会顺带启用，假件沿用原语义即可——`defaultDefinition` 不带 enabled，VM 不读它）；`currentSource`/`observeDefaultSource` 类型换 `SourceDefinition?`。断言迁移：`?.url` → `?.sourceUrl`（313/365/391 行）。**反转** `脚本书源不进书城切换器候选`（339~364 行）整段替换：

```kotlin
    /**
     * 2e 反转：脚本行**进**切换器候选，且能被立为当前源。
     * 旧边界（脚本行点不动）的成因已消失：setDefaultSource 对脚本行生效、getParserFor 给真解析器。
     */
    @Test
    fun `脚本书源进切换器候选且能成为当前源`(): Unit = runTest(mainDispatcher) {
        val f = fixture(
            items = listOf(item(ruleA()), scriptItem(), item(ruleC())),
            defaultUrl = URL_A,
        )
        awaitUntil("清单已就位") { f.viewModel.sources.value.isNotEmpty() }

        assertEquals(
            "脚本行是合法候选：enabled 即进清单",
            listOf(URL_A, URL_SCRIPT, URL_C),
            f.viewModel.sources.value.map { it.url },
        )

        f.viewModel.switchSource(URL_SCRIPT)
        awaitUntil("脚本源已成为当前源") { f.viewModel.currentSource.value?.sourceUrl == URL_SCRIPT }

        assertEquals("页面档位是 Ready：脚本源坏了才进 BrokenSource", LibrarySourceState.Ready, f.viewModel.sourceState.value)
        assertEquals("换源即重拉：脚本源的 parser 被调", listOf(URL_SCRIPT), f.parsers.getValue(URL_SCRIPT).libraryCalls)
    }
```

   （`fixture` 的 parsers 按 items 全量建，`URL_SCRIPT` 也有 FakeLibraryParser；若 `parsers` map 的键来自 `it.rule.url` 则天然覆盖脚本行。）另**新增**一例锁「脚本是默认源时分类入口为空但页面 Ready」：

```kotlin
    @Test
    fun `脚本源作默认源时页面 Ready 分类入口为空`(): Unit = runTest(mainDispatcher) {
        val f = fixture(items = listOf(scriptItem()), defaultUrl = URL_SCRIPT)
        awaitUntil("档位就位") { f.viewModel.sourceState.value != LibrarySourceState.NoSource }

        assertEquals(LibrarySourceState.Ready, f.viewModel.sourceState.value)
        assertTrue("脚本壳规则没有 kinds：分类入口空（该源若配 exploreUrl 由真实现经 getExploreEntries 给出）",
            f.viewModel.bookTypeList.value.isEmpty())
    }
```

5. `SearchViewModelTest`（192/208 行）、`BookSourceRepositoryLibraryCacheTest`（106/129 行）、`BookSourceRepositoryNoSourceTest`（73/107 行）：`currentSource` 类型 → `SourceDefinition?`（返回值包 `SourceDefinition.Native(...)` 或保持 throw），`observeDefaultSource` 类型 → `Flow<SourceDefinition?>`。
6. `BookSourceViewModel`（module_me）：196 行 `defaultSourceUrl = defaultSource?.url` → `defaultSource?.sourceUrl`；KDoc 176~180 行「但『已启用』不等于『书城能切到』……不是 bug」段替换：

```
         * 2e 起书城切换器对脚本行放开，Y 与书城可切数重新一致；
         * 「启用」与「默认源资格」的口径都来自条目面，不再有「看得到切不到」的差集。
```

7. `module_me` 假件：`defaultRule()` 保留，新增 `private fun defaultDefinition(): SourceDefinition? = defaultRule()?.let { SourceDefinition.Native(it) }`（KDoc 注明：本假件清单只有原生行，脚本能当默认源由真实现测试锁）；`currentSource`/`observeDefaultSource` 类型与返回值替换。
8. `module_book` 假件两处：类型替换（`BookDetailViewModelSourceTest:235` 若返回具体 rule 则包 `SourceDefinition.Native(...)`；`SourceSwitchViewModelTest:447/462` 保持 unsupported，仅换类型签名）。

- [ ] **Step 5: 全模块测试**

Run: `./gradlew test`
Expected: 全绿。若 `BookSourceManagerImplTest` 有本计划没点名的回落类用例红（它们断言「回落第一条**原生**源」而清单里混了脚本行），按新语义修断言并在该用例 KDoc 注明 2e 反转——不允许删用例。

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "feat(all): 脚本书源接入书城默认源与切换器"
```

（commit body 用列表写四件事：载体切换 / defaultFromRows 收敛 / UI 放开 / 锁形反转；scope 跨模块用 `all`。）

---

### Task 6: 文档同步 + 全量验证

**Files:**
- Modify: `AGENTS.md`（「Agent 实战建议」书源段三处）
- Modify: `docs/adr/0029-script-book-source-import.md`（落地状态补 2e 段）
- Modify: `docs/superpowers/specs/2026-09-08-script-source-rule-grammar.md`（§11 能力矩阵下的接线注记，474 行附近）
- Modify: `docs/test-coverage-todo.md`（§7「书城切换器仍只列原生源」改写）

- [ ] **Step 1: AGENTS.md 三处替换**

1. 「书城与『新加的书绑哪个源』用同步快照 `currentSource` 与 `observeDefaultSource()`（无启用源时两者同为 null……）」句：`currentSource` 的载体改为「格式中立的 `SourceDefinition`（脚本行同样可成为默认源，2e 起）」，其余口径不变；
2. 「书城页面判据是 `LibraryViewModel.sourceState` 三档……」段保留，删去「（书城接线归后续计划，本轮不该改任何 format 过滤）」相关表述；
3. 「**书城默认源机制仍原生**——`currentSource` 载体不变、`LibraryViewModel` 的 format 过滤保留、脚本行不当默认源的三条锁形测试原样有效；书城接线（默认源类型重构 + 切换器放开 + `getBookTypeList` 脚本分支）归后续计划，导入报告的逐项能力警示（`ScriptRuleSet.unsupported` 上 UI）同留后续」整句替换：

```
**书城接线（2e，2026-09-10）已落地**：默认源载体是密封 `SourceDefinition`（Native 带规则、Script 带原文+实体列展示信息），`currentSource`/`observeDefaultSource` 两种出身都可承载；回落与回填共用 `defaultFromRows` 一处（条目面、不筛格式）；`LibraryViewModel.sources` 放开 format 过滤（切换器候选 = 启用行）；分类入口经 `BookSourceManager.getExploreEntries`（原生 kinds / 脚本 exploreUrl 条目，url 承载规则串，`ScriptExplore` 是唯一公开门面）。别重新发现的坑：脚本默认源的**冷启动首帧**仍按 assets 猜（原生回落、Room 回填纠正）；`SourceDefinition.Script` 的 name/url 生产填充点只有 `toDefinition`，别去解 `rule_json` 取展示信息；`getAllSources`/`getEnabledSources`/`getSourceByUrl` 三面仍只含原生行（这是保留语义不是漏改）。导入报告的逐项能力警示（`ScriptRuleSet.unsupported` 上 UI）同留后续。
```

- [ ] **Step 2: 规格与 ADR**

1. 规格 474 行注记改写：「其中**发现面尚未接到书城页面**……」→「发现面已接书城（2e：`exploreUrl` 条目经 `getExploreEntries` → 书城分类胶囊，url 承载规则串、由 `getKindBook` 求值渲染）」；
2. ADR-0029「落地状态」段追加一条 `**2026-09-10（2e 书城接线）**`：默认源载体 `SourceDefinition` 化、`defaultFromRows` 收敛、切换器放开、`getExploreEntries`/`ScriptExplore` 新面；并注明三条锁形测试已按新语义反转。

- [ ] **Step 3: test-coverage-todo §7 改写**

「书城切换器仍只列原生源（2d 边界）」小节替换为 2e 的装机验证项：

```
### 7. 书城接线（2e）的装机验证项

1. 导入一条含 exploreUrl 的脚本书源 → 书城右上角切换器出现它 → 选中：分类胶囊换成该源的
   exploreUrl 条目（原生源的 kinds 不再显示）。
2. 点脚本源的分类胶囊 → 分类选书页出书（走 `ScriptBookParser.getKindBook` 的 URL 规则串渲染）；
   该页翻页与「到底」判定同原生。
3. 该源成为默认源后杀进程重启：首帧可能先显示内置原生源（assets 猜测），随后被纠正回脚本源
   ——纠正前后的书库内容都各自成立，不闪退、不混源。
4. 「已启用 Y 个」计数与切换器条数一致（2e 起无差集）。
```

- [ ] **Step 4: 全量验证**

Run: `./gradlew test :module_app:assembleDebug`
Expected: BUILD SUCCESSFUL；测试 0 失败 0 跳过（lib_book_source 469、lib_book_common 281+新增、其余模块按现状）。

- [ ] **Step 5: 提交**

```bash
git add AGENTS.md docs
git commit -m "docs: 脚本书源书城接线（2e）落地状态与约定同步"
```

- [ ] **Step 6: 人工装机验证项交接**

Task 6 Step 3 的四条是**人工项**（涉及真机 UI 与真实站点），在交付说明里明确标注未验证；构建期证据以 `./gradlew test :module_app:assembleDebug` 输出为准。

---

## 执行期修正记录

（执行时逐条追加：计划写的与代码实况不一致、口径因实况而改。不删原计划文字。）

- **2026-09-10（Task 5）`toDefinition` 的 Script 分支要填 name/url**：计划 Step 2 只点名了
  `DefaultSource`/`refreshDefaultFromRoom`/`defaultFromRows`/`applyDefault`/`currentSource`/
  `getParserFor`/`setDefaultSource`/`observeDefaultSource` 八处，没单独列 `toDefinition`。但 Step 3
  的锁形用例 `只有脚本行时它被立为默认源两面同为 Script` 断言 `snapshot?.displayName == "脚本测试源"`、
  `setDefaultSource 指向脚本行时生效且 SP 同步` 断言 `currentSource?.sourceUrl == SCRIPT_URL`——
  这两位的真值来源是 `toDefinition` 造 `SourceDefinition.Script` 时传入的 `name = entity.name, url = entity.url`
  （原实现该分支只递 `entity.ruleJson`，name/url 吃默认空串）。执行时已补这两参，否则上述断言全红。
  这与 `SourceDefinition.Script` KDoc「生产填充点只有 `BookSourceManagerImpl.toDefinition`」一致：
  展示信息从实体列取，绝不去解 `rule_json`。

- **2026-09-10（Task 5）锁形反转用例 `脚本书源进切换器候选且能成为当前源` 要等列表终态**：计划 Step 3
  给的断言序列是「`switchSource` → `awaitUntil { currentSource.value?.sourceUrl == URL_SCRIPT }` →
  断 `sourceState == Ready` → 断 `libraryCalls == listOf(URL_SCRIPT)`」。实测 `currentSource` 一翻转就抢读
  `libraryCalls`，而书库加载是仓库切到 `Dispatchers.IO` 后异步记账的，于是读到空列表（`expected:<[…]> but was:<[]>`），
  连带把同一 fixture 的后续用例 `换源后分类入口跟着换成新源的那一套` 拖成 `UncaughtExceptionsBeforeTest`
  （上一例被取消的协程外溢）。改法对齐本文件既有约定（`切换书源即设为默认并按新源重拉书库` 与 `awaitUntil` KDoc）：
  **等的是列表终态而非 `currentSource` 翻转或「parser 被调过」**——
  `awaitUntil { list.value.map { it.kindName } == listOf("分类@$URL_SCRIPT") }`，
  列表回填即代表 IO 那一轮的 `libraryCalls` 已记好，两断言随后皆稳。

## Self-Review 结论

- **覆盖**：2d 计划尾段四件事（载体重构 / sources 放开 / getBookTypeList 脚本分支 / setDefaultSource 启用路径）+ 三条锁形反转，分别落在 Task 5 / Task 5 / Task 3+4 / Task 5；`jsLib`、能力警示 UI、脚本源导出明确出界。
- **占位符**：Task 3 Step 5 第二例对 `rule()` 的 kinds 形状留了一个「以文件内既有 helper 为准」的核对点——执行者先 grep `private fun rule(` 确认 kinds 内容再落断言；其余步骤均含完整代码。
- **类型一致性**：`sourceUrl`/`displayName`（Task 1 定义，Task 5 全部消费点）；`SourceExploreEntry(title, url)`（Task 3 定义，Task 4/5 消费）；`ScriptExplore.entries(rawJson)`（Task 2 定义，Task 3 实现）；`defaultFromRows`（Task 5 定义，refreshDefaultFromRoom 与 observeDefaultSource 两个调用点）。
