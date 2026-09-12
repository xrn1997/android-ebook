# 脚本书源 BookParser 实现与全链路接线（Plan 2d）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `ScriptBookParser` 落地 `BookParser` 全部五个求值面（搜索/详情/目录/正文/发现），接入聚合搜索与正文读取链路，删除 `ScriptSourcePendingParser` 桩——脚本书源从「可导入可管理」变成「可搜索、可加书架、可全链路阅读」。

**Architecture:** 本文件是解释器四段的终段。2a 词法、2b HTML 求值、2c JSONPath 与 URL 取文层已落地（`:lib_book_source` 290 例全绿）；2d 只做两件事：① 用这些既有件装配出 `BookParser` 的脚本实现（新解析器 + 目录/正文翻页链 + 条目字段提取器），② 把消费链上的两处接缝翻到脚本后端（`BookSourceManagerImpl` 的 parser 工厂与聚合搜索候选、`JsoupSourceReader` 的正文分支）。事实源是 `docs/superpowers/specs/2026-09-08-script-source-rule-grammar.md`（下称「规格」，引用写作 §x.y），冲突时以规格为准并回改本文件。

**Tech Stack:** Kotlin、OkHttp、Jsoup、kotlinx-serialization、JUnit 4、kotlinx-coroutines-test（均已在 `:lib_book_source` 依赖里，**零新依赖**）。

**术语红线（全程有效）：** 全仓禁止出现该生态项目名字样，一律写「脚本书源 / 脚本书源格式」。自检命令一律用 `git grep -in "[l]egado"`（方括号形态，否则命令命中自身），恒须零命中。

---

## 范围裁定（2026-09-09 计划落稿时定夺）

两处范围问题按以下口径裁定（提问被跳过，按推荐项执行；改判任何一条须重写受影响的 Task）：

1. **书城接线不在本段**。脚本源成为书城「可切换默认源」需要把 `BookSourceManager.currentSource`/`observeDefaultSource` 从 `BookSourceRule` 重构为格式中立类型——波及接口、2 份 `FakeBookSourceManager`、6 处内联假件、`LibraryViewModel`/`BookstorePage`/`BookSourceViewModel` 及约 6 个测试文件，与解析器实现混在一份计划里评审面过大。本段维持现状：`currentSource` 载体不变、`LibraryViewModel.sources` 的 `format == NATIVE` 过滤保留、既有「脚本行不当默认源」锁形测试（`BookSourceManagerImplTest` 947/957/1192 行）**一条不改**。书城接线（默认源机制重构 + `getBookTypeList` 脚本分支 + 切换器放开）整体留待后续小计划（下称 **2e**）。地基路线图对 2d 的原话是「BookParser 实现 + 翻页链 + 聚合搜索接入 + 金标准 fixtures」，本段照此执行。
2. **金标准 fixtures 以仓内合成源顶位**。2c 登记的语料证据债以 642 条真实语料为来源，但语料文件已不在桌面原路径（2c 计划「语料扫描的缺口」节）。本段 Task 9 以**合成源**（按规格已核实形态手写：HTML 链式型、JSONPath API 型、`<>` 分页 + POST 型）锁「解析器编排行为」；真实语料验证（JSONPath 子集扩集、`<>` 真实形态、选项键频度、§11-13 的 `[` 开头误判）显式登记为待偿债（Task 10 写进规格 §11 与 `docs/test-coverage-todo.md`）。

## 本段要钉死的规格条款

| # | 规格出处 | 钉成什么 | 落在 |
|---|---|---|---|
| 1 | §1.2/§9 | 求值次序照 §9：每方法一次 `EvalContext`（变量作用域 = 单次解析任务）；每页取文后 `ctx.baseUrl` 推进到该页绝对地址，字段相对落位以它为基准 | Task 1/2 |
| 2 | §1.2 本仓规定 | 条目判据 `name` 与 `bookUrl` 都非空才收录，其余字段缺失留空 | Task 2 |
| 3 | §6.4/§12 | 脚本 URL **不复用** `ListPageUrl` 的首页裁剪；`{{page}}` 初值 1 由 `EvalContext.builtin` 展开；`<,{{page}}>` 交 `ScriptUrlResolver.anglePages` | 既有（Task 2 只装配） |
| 4 | §11-13/§6.5 | 相对落位一律 `TocPageUrl.join` 三形态；URL 类字段先剥选项尾段、落位后回附（尾段由下一次取文消费） | Task 2 |
| 5 | §5.3 + 本仓规定 | `{{key}}` 展开值按 `URLEncoder.encode(UTF-8)` 百分号编码（不编码则中文关键词进 OkHttp 直接非法 URL）；登记 §11 待语料验证 | Task 2 |
| 6 | §1.3/§8 | 发现页：`ExploreUrlFormat.split` 条目 → 逐分类首页抓取；`ruleExplore` 无 `bookList` 时回落 `ruleSearch` 字段（语料同构）；单分类失败按空区块 | Task 3 |
| 7 | §1.4 | `init` 预处理：AllInOne 产物以**首个匹配**为条目上下文、字段经 `evaluateOnItem`（`$n` 组引用可用）；JS 形态类型化穿透；`tocUrl` 为空回落详情页 URL；`canReName` 在本管道无行为差（入参 shelf 不携带搜索值，登记 §11） | Task 4 |
| 8 | §1.5/§7.1/§12 | 目录翻页：`chapterList` 反序前缀由后端消费（既有）；`contentRef` 跨页去重、**零新增即到底**、回环即停、`MAX_TOC_CHAPTERS = 20_000` 触顶按截断记日志不抛——与原生 `TocPager` 同一套判据 | Task 5 |
| 9 | §7.1/§7.2 | `nextTocUrl`/`nextContentUrl` 支持**字符串规则**与**URL 数组**两种形态（数组 = 固定页序一次给出，经 `ScriptRuleSet.jsonField` 展开）；数组元素与入口同址时跳过不终止 | Task 5/6 |
| 10 | §7.2/§7.3 | 正文翻页：空/回环/页数上限（50，与 `JsoupSourceReader.MAX_CONTENT_PAGES` 同值）停；**不套用** `ChapterPageMatcher`（§7.3 明令）；`replaceRegex` 以净化形态跑在拼接后的整章串上 | Task 6 |
| 11 | §3.1/§11-2 | 单值字段收敛 `firstText()` 一处收口（既有）；**content 字段例外**：全部值按 `\n` 连接（textNodes/多匹配的段落性，登记 §11） | Task 6 |
| 12 | §12 | 失败如实报：`ScriptBookParser` 不做整体 catch 吞错（聚合搜索单源收敛在 `searchOneSource` 既有 catch；正文读取器记录错误 URL 后**原样上抛**脚本类型化异常，不裹成「章节内容解析失败」） | Task 2/6/8 |
| 13 | §12 | `getParserFor` 契约保持：脚本行**不返回 null**；`rule_json` 坏行在**首次求值**抛类型化 `ScriptRuleParseException`（构造期不解析，消息含「脚本书源 JSON 无法解析」） | Task 1/7 |

## 与既有代码的接缝（先读这些再用）

| 已有 | 2d 怎么用 |
|---|---|
| `ScriptRuleSet.load(rawJson)` / `rule(kind, field)` | 求值输入；本段加 `jsonField(kind, field): JsonElement?` 供 `nextTocUrl`/`nextContentUrl` 数组形态展开 |
| `ScriptRuleEvaluator(ctx)` | `evaluate` / `evaluateOnItem` / `evaluateOnJsonItem` 三个入口分别对应 Nodes/Matches/Jsons 条目的字段求值 |
| `RuleValue.Page/Nodes/Texts/Json`、`RuleResult.Miss/Nodes/Texts/Matches/Jsons` + `firstText()` | 取文后输入一律 `RuleValue.Page(text)`；列表字段解出的结果经条目提取器分派 |
| `ScriptPageFetcher.fetch` | 本段加 `fetchPage` 返回 `ScriptPage(url, text)`——`fetch` 变薄壳，2c 既有用例零改动 |
| `ScriptUrlResolver.resolve` / `ScriptUrlOption.splitTail` / `TocPageUrl.join` | URL 规则串取文与字段落位共用；`EvalContext.baseUrl` 可变化（翻页推进） |
| `ExploreUrlFormat.split(raw)` | 发现页条目 → `(title, urlRule)` |
| `AggregateSearchEvent` / `searchOneSource` | 事件流零改动；候选集从「规则类型化读面」换成「DAO 启用行 × toItem」（两种出身） |
| `JsoupSourceReader.resolveJsoupParser` | 四种成因判据保留；返回放宽为 `BookParser`，`fetchAndStore` 按 `JsoupBookParser`/`ScriptContentParser` 分岔 |
| `TextNormalizer.unifyNewlines` | 住 `lib_book_common`——正文规范化留在读取器侧做（`lib_book_source` 不得反向依赖） |

## 包与文件布局

```
lib_book_source/src/main/java/com/ebook/source/analyze/
  ScriptBookParser.kt          [新建 public] BookParser + ScriptContentParser 装配实现（实体映射住这里）
  ScriptContentParser.kt       [新建 public interface] 正文抓取接缝（lib_book_common 的读取器跨模块消费）
  ScriptSourcePendingParser.kt [删除]
  ScriptInterpreterPendingException.kt [删除]
lib_book_source/src/main/java/com/ebook/source/script/
  ScriptFieldExtractor.kt      [新建 internal] 列表结果 → 条目上下文；条目 × 字段规则 → 值；URL 字段落位
  ScriptPageChain.kt           [新建 internal] 翻页计划器（字符串规则 × 数组形态 × 回环防护，目录/正文共用）
  ScriptTocPager.kt            [新建 internal] 目录翻页链（去重/零新增/上限，判据与原生 TocPager 对齐）
  ScriptContentPager.kt        [新建 internal] 正文翻页链 + replaceRegex 净化
  ScriptRuleSet.kt             [改] +jsonField；EvalContext.kt [改] baseUrl 可变；ScriptHttp.kt [改] +fetchPage/ScriptPage
lib_book_common/src/main/java/com/ebook/common/analyze/source/
  BookSourceManagerImpl.kt     [改] 工厂 Script 分支换真解析器；searchAcross 候选含脚本行；相关 KDoc/日志
  BookSourceManager.kt         [改] KDoc（getParserFor/searchAcross/getEnabledSources 的脚本行描述）
  JsoupSourceReader.kt         [改] resolveJsoupParser → resolveParser；fetchAndStore 分岔脚本分支
lib_book_source/src/test/java/com/ebook/source/
  analyze/ScriptBookParserTest.kt      [新建]（Task 1/2/4 骨架·搜索·详情）
  script/ScriptFieldExtractorTest.kt  [新建]（并入条目/落位锁形）
  script/ScriptTocPagerTest.kt        [新建]
  script/ScriptContentPagerTest.kt    [新建]
  script/ScriptSourceEndToEndTest.kt  [新建] 合成金标准三型源
  script/ScriptRuleSetTest.kt / ScriptHttpTest.kt / ScriptBookParserExploreTest.kt [追加]
lib_book_common/src/test/java/com/ebook/common/analyze/source/
  BookSourceManagerImplTest.kt [改] 桩断言换真解析器断言 + 聚合搜索脚本行用例
  JsoupSourceReaderTest.kt     [追加] 脚本正文分支用例
```

一个组件一个文件的分工与 2a/2b/2c 一致：故障时「条目解错了」「翻页判错了」「正文拼错了」「接线错了」各自定位。求值层全部保持 `internal`（与测试同模块）；跨模块可见的只有 `ScriptBookParser`、`ScriptContentParser` 两个 analyze 包成员（与 `JsoupBookParser.rule`、`ChapterPageMatcher` 的先例同一条理由：依赖方 test source set 不是 friend module）。

---

### Task 1: 脚手架——`ScriptBookParser` 骨架、`fetchPage`、`jsonField`、可变 `baseUrl`

**Files:**
- Create: `lib_book_source/src/main/java/com/ebook/source/analyze/ScriptBookParser.kt`
- Create: `lib_book_source/src/main/java/com/ebook/source/analyze/ScriptContentParser.kt`
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/ScriptHttp.kt`
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/EvalContext.kt`
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/ScriptRuleSet.kt`
- Test: `lib_book_source/src/test/java/com/ebook/source/analyze/ScriptBookParserTest.kt`（新建）
- Test: `lib_book_source/src/test/java/com/ebook/source/script/ScriptRuleSetTest.kt`（追加）
- Test: `lib_book_source/src/test/java/com/ebook/source/script/ScriptHttpTest.kt`（追加）

- [x] **Step 1: 写失败的测试**

`ScriptBookParserTest.kt`：

```kotlin
package com.ebook.source.analyze

import com.ebook.source.script.ScriptRuleParseException
import com.ebook.source.script.ScriptTransport
import com.ebook.source.script.ScriptRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptBookParserTest {

    /** 断言「请求长什么样」的假传输：2d 各 Task 共用（与 2c 的 RecordingTransport 同法） */
    private class RecordingTransport(vararg pages: Pair<String, String>) : ScriptTransport {
        val requests = mutableListOf<ScriptRequest>()
        private val table = pages.toMap()
        override suspend fun execute(request: ScriptRequest): String {
            requests += request
            return table[request.url] ?: ""
        }
    }

    @Test
    fun `构造期不解析原始 JSON 坏行在首次求值才类型化失败`() = runTest {
        val parser = ScriptBookParser("{broken json", RecordingTransport())
        // 构造不抛：getParserFor 的契约是「脚本行永不 null、不在构造期失败」，
        // 坏行必须等到求值才以类型化异常如实报（§12）。searchBook 第一行就摸 rules（惰性装载），
        // 异常原样穿透（不裹 runCatching）——完整搜索行为由 Task 2 锁，本用例只锁装载失败时点与类型
        val error = runCatching { parser.searchBook("凡人", 1) }.exceptionOrNull()
        assertTrue(error is ScriptRuleParseException)
    }
}
```

`ScriptRuleSetTest.kt` 追加：

```kotlin
    @Test
    fun `jsonField 展开数组形态字段供翻页链消费`() {
        val raw = """{"bookSourceUrl":"https://a.com","ruleToc":{"nextTocUrl":["https://a.com/toc1","https://a.com/toc2"]}}"""
        val set = ScriptRuleSet.load(raw)
        val node = set.jsonField(RuleObjectKind.TOC, "nextTocUrl")
        assertTrue(node is kotlinx.serialization.json.JsonArray)
        // 字符串规则形态下 rule 仍是 null（数组不进规则表，见类 KDoc），jsonField 拿到的是原始节点
        assertTrue(set.rule(RuleObjectKind.TOC, "nextTocUrl") == null)
    }
```

`ScriptHttpTest.kt` 追加：

```kotlin
    @Test
    fun `fetchPage 回传请求落点地址供相对落位使用`() = runTest {
        val transport = RecordingTransport()  // 复用该文件既有的假件写法
        val page = fetcherWith(transport).fetchPage("/dir/page.html", EvalContext(baseUrl = "https://root.com")) { RuleResult.Miss }
        assertEquals("https://root.com/dir/page.html", page.url)
    }
```

（`fetcherWith` 按该文件现状取名——2c 的用例用 `fetcher(transport)` 构造，保持同一写法即可。）

- [x] **Step 2: 运行确认失败**

Run: `./gradlew :lib_book_source:compileDebugUnitTestKotlin`
Expected: FAIL——`ScriptBookParser`、`ScriptTransport` 对 analyze 包测试的可见性（internal 同模块可见，应编译过）、`jsonField`/`fetchPage` 未定义、`ScriptBookParserTest` 引用的类不存在。

- [x] **Step 3: 实现四处改动**

`ScriptContentParser.kt`：

```kotlin
package com.ebook.source.analyze

/**
 * 脚本书源的**正文抓取接缝**（lib_book_common 的 `JsoupSourceReader` 跨模块消费）。
 *
 * 为什么是独立接口而不是塞进 `BookParser`：`BookParser` 是「发现面」契约（搜索/详情/目录/分类/书库），
 * 正文住 `ruleContent` 且由**读取器**驱动落盘（章文件缓存、空正文不落盘都是读取器侧的策略）。
 * 原生链路里读取器向下转型 `JsoupBookParser` 拿规则；脚本格式的规则求值件全部 `internal`，
 * 跨模块转型拿不到——于是把「给我这章的文本」立成显式接缝，读取器按本接口分岔。
 *
 * 为什么是 public：读取器与其测试（lib_book_common 的 test source set）都要实现/消费它，
 * internal 跨模块不可见（同 `ChapterPageMatcher` 的先例）。
 */
interface ScriptContentParser {

    /**
     * 抓取一章正文（纯网络解析，不碰存储）：按 `ruleContent.content` 逐页取文、
     * `nextContentUrl` 翻页拼接（§7.2，不套用 `ChapterPageMatcher`）、`replaceRegex` 净化。
     *
     * 返回整章文本，段落以 `\n` 分隔；**未规范化**——缩进/换行统一归读取层的 `TextNormalizer`
     * （`lib_book_source` 不得反向依赖 lib_book_common，与原生路径同一分工）。
     * 空串是合法返回（调用方按失败处置、不落盘）。
     */
    suspend fun fetchChapterText(contentRef: String): String
}
```

`ScriptBookParser.kt`（骨架：五个 override 先给最小可编译形态，搜索/发现/详情/目录/正文分别由 Task 2~6 逐个替换为下文给出的完整实现——骨架行随对应 Task 提交时删除；本 Task 只让 `searchBook` 走通「触发惰性装载 → 返回空」使红灯用例可编译可断言）：

```kotlin
package com.ebook.source.analyze

import com.ebook.db.entity.BookShelfEntity
import com.ebook.db.entity.LibraryEntity
import com.ebook.db.entity.SearchBookEntity
import com.ebook.db.entity.WebChapterEntity
import com.ebook.source.script.ScriptPageFetcher
import com.ebook.source.script.ScriptRuleParseException
import com.ebook.source.script.ScriptRuleSet
import com.ebook.source.script.ScriptTransport
import com.ebook.source.script.OkHttpScriptTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * 脚本书源的 `BookParser` 实现：把 2a~2c 的词法/求值/取文层装配成 `BookParser` 的五个求值面。
 *
 * ## 装载是惰性的（getParserFor 契约的一部分）
 *
 * 构造只存原始 JSON，`ScriptRuleSet.load` 在**首次求值**才执行（`rules` 惰性字段）：
 * `getParserFor` 对脚本行**永不返回 null、也不在取 parser 时抛**——「行在但 JSON 坏」必须以
 * 求值期的类型化 `ScriptRuleParseException` 如实报（消息含「脚本书源 JSON 无法解析」），
 * 构造期抛会让缓存锁内炸出未预期异常、null 则会被调用方当成「书源已失效」（§12）。
 *
 * ## 构造可见性
 *
 * 生产构造（parserFactory 用）只收原始 JSON + `@Named("source")` 纯净客户端；测试构造收
 * `ScriptTransport` 假件断言「请求长什么样」。`internal` 主构造引用 internal 类型合法，
 * public 次构造签名只出现 public 类型——跨模块（lib_book_common）只用 public 次构造。
 */
class ScriptBookParser internal constructor(
    private val rawJson: String,
    transport: ScriptTransport,
) : BookParser, ScriptContentParser {

    constructor(rawJson: String, okHttpClient: OkHttpClient?) : this(
        rawJson,
        OkHttpScriptTransport(okHttpClient ?: error("okHttpClient 未注入（生产接线必须传真客户端）")),
    )

    /** 求值输入：惰性装载（理由见类 KDoc「装载是惰性的」） */
    internal val rules: ScriptRuleSet by lazy { ScriptRuleSet.load(rawJson) }

    /** 取文门面：sourceRoot 是书源 URL（= `rules.sourceUrl`，惰性联动） */
    internal val fetcher: ScriptPageFetcher by lazy {
        ScriptPageFetcher(okHttpClient = null, sourceRoot = rules.sourceUrl, transport = transport)
    }

    override suspend fun searchBook(content: String, page: Int): List<SearchBookEntity> =
        withContext(Dispatchers.IO) {
            rules.searchUrl ?: return@withContext emptyList()  // 未配搜索地址：非错误，本源无搜索
            emptyList()  // ← Task 2 填实（此行仅为骨架，Task 2 提交即删除）
        }

    override suspend fun getBookInfo(bookShelf: BookShelfEntity): BookShelfEntity =
        bookShelf  // ← Task 4 填实

    override suspend fun getChapterList(bookShelf: BookShelfEntity): WebChapterEntity<BookShelfEntity> =
        withContext(Dispatchers.IO) {
            bookShelf.chapterList = mutableListOf()
            WebChapterEntity(bookShelf, false)  // ← Task 5 填实
        }

    override suspend fun getKindBook(url: String, page: Int): List<SearchBookEntity> =
        emptyList()  // ← Task 3 填实

    override suspend fun fetchLibraryData(): LibraryEntity =
        LibraryEntity()  // ← Task 3 填实

    override suspend fun fetchChapterText(contentRef: String): String =
        ""  // ← Task 6 填实
}
```

`ScriptHttp.kt`：在 `ScriptPageFetcher` 内、`fetch` 之前加：

```kotlin
/** 一次取文的产物：请求落点的绝对地址 + 已按选项 charset 解码的响应文本（2d 加，供字段相对落位与翻页推进） */
internal data class ScriptPage(val url: String, val text: String)
```

并把 `fetch` 改为薄壳（2c 既有签名与用例零变化）：

```kotlin
    suspend fun fetch(
        ruleUrl: String,
        ctx: EvalContext,
        evaluateInner: (String) -> RuleResult,
    ): String = fetchPage(ruleUrl, ctx, evaluateInner).text

    /**
     * 取文并回传**请求落点地址**：调用方需要它做两件事——字段结果的相对落位基准（§6.5）与
     * 翻页循环的 `ctx.baseUrl` 推进。`fetch` 不回传地址是 2c 的形状（当时没有消费者），
     * 2d 起装配层一律走本方法。
     */
    suspend fun fetchPage(
        ruleUrl: String,
        ctx: EvalContext,
        evaluateInner: (String) -> RuleResult,
    ): ScriptPage {
        val resolved = ScriptUrlResolver.resolve(ruleUrl, ctx, sourceRoot, evaluateInner)
        val text = transport.execute(
            ScriptRequest(
                url = resolved.url,
                method = resolved.options.method,
                headers = resolved.options.headers,
                body = resolved.options.body,
                charset = resolved.options.charset,
                timeoutMs = resolved.options.timeoutMs,
                retry = resolved.options.retry,
            ),
        )
        return ScriptPage(resolved.url, text)
    }
```

`EvalContext.kt`：`val baseUrl: String = ""` 改 `var baseUrl: String = ""`，KDoc 追加一行：

```kotlin
 * [baseUrl] 是 `var`：翻页链每取回一页就把它推进到该页绝对地址——下一页字段结果的相对
 * 落位基准是「链接写在哪页、就相对那页」（与 `TocPageUrl.join` 的既有语义同一条）。
 * 变量表等其余状态不动：作用域仍是单次解析任务（§5.2）。
```

`ScriptRuleSet.kt`：类内追加访问器 + load 时顺手建表：

```kotlin
    /** 条目的原始 JSON 节点：数组等非常规形态字段的展开入口（`nextTocUrl`/`nextContentUrl` 的 URL 数组，§7.1/§7.2）；键缺失返回 null */
    fun jsonField(kind: RuleObjectKind, field: String): JsonElement? = rawObjects[kind]?.get(field)
```

```kotlin
    private val rawObjects: Map<RuleObjectKind, Map<String, JsonElement>> =
        RuleObjectKind.entries.associateWith { kind ->
            (root[kind.jsonKey] as? JsonObject)?.entries?.toMap() ?: emptyMap()
        }
```

（`load` 内已有 `obj`；`rawObjects` 建在 `objects` 旁，两者同源不同用途：`objects` 只收字符串规则，`rawObjects` 保原始节点。）

- [x] **Step 4: 运行测试通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest`
Expected: PASS——新 3 用例 + 既有 290 例零回归。

- [x] **Step 5: 红线 + 提交**

```bash
git grep -in "[l]egado" && echo "VIOLATION" || echo "CLEAN"
git add lib_book_source
git commit -m "$(cat <<'EOF'
feat(lib_book_source): 新增脚本解析器骨架与取文页地址回传

- ScriptBookParser/ScriptContentParser 双接缝落地，规则惰性装载保住
  getParserFor「脚本行不 null 不构造期抛」契约
- ScriptPageFetcher.fetchPage 回传请求落点供相对落位与翻页推进
- EvalContext.baseUrl 可变化；ScriptRuleSet 增 jsonField 展开数组形态字段
EOF
)"
```

---

### Task 2: 搜索链路——`searchBook`（searchUrl 渲染、条目提取、URL 落位、关键词编码）

**Files:**
- Modify: `lib_book_source/src/main/java/com/ebook/source/analyze/ScriptBookParser.kt`（searchBook 填实）
- Create: `lib_book_source/src/main/java/com/ebook/source/script/ScriptFieldExtractor.kt`
- Test: `lib_book_source/src/test/java/com/ebook/source/analyze/ScriptBookParserTest.kt`（追加）
- Test: `lib_book_source/src/test/java/com/ebook/source/script/ScriptFieldExtractorTest.kt`（新建）

- [x] **Step 1: 写失败的测试**

`ScriptBookParserTest.kt` 追加（`RecordingTransport` 已有；三引号内的 `{{key}}` 与 Kotlin 字符串模板无冲突——`{` 不是模板字符，直接写即可）：

```kotlin
    private val htmlSource = """
        {"bookSourceUrl":"https://www.example.com","bookSourceName":"示例",
         "searchUrl":"/search?key={{key}}&page={{page}}",
         "ruleSearch":{"bookList":"class.bookbox","name":"tag.h4@text",
           "bookUrl":"tag.a@href","author":"tag.p@text","coverUrl":"tag.img@src",
           "lastChapter":"tag.span@text","kind":"class.kind@text","intro":"class.intro@text"}}
    """.trimIndent()
```

```kotlin
    private val searchHtml = """
        <div class="bookbox"><h4><a href="/book/123.html">凡人修仙传</a></h4>
        <p>忘语</p><img src="/cover/123.jpg"/><span>第两千章</span>
        <i class="kind">仙侠</i><i class="intro">一个山村少年的逆袭</i></div>
        <div class="bookbox"><h4><a href="/book/456.html">凡人修仙之仙界篇</a></h4></div>
        <div class="bookbox"><h4><a href="">空链接的书</a></h4></div>
    """.trimIndent()

    @Test
    fun `HTML 链式源搜索出条目并完成相对落位与条目判据`() = runTest {
        val transport = RecordingTransport("https://www.example.com/search?key=%E5%87%A1%E4%BA%BA&page=1" to searchHtml)
        val parser = ScriptBookParser(htmlSource, transport)
        val books = parser.searchBook("凡人", 1)
        assertEquals(2, books.size)  // 第三条 bookUrl 为空：条目判据（§1.2）不收录
        val first = books.first()
        assertEquals("凡人修仙传", first.name)
        assertEquals("忘语", first.author)
        assertEquals("https://www.example.com/book/123.html", first.noteUrl)
        assertEquals("https://www.example.com/cover/123.jpg", first.coverUrl)
        assertEquals("第两千章", first.lastChapter)
        assertEquals("仙侠", first.kind)
        assertEquals("一个山村少年的逆袭", first.desc)
        assertEquals("https://www.example.com", first.tag)
        assertEquals("示例", first.origin)
    }

    @Test
    fun `中文关键词经 URLEncoder 编码进请求地址`() = runTest {
        val transport = RecordingTransport("https://www.example.com/search?key=%E5%87%A1%E4%BA%BA&page=1" to searchHtml)
        ScriptBookParser(htmlSource, transport).searchBook("凡人", 1)
        assertEquals(1, transport.requests.size)
        assertTrue(transport.requests.single().url.contains("key=%E5%87%A1%E4%BA%BA"))
    }

    @Test
    fun `JSONPath API 型源搜索出条目`() = runTest {
        val jsonSource = """{"bookSourceUrl":"https://api.example.com",
            "searchUrl":"https://api.example.com/books?kw={{key}}",
            "ruleSearch":{"bookList":"$.data.books","name":"$.name","author":"$.author","bookUrl":"$.url"}}"""
        val body = """{"data":{"books":[{"name":"凡人修仙传","author":"忘语","url":"https://api.example.com/b/1"}]}}"""
        val parser = ScriptBookParser(jsonSource, RecordingTransport("https://api.example.com/books?kw=%E5%87%A1%E4%BA%BA" to body))
        val books = parser.searchBook("凡人", 1)
        assertEquals(listOf("凡人修仙传"), books.map { it.name })
        assertEquals("https://api.example.com/b/1", books.single().noteUrl)
    }

    @Test
    fun `POST 选项把表单体带进请求`() = runTest {
        val postSource = """{"bookSourceUrl":"https://www.example.com",
            "searchUrl":"/search.php,{\"method\":\"POST\",\"body\":\"key={{key}}&page={{page}}\"}",
            "ruleSearch":{"bookList":"class.bookbox","name":"tag.h4@text","bookUrl":"tag.a@href"}}"""
        val transport = RecordingTransport("https://www.example.com/search.php" to searchHtml)
        ScriptBookParser(postSource, transport).searchBook("凡人", 2)
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("key=%E5%87%A1%E4%BA%BA&page=2", request.body)
    }

    @Test
    fun `bookUrl 携带选项尾段时原样保留在 noteUrl 上`() = runTest {
        // 尾段是「下一次取文的输入」的一部分（§9 第 7 步）：字段落位只处理地址部分，
        // 尾段回附，详情页取文时由 ScriptUrlResolver 消费
        val source = """{"bookSourceUrl":"https://www.example.com",
            "searchUrl":"/s","ruleSearch":{"bookList":"class.bookbox",
            "name":"tag.h4@text","bookUrl":"tag.a@href"}}"""
        val html = """<div class="bookbox"><h4><a href="/book/123.html,{\"charset\":\"gbk\"}">书</a></h4></div>"""
        val books = ScriptBookParser(source, RecordingTransport("https://www.example.com/s" to html)).searchBook("k", 1)
        assertEquals("https://www.example.com/book/123.html,{\"charset\":\"gbk\"}", books.single().noteUrl)
    }

    @Test
    fun `未配 searchUrl 的源搜索返回空列表而不是报错`() = runTest {
        val source = """{"bookSourceUrl":"https://www.example.com","bookSourceName":"无搜索"}"""
        assertTrue(ScriptBookParser(source, RecordingTransport()).searchBook("凡人", 1).isEmpty())
    }
```

`ScriptFieldExtractorTest.kt`（锁条目分派与落位口径）：

```kotlin
package com.ebook.source.script

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptFieldExtractorTest {

    @Test
    fun `resolveUrl 剥尾段落位后回附`() {
        val extractor = ScriptFieldExtractor(ScriptRuleEvaluator(EvalContext()), "https://root.com")
        assertEquals("https://root.com/book/1", extractor.resolveUrl("/book/1", "https://root.com/dir/page.html"))
        assertEquals("https://root.com/book/1,{\"charset\":\"gbk\"}",
            extractor.resolveUrl("/book/1,{\"charset\":\"gbk\"}", "https://root.com/dir/page.html"))
        assertEquals("https://other.com/x", extractor.resolveUrl("https://other.com/x", "https://root.com/dir/page.html"))
    }

    @Test
    fun `listItems 把四类列表结果各分成条目上下文`() {
        val extractor = ScriptFieldExtractor(ScriptRuleEvaluator(EvalContext()), "https://root.com")
        val doc = Jsoup.parse("<ul><li>a</li><li>b</li></ul>")
        assertEquals(2, extractor.listItems(RuleResult.Nodes(doc.select("li"))).size)
        assertEquals(2, extractor.listItems(RuleResult.Matches(listOf(listOf("x", "1"), listOf("y", "2")))).size)
        assertEquals(1, extractor.listItems(RuleResult.Texts(listOf("t"))).size)
        assertEquals(0, extractor.listItems(RuleResult.Miss).size)
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.analyze.ScriptBookParserTest" --tests "com.ebook.source.script.ScriptFieldExtractorTest"`
Expected: FAIL——`ScriptFieldExtractor` 未定义、`resolveUrl`/`listItems`/`fetchPage` 装配缺失。

- [x] **Step 3: 实现 `ScriptFieldExtractor` 与 `searchBook`**

`ScriptFieldExtractor.kt`：

```kotlin
package com.ebook.source.script

import kotlinx.serialization.json.JsonElement
import org.jsoup.nodes.Element

/**
 * 列表字段 → 条目上下文 → 逐字段取值（规格 §9 第 5 步「每支的输入 = 当前上下文文档/文本」的装配件）。
 *
 * 与 [ScriptRuleEvaluator] 的分工：求值器答「一条规则对一个输入解出什么」；本类答
 * 「列表结果怎么变成条目、条目里的字段规则喂哪个输入、URL 类字段怎么落位」——
 * 搜索/发现/详情/目录四个链路共用，各写一份就会出现「同一个 bookList 结果两种条目口径」。
 */
internal class ScriptFieldExtractor(
    private val evaluator: ScriptRuleEvaluator,
    private val sourceRoot: String,
) {

    /** 一个条目的求值上下文：四类列表结果各走各的字段入口（`evaluate`/`evaluateOnItem`/`evaluateOnJsonItem`） */
    internal sealed interface ItemContext {
        data class ElementCtx(val element: Element) : ItemContext
        data class GroupsCtx(val groups: List<String>) : ItemContext
        data class JsonCtx(val node: JsonElement) : ItemContext
        data class TextCtx(val text: String) : ItemContext
    }

    /** 列表字段的求值结果 → 条目上下文集；[RuleResult.Miss] 即零条目（§3.1 未取到值独立事实） */
    fun listItems(result: RuleResult): List<ItemContext> = when (result) {
        RuleResult.Miss -> emptyList()
        is RuleResult.Nodes -> result.elements.map { ItemContext.ElementCtx(it) }
        is RuleResult.Matches -> result.items.map { ItemContext.GroupsCtx(it) }
        is RuleResult.Jsons -> result.items.map { ItemContext.JsonCtx(it) }
        is RuleResult.Texts -> result.values.map { ItemContext.TextCtx(it) }
    }

    /**
     * 条目 × 字段规则 → 单值（§11-2 收敛口径：firstText，没有则空串）。
     * 字段规则空白直接给空串：空规则在求值器里本就是 Miss，这里省一次切分开销且语义相同。
     */
    fun fieldText(item: ItemContext, fieldRule: String): String {
        if (fieldRule.isBlank()) return ""
        return when (item) {
            is ItemContext.ElementCtx -> evaluator.evaluate(fieldRule, RuleValue.Nodes(listOf(item.element)))
            is ItemContext.GroupsCtx -> evaluator.evaluateOnItem(fieldRule, item.groups)
            is ItemContext.JsonCtx -> evaluator.evaluateOnJsonItem(fieldRule, item.node)
            is ItemContext.TextCtx -> evaluator.evaluate(fieldRule, RuleValue.Texts(listOf(item.text)))
        }.firstText()
    }

    /** URL 类字段：取值后按 §9 第 7 步落位（尾段回附，见 [resolveUrl]） */
    fun fieldUrl(item: ItemContext, fieldRule: String, baseUrl: String): String {
        val raw = fieldText(item, fieldRule)
        return if (raw.isBlank()) "" else resolveUrl(raw, baseUrl)
    }

    /**
     * URL 字段落位（§6.5）：先剥选项尾段 → `TocPageUrl.join` 三形态落位 → 回附尾段。
     * 尾段是**下一次取文输入**的一部分（gbk/POST 选项跟 URL 走），字段落位只处理地址部分；
     * 不剥就落位会把 `,{...}` 当成路径段拼出 404。
     */
    fun resolveUrl(raw: String, baseUrl: String): String {
        val (url, tail) = ScriptUrlOption.splitTail(raw) ?: (raw to null)
        val absolute = com.ebook.source.analyze.TocPageUrl.join(baseUrl, url, sourceRoot)
        return if (tail == null) absolute else absolute + tail
    }
}
```

`ScriptBookParser.searchBook` 填实（并加两个私有助手，供 Task 3/4/5 复用）：

```kotlin
    override suspend fun searchBook(content: String, page: Int): List<SearchBookEntity> =
        withContext(Dispatchers.IO) {
            val searchUrl = rules.searchUrl ?: return@withContext emptyList()
            val ctx = EvalContext(
                baseUrl = sourceBase(),
                // {{key}} 展开值按表单百分号编码（本仓规定，登记 §11）：不编码时中文关键词
                // 在 OkHttp HttpUrl 里直接非法；编码错不如不编码坏的根源在「上游是否自行编码」无文档
                key = tryEncodeKey(content),
                page = page,
            )
            val evaluator = ScriptRuleEvaluator(ctx)
            val extractor = ScriptFieldExtractor(evaluator, rules.sourceUrl)
            val pageData = fetchPage(searchUrl, ctx, evaluator)
            ctx.baseUrl = pageData.url
            parseBookList(pageData.text, ctx, evaluator, RuleObjectKind.SEARCH, fallbackKind = null)
        }

    /** 源根作为目录形态的落位基准：裸相对 searchUrl（无 `/` 前缀）按「源根目录 + 相对段」落位 */
    private fun sourceBase(): String =
        if (rules.sourceUrl.endsWith("/")) rules.sourceUrl else rules.sourceUrl + "/"

    private fun tryEncodeKey(keyword: String): String = try {
        java.net.URLEncoder.encode(keyword, "UTF-8")
    } catch (e: Exception) {
        keyword
    }

    /**
     * 列表页 → 书籍条目（搜索与发现共用；发现无 bookList 时回落搜索字段，语料里两对象同构，§1.3）。
     * 条目判据（§1.2 本仓规定）：`name` 与 `bookUrl` 都非空才收录——其余字段缺失只留空。
     */
    internal fun parseBookList(
        pageText: String,
        ctx: EvalContext,
        evaluator: ScriptRuleEvaluator,
        kind: RuleObjectKind,
        fallbackKind: RuleObjectKind?,
    ): List<SearchBookEntity> {
        fun fieldRule(name: String): String =
            rules.rule(kind, name)?.takeIf { it.isNotBlank() }
                ?: fallbackKind?.let { rules.rule(it, name) }.orEmpty()

        val listRule = rules.rule(kind, "bookList")?.takeIf { it.isNotBlank() }
            ?: fallbackKind?.let { rules.rule(it, "bookList") }?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        val extractor = ScriptFieldExtractor(evaluator, rules.sourceUrl)
        val items = extractor.listItems(evaluator.evaluate(listRule, RuleValue.Page(pageText)))
        val books = mutableListOf<SearchBookEntity>()
        for (item in items) {
            val name = extractor.fieldText(item, fieldRule("name"))
            val bookUrlRaw = extractor.fieldText(item, fieldRule("bookUrl"))
            if (name.isBlank() || bookUrlRaw.isBlank()) continue
            books += SearchBookEntity().apply {
                this.name = name
                author = extractor.fieldText(item, fieldRule("author"))
                noteUrl = extractor.fieldUrl(item, fieldRule("bookUrl"), ctx.baseUrl)
                coverUrl = extractor.fieldUrl(item, fieldRule("coverUrl"), ctx.baseUrl)
                lastChapter = extractor.fieldText(item, fieldRule("lastChapter"))
                kind = extractor.fieldText(item, fieldRule("kind"))
                desc = extractor.fieldText(item, fieldRule("intro"))
                tag = rules.sourceUrl
                origin = rules.name
            }
        }
        return books
    }

    /** 取文 + `@@规则` 递归求值闭包（§5.1：URL 插值里的 `@@` 规则跑在当前页上） */
    private suspend fun fetchPage(
        ruleUrl: String,
        ctx: EvalContext,
        evaluator: ScriptRuleEvaluator,
    ): ScriptPage = fetcher.fetchPage(ruleUrl, ctx) { inner ->
        evaluator.evaluate(inner, RuleValue.Page(""))  // 取文前的 URL 没有页面上下文：@@ 递归按空文档求值
    }
```

（注意 `searchBook` 里 `tryEncodeKey` 与 `sourceBase` 只在 URL 任务上有意义；`EvalContext.key` 的 KDoc 保持「搜索关键词」语义。）

- [x] **Step 4: 运行测试通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest`
Expected: PASS——新增 8 例，既有零回归。

- [x] **Step 5: 红线 + 提交**

```bash
git grep -in "[l]egado" && echo "VIOLATION" || echo "CLEAN"
git add lib_book_source
git commit -m "$(cat <<'EOF'
feat(lib_book_source): 脚本书源搜索链路（searchUrl 渲染与条目提取）

- ScriptFieldExtractor 分派四类列表结果为条目上下文，URL 字段剥尾段落位后回附
- {{key}} 展开值按表单百分号编码（本仓规定，登记规格 §11 待语料验证）
- 条目判据与实体映射对齐原生链路（name/bookUrl 双非空才收录）
EOF
)"
```

---

### Task 3: 发现链路——`fetchLibraryData` 与 `getKindBook`

**Files:**
- Modify: `lib_book_source/src/main/java/com/ebook/source/analyze/ScriptBookParser.kt`
- Test: `lib_book_source/src/test/java/com/ebook/source/analyze/ScriptBookParserTest.kt`（追加发现用例）

- [x] **Step 1: 写失败的测试**

```kotlin
    private val exploreSource = """
        {"bookSourceUrl":"https://www.example.com","bookSourceName":"示例",
         "exploreUrl":"男生书库::/shuku/0_1_0_0_0_{{page}}_0_0\n男频连载::/shuku/0_2_0_0_0_{{page}}_0_0",
         "ruleExplore":{"bookList":"class.bookbox","name":"tag.h4@text","bookUrl":"tag.a@href"}}
    """.trimIndent()

    @Test
    fun `fetchLibraryData 逐分类抓首页并按空区块容忍单分类失败`() = runTest {
        val transport = RecordingTransport(
            "https://www.example.com/shuku/0_1_0_0_0_1_0_0" to searchHtml,   // 第 1 页 {{page}}=1
            "https://www.example.com/shuku/0_2_0_0_0_1_0_0" to "",            // 第 2 分类空响应
        )
        val entity = ScriptBookParser(exploreSource, transport).fetchLibraryData()
        val blocks = entity.kindBooks.orEmpty()
        assertEquals(2, blocks.size)
        assertEquals("男生书库", blocks[0].title)
        assertEquals(2, blocks[0].books.size)
        assertTrue(blocks[1].books.isEmpty())
    }

    @Test
    fun `getKindBook 按 urlRule 与页码取分类页并支持 ruleExplore 回落 ruleSearch`() = runTest {
        val source = """{"bookSourceUrl":"https://www.example.com",
            "exploreUrl":"书库::/list","ruleSearch":{"bookList":"class.bookbox","name":"tag.h4@text","bookUrl":"tag.a@href"}}"""
        val transport = RecordingTransport("https://www.example.com/list" to searchHtml)
        // ruleExplore 未配：回落 ruleSearch 字段（语料同构，§1.3）
        val books = ScriptBookParser(source, transport).getKindBook("/list", 1)
        assertEquals(2, books.size)
    }

    @Test
    fun `未配 exploreUrl 的源书库为空实体而不是错误`() = runTest {
        val source = """{"bookSourceUrl":"https://www.example.com","bookSourceName":"无发现"}"""
        val entity = ScriptBookParser(source, RecordingTransport()).fetchLibraryData()
        assertNull(entity.kindBooks)
    }
```

- [x] **Step 2: 运行确认失败**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.analyze.ScriptBookParserTest"`
Expected: FAIL——三个方法仍是骨架。

- [x] **Step 3: 实现**

```kotlin
    override suspend fun getKindBook(url: String, page: Int): List<SearchBookEntity> =
        withContext(Dispatchers.IO) {
            if (url.isBlank()) return@withContext emptyList()
            try {
                loadExplorePage(url, page)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 与原生 getKindBook 同形：分类页失败按空列表返回，页面按「没数据」处置
                Logger.w("ScriptBookParser[${rules.name}]", "getKindBook 失败: url=$url", e)
                emptyList()
            }
        }

    override suspend fun fetchLibraryData(): LibraryEntity = withContext(Dispatchers.IO) {
        val exploreUrl = rules.exploreUrl ?: return@withContext LibraryEntity()  // 未配发现 = 配置形态非错误（对齐原生 kinds 为空）
        val entries = ExploreUrlFormat.split(exploreUrl)
        if (entries.isEmpty()) return@withContext LibraryEntity()
        val blocks = mutableListOf<LibraryKindBookListEntity>()
        for (entry in entries) {
            try {
                blocks += LibraryKindBookListEntity(entry.title, entry.urlRule, loadExplorePage(entry.urlRule, 1))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 单分类失败按空区块计入（对齐原生 fetchLibraryData）：书城少一个分类仍是可用页面
                Logger.w("ScriptBookParser[${rules.name}]", "发现分类抓取失败: ${entry.title}", e)
                blocks += LibraryKindBookListEntity(entry.title, "", emptyList())
            }
        }
        LibraryEntity().apply { kindBooks = blocks }
    }

    /** 发现页 URL 规则串 → 分类页条目（GET/{{page}} 初值 1；书城接线后由 2e 的调用方消费） */
    private suspend fun loadExplorePage(urlRule: String, page: Int): List<SearchBookEntity> {
        val ctx = EvalContext(baseUrl = sourceBase(), page = page)
        val evaluator = ScriptRuleEvaluator(ctx)
        val pageData = fetchPage(urlRule, ctx, evaluator)
        ctx.baseUrl = pageData.url
        return parseBookList(pageData.text, ctx, evaluator, RuleObjectKind.EXPLORE, fallbackKind = RuleObjectKind.SEARCH)
    }
```

- [x] **Step 4: 运行测试通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest`
Expected: PASS——新增 3 例。

- [x] **Step 5: 红线 + 提交**

```bash
git grep -in "[l]egado" && echo "VIOLATION" || echo "CLEAN"
git add lib_book_source
git commit -m "$(cat <<'EOF'
feat(lib_book_source): 脚本书源发现链路（exploreUrl 分类与分类页）

- ExploreUrlFormat 条目逐分类抓首页，单分类失败按空区块（对齐原生口径）
- ruleExplore 未配 bookList 时回落 ruleSearch 字段（两对象语料同构）
EOF
)"
```

---

### Task 4: 详情链路——`getBookInfo`（ruleBookInfo、init 预处理、tocUrl）

**Files:**
- Modify: `lib_book_source/src/main/java/com/ebook/source/analyze/ScriptBookParser.kt`
- Test: `lib_book_source/src/test/java/com/ebook/source/analyze/ScriptBookParserTest.kt`（追加）

- [x] **Step 1: 写失败的测试**

```kotlin
    @Test
    fun `详情字段逐条求值并完成 tocUrl 相对落位`() = runTest {
        val source = """{"bookSourceUrl":"https://www.example.com",
            "ruleBookInfo":{"name":"tag.h1@text","author":"class.info@text",
              "intro":"class.intro@text","coverUrl":"tag.img@src","tocUrl":"class.catalog@href"}}"""
        val detailHtml = """
            <h1>凡人修仙传</h1><i class="info">忘语</i>
            <i class="intro">仙侠巨著</i><img src="/cover/123.jpg"/>
            <a class="catalog" href="/book/123/toc.html">目录</a>
        """.trimIndent()
        val parser = ScriptBookParser(source, RecordingTransport("https://www.example.com/book/123.html" to detailHtml))
        val shelf = BookShelfEntity().apply { noteUrl = "https://www.example.com/book/123.html" }
        val result = parser.getBookInfo(shelf)
        val info = result.bookInfo!!
        assertEquals("凡人修仙传", info.name)
        assertEquals("忘语", info.author)
        assertEquals("仙侠巨著", info.introduce)
        assertEquals("https://www.example.com/cover/123.jpg", info.coverUrl)
        assertEquals("https://www.example.com/book/123/toc.html", info.chapterUrl)
        assertEquals("https://www.example.com", info.tag)
    }

    @Test
    fun `tocUrl 未配置时回落详情页地址`() = runTest {
        val source = """{"bookSourceUrl":"https://www.example.com",
            "ruleBookInfo":{"name":"tag.h1@text"}}"""
        val parser = ScriptBookParser(source, RecordingTransport("https://www.example.com/book/123.html" to "<h1>x</h1>"))
        val result = parser.getBookInfo(BookShelfEntity().apply { noteUrl = "https://www.example.com/book/123.html" })
        assertEquals("https://www.example.com/book/123.html", result.bookInfo!!.chapterUrl)
    }

    @Test
    fun `intro 为空时与原生同形填暂无简介`() = runTest {
        val source = """{"bookSourceUrl":"https://www.example.com","ruleBookInfo":{"name":"tag.h1@text"}}"""
        val parser = ScriptBookParser(source, RecordingTransport("https://www.example.com/b.html" to "<h1>x</h1>"))
        assertEquals("暂无简介", parser.getBookInfo(BookShelfEntity().apply { noteUrl = "https://www.example.com/b.html" }).bookInfo!!.introduce)
    }

    @Test
    fun `init 的 AllInOne 产物作为条目上下文供字段组引用`() = runTest {
        // TXT 流详情页：init 用 AllInOne 正则从整页切出字段，后续字段规则以 $n 组引用（§2.4 组引用）。
        // `[^作]*` 截住第一组防贪婪吞掉第二组——合成语料的取舍，锁的是「组引用走 init 条目上下文」这个机制
        val source = """{"bookSourceUrl":"https://www.example.com",
            "ruleBookInfo":{"init":":(书名[^作]*)(作者.*)","name":"$1","author":"$2"}}"""
        val html = "书名凡人修仙传作者忘语"
        val parser = ScriptBookParser(source, RecordingTransport("https://www.example.com/b.html" to html))
        val info = parser.getBookInfo(BookShelfEntity().apply { noteUrl = "https://www.example.com/b.html" }).bookInfo!!
        assertEquals("书名凡人修仙传", info.name)
        assertEquals("作者忘语", info.author)
    }
```

（Kotlin 字符串里 `$1` 是字面量——`$` 后跟数字不构成模板（标识符不能以数字开头），无需转义。）

- [x] **Step 2: 运行确认失败**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.analyze.ScriptBookParserTest"`
Expected: FAIL——`getBookInfo` 仍是骨架。

- [x] **Step 3: 实现**

```kotlin
    override suspend fun getBookInfo(bookShelf: BookShelfEntity): BookShelfEntity =
        withContext(Dispatchers.IO) {
            rules  // 触发惰性装载：坏 JSON 在此抛类型化异常（§12）
            bookShelf.tag = rules.sourceUrl
            val bookInfo = BookInfoEntity()
            bookInfo.noteUrl = bookShelf.noteUrl
            bookInfo.tag = rules.sourceUrl
            bookInfo.origin = rules.name

            val ctx = EvalContext(baseUrl = stripTail(bookShelf.noteUrl))
            val evaluator = ScriptRuleEvaluator(ctx)
            val extractor = ScriptFieldExtractor(evaluator, rules.sourceUrl)
            val pageData = fetchPage(bookShelf.noteUrl, ctx, evaluator)
            ctx.baseUrl = pageData.url
            val input = RuleValue.Page(pageData.text)

            // init 预处理（§1.4）：AllInOne 以首个匹配为条目上下文（本仓规定，登记 §11——
            // 「字段=对象键」读法属 JS 分支，Plan 3）；JS 形态在 evaluate 内类型化穿透
            val initRule = rules.rule(RuleObjectKind.BOOK_INFO, "init").orEmpty()
            val initItem = initRule.takeIf { it.isNotBlank() }
                ?.let { evaluator.evaluate(it, input) }
                ?.let { it as? RuleResult.Matches }
                ?.items?.firstOrNull()
                ?.let { ScriptFieldExtractor.ItemContext.GroupsCtx(it) }

            fun field(name: String): String = when {
                initItem != null -> extractor.fieldText(initItem, rules.rule(RuleObjectKind.BOOK_INFO, name).orEmpty())
                else -> evaluator.evaluate(rules.rule(RuleObjectKind.BOOK_INFO, name).orEmpty(), input).firstText()
            }

            // canReName（§1.4）在本管道无行为差：入参 shelf 不携带搜索值（addFromSearch 只装
            // noteUrl/tag），字段解出即填——与原生 getBookInfo 的无条件覆盖同形，规格 §11 登记
            bookInfo.name = field("name")
            bookInfo.author = field("author")
            bookInfo.introduce = field("intro").ifBlank { "暂无简介" }
            bookInfo.coverUrl = extractor.resolveUrl(field("coverUrl"), ctx.baseUrl)
            val tocRaw = field("tocUrl")
            // tocUrl 只支持单个 URL（§1.4）；为空回落详情页 URL
            bookInfo.chapterUrl = if (tocRaw.isBlank()) bookShelf.noteUrl else extractor.resolveUrl(tocRaw, ctx.baseUrl)

            bookShelf.bookInfo = bookInfo
            bookShelf
        }

    /** 以 URL 为落位基准前先剥选项尾段：尾段不是地址的一部分 */
    private fun stripTail(url: String): String =
        ScriptUrlOption.splitTail(url)?.first ?: url
```

- [x] **Step 4: 运行测试通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest`
Expected: PASS——新增 4 例。

- [x] **Step 5: 红线 + 提交**

```bash
git grep -in "[l]egado" && echo "VIOLATION" || echo "CLEAN"
git add lib_book_source
git commit -m "$(cat <<'EOF'
feat(lib_book_source): 脚本书源详情链路（ruleBookInfo 与 init 预处理）

- init 的 AllInOne 产物以首个匹配为条目上下文，字段经组引用取值（登记 §11）
- tocUrl 相对落位、为空回落详情页地址；空 intro 与原生同形填「暂无简介」
EOF
)"
```

---

### Task 5: 目录链路——`getChapterList` 与 `ScriptTocPager`（含 `ScriptPageChain`）

**Files:**
- Modify: `lib_book_source/src/main/java/com/ebook/source/analyze/ScriptBookParser.kt`
- Create: `lib_book_source/src/main/java/com/ebook/source/script/ScriptPageChain.kt`
- Create: `lib_book_source/src/main/java/com/ebook/source/script/ScriptTocPager.kt`
- Test: `lib_book_source/src/test/java/com/ebook/source/script/ScriptTocPagerTest.kt`（新建）

- [x] **Step 1: 写失败的测试**

`ScriptTocPagerTest.kt`——纯逻辑假件（fetch 闭包喂假页表，无需 transport）：

```kotlin
package com.ebook.source.script

import com.ebook.db.entity.ChapterListEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptTocPagerTest {

    private fun rulesOf(tocJson: String): ScriptRuleSet =
        ScriptRuleSet.load("""{"bookSourceUrl":"https://a.com","ruleToc":$tocJson}""")

    private fun pagerOf(set: ScriptRuleSet, pages: Map<String, String>): Pair<ScriptTocPager, EvalContext> {
        val ctx = EvalContext(baseUrl = "https://a.com/toc.html")
        val evaluator = ScriptRuleEvaluator(ctx)
        val extractor = ScriptFieldExtractor(evaluator, "https://a.com")
        val pager = ScriptTocPager(extractor, set) { url ->
            ScriptPage(url, pages[ScriptUrlOption.splitTail(url)?.first ?: url] ?: "")
        }
        return pager to ctx
    }

    private val onePageToc = """
        <ul><li><a href="/c1.html">第一章</a></li><li><a href="/c2.html">第二章</a></li></ul>
    """.trimIndent()

    @Test
    fun `单页目录按出现顺序编号`() = runTest {
        val set = rulesOf("""{"chapterList":"tag.li","chapterName":"tag.a@text","chapterUrl":"tag.a@href"}""")
        val (pager, ctx) = pagerOf(set, mapOf("https://a.com/toc.html" to onePageToc))
        val chapters = pager.collect("https://a.com/toc.html", ctx, noteUrl = "https://a.com/b.html", tag = "https://a.com")
        assertEquals(listOf("第一章", "第二章"), chapters.map { it.durChapterName })
        assertEquals(0, chapters.first().durChapterIndex)
        assertEquals("https://a.com/c2.html", chapters[1].contentRef)
    }

    @Test
    fun `字符串规则 nextTocUrl 逐页跟进且相对链接按当前页落位`() = runTest {
        val set = rulesOf("""{"chapterList":"tag.li","chapterName":"tag.a@text","chapterUrl":"tag.a@href",
            "nextTocUrl":"class.next@href"}""")
        val page1 = onePageToc + """<a class="next" href="toc_2.html">下一页</a>"""
        val page2 = """<ul><li><a href="/c3.html">第三章</a></li></ul>"""  // 无 next：末页
        val (pager, ctx) = pagerOf(
            set,
            mapOf("https://a.com/toc.html" to page1, "https://a.com/toc_2.html" to page2),
        )
        val chapters = pager.collect("https://a.com/toc.html", ctx, noteUrl = "n", tag = "t")
        assertEquals(3, chapters.size)
        assertEquals("https://a.com/c3.html", chapters[2].contentRef)
    }

    @Test
    fun `数组形态 nextTocUrl 按固定页序访问且入口重复元素跳过不终止`() = runTest {
        val set = rulesOf("""{"chapterList":"tag.li","chapterName":"tag.a@text","chapterUrl":"tag.a@href",
            "nextTocUrl":["https://a.com/toc.html","https://a.com/toc_2.html"]}""")
        val (pager, ctx) = pagerOf(
            set,
            mapOf("https://a.com/toc.html" to onePageToc, "https://a.com/toc_2.html" to """<ul><li><a href="/c3.html">第三章</a></li></ul>"""),
        )
        val chapters = pager.collect("https://a.com/toc.html", ctx, noteUrl = "n", tag = "t")
        assertEquals(3, chapters.size)  // 数组首元素=入口：跳过已访问的它，继续第二元素
    }

    @Test
    fun `软404 页零新增即到底不继续翻页`() = runTest {
        val set = rulesOf("""{"chapterList":"tag.li","chapterName":"tag.a@text","chapterUrl":"tag.a@href",
            "nextTocUrl":"class.next@href"}""")
        val page1 = onePageToc + """<a class="next" href="toc_2.html">下一页</a>"""
        val (pager, ctx) = pagerOf(
            set,
            mapOf("https://a.com/toc.html" to page1, "https://a.com/toc_2.html" to onePageToc),  // 重复首页内容
        )
        val chapters = pager.collect("https://a.com/toc.html", ctx, noteUrl = "n", tag = "t")
        assertEquals(2, chapters.size)  // 第二页零新增：到底（§12 判据复用）
    }

    @Test
    fun `chapterList 前导负号把条目集整体反序`() = runTest {
        val set = rulesOf("""{"chapterList":"-tag.li","chapterName":"tag.a@text","chapterUrl":"tag.a@href"}""")
        val (pager, ctx) = pagerOf(set, mapOf("https://a.com/toc.html" to onePageToc))
        val chapters = pager.collect("https://a.com/toc.html", ctx, noteUrl = "n", tag = "t")
        assertEquals(listOf("第二章", "第一章"), chapters.map { it.durChapterName })
    }

    @Test
    fun `chapterUrl 为空的条目跳过且 contentRef 跨页去重`() = runTest {
        val set = rulesOf("""{"chapterList":"tag.li","chapterName":"tag.a@text","chapterUrl":"tag.a@href"}""")
        val html = """<ul><li><a>无链接章</a></li><li><a href="/c1.html">第一章</a></li></ul>"""
        val (pager, ctx) = pagerOf(set, mapOf("https://a.com/toc.html" to html))
        val chapters = pager.collect("https://a.com/toc.html", ctx, noteUrl = "n", tag = "t")
        assertEquals(1, chapters.size)
    }
```

- [x] **Step 2: 运行确认失败**

Run: `./gradlew :lib_book_source:compileDebugUnitTestKotlin`
Expected: FAIL——`ScriptTocPager`/`ScriptPageChain` 未定义。

- [x] **Step 3: 实现 `ScriptPageChain` 与 `ScriptTocPager`**

`ScriptPageChain.kt`：

```kotlin
package com.ebook.source.script

/**
 * 翻页计划器：`nextTocUrl`/`nextContentUrl` 共用的「字符串规则 × URL 数组 × 回环防护」取下一页逻辑
 * （§7.1/§7.2 两种形态同一套口径）。收成一处是因为目录与正文各自的判定一旦漂移，
 * 「同一个 nextTocUrl 写法两种翻法」这类错乱无从定位（与 `ChapterPageMatcher` 收口同一理由）。
 *
 * 两种形态：
 * - **数组形态**（字段值是 JSON 数组）：固定页序一次给出——逐个跳过已访问元素（入口常是
 *   数组首元素，跳过而不是终止），耗尽即停；
 * - **字符串规则形态**：每页求值一次，空/`null` 即停（§7.1 停止条件），候选已访问（回环）即停。
 *
 * 访问键**剥掉选项尾段**再比：同一地址带不同选项（`X` 与 `X,{"charset":"gbk"}`）是同一页，
 * 不剥会让数组元素「看似没访问过」而重复请求、零新增又把链掐断。
 */
internal class ScriptPageChain(
    entryUrl: String,
    private val nextRule: String,
    private val nextArray: List<String>,
    private val extractor: ScriptFieldExtractor,
) {
    private val visited = mutableSetOf(visitKeyOf(entryUrl))
    private var arrayIndex = 0

    /** 已访问页数（含入口）：调用方的页数上限判定依据（正文链的 [ScriptContentPager.MAX_CONTENT_PAGES]） */
    fun visitedCount(): Int = visited.size

    /** 产出下一个未访问页地址；null = 链终止（无候选 / 回环 / 数组耗尽）。[pageUrl] 是当前页地址（相对落位基准） */
    fun nextOf(input: RuleValue, pageUrl: String): String? {
        val candidate: String? = when {
            nextArray.isNotEmpty() -> {
                while (arrayIndex < nextArray.size && visitKeyOf(nextArray[arrayIndex]) in visited) arrayIndex++
                nextArray.getOrNull(arrayIndex++)?.also { visited += visitKeyOf(it) }
            }
            nextRule.isNotBlank() -> {
                val raw = extractor.evaluateList(nextRule, input).firstText()
                if (raw.isBlank()) null
                else extractor.resolveUrl(raw, pageUrl).takeIf { visited.add(visitKeyOf(it)) }
            }
            else -> null
        }
        return candidate
    }

    private fun visitKeyOf(url: String): String = ScriptUrlOption.splitTail(url)?.first ?: url
}
```

`ScriptFieldExtractor` 补一个求值转发入口（与 `listItems`/`fieldText` 同居一处，翻页链与列表链都经它拿求值器——调用方不必同时持有两件）：

```kotlin
    /** 规则求值转发：翻页链的 nextTocUrl/nextContentUrl 求值与列表字段同一条路（§9 第 5 步） */
    fun evaluateList(rule: String, input: RuleValue): RuleResult = evaluator.evaluate(rule, input)
```

`ScriptTocPager.kt`：

```kotlin
package com.ebook.source.script

import com.ebook.db.entity.ChapterListEntity
import com.xrn1997.common.util.Logger

/**
 * 脚本书源的目录翻页链（§7.1）。终止判据与上限策略**与原生 `TocPager` 同一套**（§12）：
 * contentRef 跨页去重、零新增即到底（软 404 与末页同判）、回环即停、[MAX_TOC_CHAPTERS]
 * 触顶按截断记日志不抛。
 *
 * 与原生 `TocPager` 的差异只有驱动方式：原生是 `nextPage` 选择器/`pageUrl` 模板，
 * 脚本是 `nextTocUrl` 的字符串规则/URL 数组（经 [ScriptPageChain]）。判定「这页属不属于
 * 目录」的依据两边各自独立，不得互相移植口径。
 *
 * 求值取经 [fetchPage] 闭包注入：单测用假页表覆盖分页/终止/触顶各形态，无需 transport。
 */
internal class ScriptTocPager(
    private val extractor: ScriptFieldExtractor,
    private val rules: ScriptRuleSet,
    private val fetchPage: suspend (String) -> ScriptPage,
) {
    companion object {
        /** 目录防御上限（章），与原生 `TocPager.MAX_TOC_CHAPTERS` 同值同理由 */
        const val MAX_TOC_CHAPTERS = 20_000
    }

    suspend fun collect(
        entryUrl: String,
        ctx: EvalContext,
        noteUrl: String,
        tag: String,
    ): MutableList<ChapterListEntity> {
        val chapters = mutableListOf<ChapterListEntity>()
        val seenRefs = mutableSetOf<String>()
        val chain = ScriptPageChain(
            entryUrl = entryUrl,
            nextRule = rules.rule(RuleObjectKind.TOC, "nextTocUrl").orEmpty(),
            nextArray = rules.nextUrlArray(RuleObjectKind.TOC, "nextTocUrl"),
            extractor = extractor,
        )
        var current: String? = entryUrl
        while (current != null) {
            val sizeBefore = chapters.size
            val page = fetchPage(current)
            ctx.baseUrl = page.url  // 字段相对落位推进到当前页（§6.5「链接写在哪页就相对哪页」）
            val input = RuleValue.Page(page.text)
            val items = extractor.listItems(
                extractor.evaluateList(rules.rule(RuleObjectKind.TOC, "chapterList").orEmpty(), input)
            )
            for (item in items) {
                if (chapters.size >= MAX_TOC_CHAPTERS) break
                val urlRaw = extractor.fieldText(item, rules.rule(RuleObjectKind.TOC, "chapterUrl").orEmpty())
                if (urlRaw.isBlank()) continue
                val contentRef = extractor.resolveUrl(urlRaw, page.url)
                if (!seenRefs.add(contentRef)) continue  // 跨页去重：软 404 重复内容靠它判零新增
                chapters += ChapterListEntity(
                    noteUrl = noteUrl,
                    durChapterIndex = chapters.size,
                    contentRef = contentRef,   // 含选项尾段：正文取文时消费
                    durChapterName = extractor.fieldText(item, rules.rule(RuleObjectKind.TOC, "chapterName").orEmpty()),
                    tag = tag,
                )
            }
            if (chapters.size >= MAX_TOC_CHAPTERS) {
                Logger.w("ScriptTocPager", "章节索引触及防御上限 $MAX_TOC_CHAPTERS 章，按截断处理: $entryUrl")
                break
            }
            // 零新增即到底——优先于翻页判定（§12：软 404 页与末页同判）
            current = if (chapters.size == sizeBefore) null else chain.nextOf(input, page.url)
        }
        return chapters
    }
}
```

`ScriptRuleSet` 追加数组展开助手（`jsonField` 之上薄封装）：

```kotlin
    /** `nextTocUrl`/`nextContentUrl` 的 URL 数组形态（§7.1/§7.2）：字符串元素逐个取出，非数组返回空 */
    fun nextUrlArray(kind: RuleObjectKind, field: String): List<String> =
        (jsonField(kind, field) as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
            .orEmpty()
```

`ScriptBookParser.getChapterList` 填实：

```kotlin
    override suspend fun getChapterList(bookShelf: BookShelfEntity): WebChapterEntity<BookShelfEntity> =
        withContext(Dispatchers.IO) {
            bookShelf.tag = rules.sourceUrl
            // 章节索引入口 = 详情页解出的 tocUrl（含选项尾段），无则详情页地址——与原生同形
            val entryUrl = bookShelf.bookInfo?.chapterUrl ?: bookShelf.noteUrl
            val ctx = EvalContext(baseUrl = stripTail(entryUrl))
            val evaluator = ScriptRuleEvaluator(ctx)
            val extractor = ScriptFieldExtractor(evaluator, rules.sourceUrl)
            val pager = ScriptTocPager(extractor, rules) { urlRule -> fetchPage(urlRule, ctx, evaluator) }
            bookShelf.chapterList = pager.collect(
                entryUrl = entryUrl,
                ctx = ctx,
                noteUrl = bookShelf.noteUrl,
                tag = rules.sourceUrl,
            )
            WebChapterEntity(bookShelf, false)
        }
```

（`ScriptFieldExtractor` 的 `evaluateList` 转发入口见 Step 3 的 `ScriptPageChain` 小节——两个翻页链与列表字段共用同一条求值路径。）

- [x] **Step 4: 运行测试通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest`
Expected: PASS——新增 6 例。

- [x] **Step 5: 红线 + 提交**

```bash
git grep -in "[l]egado" && echo "VIOLATION" || echo "CLEAN"
git add lib_book_source
git commit -m "$(cat <<'EOF'
feat(lib_book_source): 脚本书源目录翻页链（nextTocUrl 与去重上限）

- ScriptPageChain 统一字符串规则/URL 数组/回环防护三种翻页口径（目录正文共用）
- 终止判据与 20k 上限对齐原生 TocPager：零新增即到底、contentRef 跨页去重
- 数组形态入口重复元素跳过不终止，访问键剥选项尾段防同址误判
EOF
)"
```

---

### Task 6: 正文链路——`fetchChapterText` 与 `ScriptContentPager`（nextContentUrl/replaceRegex）

**Files:**
- Modify: `lib_book_source/src/main/java/com/ebook/source/analyze/ScriptBookParser.kt`（fetchChapterText 填实）
- Create: `lib_book_source/src/main/java/com/ebook/source/script/ScriptContentPager.kt`
- Test: `lib_book_source/src/test/java/com/ebook/source/script/ScriptContentPagerTest.kt`（新建）

- [x] **Step 1: 写失败的测试**

```kotlin
package com.ebook.source.script

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptContentPagerTest {

    private fun pagerOf(contentJson: String, pages: Map<String, String>): ScriptContentPager {
        val set = ScriptRuleSet.load("""{"bookSourceUrl":"https://a.com","ruleContent":$contentJson}""")
        val ctx = EvalContext(baseUrl = "https://a.com/c1.html")
        val evaluator = ScriptRuleEvaluator(ctx)
        val extractor = ScriptFieldExtractor(evaluator, "https://a.com")
        return ScriptContentPager(extractor, set, ctx) { url ->
            ScriptPage(url, pages[ScriptUrlOption.splitTail(url)?.first ?: url] ?: "")
        }
    }

    @Test
    fun `单章多页按 nextContentUrl 拼接并以换行分段`() = runTest {
        val pager = pagerOf(
            """{"content":"id.content@textNodes","nextContentUrl":"class.next@href"}""",
            mapOf(
                "https://a.com/c1.html" to """<div id="content">第一页正文<br/>第二段</div><a class="next" href="c1_2.html">下一页</a>""",
                "https://a.com/c1_2.html" to """<div id="content">第二页正文</div>""",  // 无 next：本章末页
            ),
        )
        val text = pager.collect("https://a.com/c1.html")
        assertEquals("第一页正文\n第二段\n第二页正文", text)
    }

    @Test
    fun `replaceRegex 以净化形态跑在拼接后的整章串上`() = runTest {
        val pager = pagerOf(
            """{"content":"id.content@textNodes","replaceRegex":"##本章未完.*继续阅读##"}""",
            mapOf("https://a.com/c1.html" to """<div id="content">正文甲<br/>本章未完，点击继续阅读</div>"""),
        )
        assertEquals("正文甲", pager.collect("https://a.com/c1.html"))
    }

    @Test
    fun `回环的 nextContentUrl 被访问集拦下不无限循环`() = runTest {
        // 规则配错：next 指向本章自己（§7.3 反例——显式规则配错时回环拦截是唯一防线）
        val html = """<div id="content">正文</div><a class="next" href="c1.html">自己</a>"""
        val pager = pagerOf(
            """{"content":"id.content@textNodes","nextContentUrl":"class.next@href"}""",
            mapOf("https://a.com/c1.html" to html),
        )
        assertEquals("正文", pager.collect("https://a.com/c1.html"))
    }

    @Test
    fun `数组形态 nextContentUrl 按固定页序拼接`() = runTest {
        val pager = pagerOf(
            """{"content":"id.content@textNodes","nextContentUrl":["https://a.com/c1.html","https://a.com/c1_2.html"]}""",
            mapOf(
                "https://a.com/c1.html" to """<div id="content">页一</div>""",
                "https://a.com/c1_2.html" to """<div id="content">页二</div>""",
            ),
        )
        assertEquals("页一\n页二", pager.collect("https://a.com/c1.html"))
    }

    @Test
    fun `content 含 js 段时类型化异常原样穿透`() = runTest {
        val pager = pagerOf(
            """{"content":"@js:result"}""",
            mapOf("https://a.com/c1.html" to "x"),
        )
        val error = runCatching { pager.collect("https://a.com/c1.html") }.exceptionOrNull()
        assertTrue(error is JsEvaluationPendingException)
    }

    @Test
    fun `content 规则未配置时空串返回由读取层判失败`() = runTest {
        val pager = pagerOf("""{}""", mapOf("https://a.com/c1.html" to "x"))
        assertEquals("", pager.collect("https://a.com/c1.html"))
    }
```

- [x] **Step 2: 运行确认失败**

Run: `./gradlew :lib_book_source:compileDebugUnitTestKotlin`
Expected: FAIL——`ScriptContentPager` 未定义。

- [x] **Step 3: 实现**

```kotlin
package com.ebook.source.script

/**
 * 脚本书源的正文翻页链（§7.2/§7.3）。
 *
 * 三条不得违反的口径：
 * - **不套用 `ChapterPageMatcher`**（§7.3 明令）：`nextContentUrl` 是作者显式给出的下一页，
 *   可信度高于 URL 形状启发式，一过滤就永久漏页；防串章靠**回环访问集**（§7.2 本仓规定的
 *   防御件，与原生共享「已访问集合 + 页数上限」的防御思路、不共享判定函数）；
 * - 停止条件：空/`null`（§7.1 同判）、回环、[MAX_CONTENT_PAGES] 上限——**没有**「零新增即到底」
 *   （正文没有去重语义，空页不构成终止证据）；
 * - `replaceRegex` 跑在**拼接后的整章串**上（规则可能跨段，§1.6）。
 *
 * content 字段结果的收敛例外（登记 §11）：单值字段一律 `firstText`，但 content 是段落性字段
 * ——各形态的值集按 `\n` 连接（textNodes/多匹配的段落数据不许丢）。
 */
internal class ScriptContentPager(
    private val extractor: ScriptFieldExtractor,
    private val rules: ScriptRuleSet,
    private val ctx: EvalContext,
    private val fetchPage: suspend (String) -> ScriptPage,
) {
    companion object {
        /** 单章正文页数上限，与 `JsoupSourceReader.MAX_CONTENT_PAGES` 同值同理由 */
        const val MAX_CONTENT_PAGES = 50
    }

    suspend fun collect(entryRef: String): String {
        val contentRule = rules.rule(RuleObjectKind.CONTENT, "content").orEmpty()
        val nextRule = rules.rule(RuleObjectKind.CONTENT, "nextContentUrl").orEmpty()
        val replaceRegex = rules.rule(RuleObjectKind.CONTENT, "replaceRegex").orEmpty()
        val chain = ScriptPageChain(
            entryUrl = entryRef,
            nextRule = nextRule,
            nextArray = rules.nextUrlArray(RuleObjectKind.CONTENT, "nextContentUrl"),
            extractor = extractor,
        )
        val text = StringBuilder()
        var current: String? = entryRef
        while (current != null && chain.visitedCount() <= MAX_CONTENT_PAGES) {
            val page = fetchPage(current)
            ctx.baseUrl = page.url
            val input = RuleValue.Page(page.text)
            val chunk = contentToText(extractor.evaluateList(contentRule, input))
            if (chunk.isNotBlank() && text.isNotEmpty()) text.append('\n')
            text.append(chunk)
            current = chain.nextOf(input, page.url)
        }
        // trim 收边：段中段尾的空串（净化删段后常留）与首尾空白交给读取层的 TextNormalizer
        // 逐段清理，整章首尾的空白在此先收掉——它们不构成任何段落
        return applyReplaceRegex(text.toString().trim(), replaceRegex)
    }

    /** content 的收敛例外：全部值按 `\n` 连接（类 KDoc 第三条口径） */
    private fun contentToText(result: RuleResult): String = when (result) {
        RuleResult.Miss -> ""
        is RuleResult.Texts -> result.values.joinToString("\n")
        is RuleResult.Nodes -> result.elements.joinToString("\n") { it.text() }
        is RuleResult.Matches -> result.items.mapNotNull { it.firstOrNull() }.joinToString("\n")
        is RuleResult.Jsons -> result.items.joinToString("\n") { it.jsonText() }
    }

    /**
     * `replaceRegex` 以净化形态生效（§2.4）：值可能带或不带前导 `##`（独立使用等价 `all##…`），
     * 缺前缀补一个再交 [RegexReplacement.parse]；执行复用 [ReplacementApplier]（循环替换全部命中，
     * 与选择器侧的替换同一套引擎——两处各写一遍就会出现「同一条正则两种替换语义」）。
     */
    private fun applyReplaceRegex(content: String, replaceRegex: String): String {
        if (content.isEmpty() || replaceRegex.isBlank()) return content
        val normalized = if (replaceRegex.startsWith("##")) replaceRegex else "##$replaceRegex"
        val parsed = RegexReplacement.parse(normalized) ?: return content
        val replaced = ReplacementApplier.apply(RuleResult.Texts(listOf(content)), parsed, replaceRegex)
        return (replaced as RuleResult.Texts).values.firstOrNull().orEmpty()
    }
}
```

（`ScriptPageChain.visitedCount()` 已在 Task 5 定义——访问集大小即抓取页数，与原生 `JsoupSourceReader` 用 visited 计数的口径同形。）

`ScriptBookParser.fetchChapterText` 填实：

```kotlin
    override suspend fun fetchChapterText(contentRef: String): String = withContext(Dispatchers.IO) {
        val ctx = EvalContext(baseUrl = stripTail(contentRef))
        val evaluator = ScriptRuleEvaluator(ctx)
        val extractor = ScriptFieldExtractor(evaluator, rules.sourceUrl)
        val pager = ScriptContentPager(extractor, rules, ctx) { urlRule -> fetchPage(urlRule, ctx, evaluator) }
        pager.collect(contentRef)
    }
```

- [x] **Step 4: 运行测试通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest`
Expected: PASS——新增 6 例。

- [x] **Step 5: 红线 + 提交**

```bash
git grep -in "[l]egado" && echo "VIOLATION" || echo "CLEAN"
git add lib_book_source
git commit -m "$(cat <<'EOF'
feat(lib_book_source): 脚本书源正文翻页链与净化

- nextContentUrl 字符串/数组形态经 ScriptPageChain 消费，回环与 50 页上限拦截
- 不套用 ChapterPageMatcher（§7.3）：显式规则可信度高于 URL 形状启发式
- replaceRegex 复用 ReplacementApplier 循环净化，跑在拼接后的整章串上
EOF
)"
```

---

### Task 7: 接线——工厂换真解析器、删除桩、聚合搜索候选含脚本行

**Files:**
- Modify: `lib_book_common/src/main/java/com/ebook/common/analyze/source/BookSourceManagerImpl.kt`
- Modify: `lib_book_common/src/main/java/com/ebook/common/analyze/source/BookSourceManager.kt`（KDoc）
- Delete: `lib_book_source/src/main/java/com/ebook/source/analyze/ScriptSourcePendingParser.kt`
- Delete: `lib_book_source/src/main/java/com/ebook/source/analyze/ScriptInterpreterPendingException.kt`
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/ScriptRuleExceptions.kt`（KDoc 引用已删类）
- Modify: `lib_book_common/src/test/java/com/ebook/common/analyze/source/BookSourceManagerImplTest.kt`

- [x] **Step 1: 写失败的测试**

`BookSourceManagerImplTest.kt`——先把桩断言换成真解析器断言（既有用例改名/改断言，语义不变）：

```kotlin
// import 区：删 com.ebook.source.analyze.ScriptInterpreterPendingException / ScriptSourcePendingParser，
// 换 com.ebook.source.analyze.ScriptBookParser
```

逐处替换（行号为计划落稿时的现场，执行时以 grep 复核）：

```kotlin
// L864/L884/L991/L1024/L1205/L1229/L1254 的
assertTrue(... is ScriptSourcePendingParser)
// 一律改为
assertTrue(... is ScriptBookParser)

// L986 假工厂的 Script 分支
is SourceDefinition.Script -> ScriptSourcePendingParser
// 改为（该用例注入的假件，返回什么由用例自己定）
is SourceDefinition.Script -> fakeScriptParser
```

L900-911 的「求值请求抛 `ScriptInterpreterPendingException`」用例**重写**为锁惰性装载（原语义「解释器没写」已不复存在）：

```kotlin
    @Test
    fun `rule_json 坏掉的脚本行求值抛类型化装载失败而不是 null`() = runTest {
        dao.upsert(
            BookSourceEntity(
                url = SCRIPT_URL, name = "坏脚本行",
                ruleJson = """{"bookSourceUrl":}""",   // 直接落库绕过导入校验的脏行
                format = SourceFormat.SCRIPT.raw,
            )
        )
        val parser = manager.getParserFor(SCRIPT_URL)
        assertNotNull("契约：脚本行永不 null", parser)
        val failure = runCatching { parser!!.searchBook("k", 1) }.exceptionOrNull()
        // 跨模块断言走消息契约（ScriptRuleParseException 是 lib_book_source 的 internal 类型）
        assertTrue(failure?.message?.contains("脚本书源 JSON 无法解析") == true)
    }
```

新增聚合搜索脚本行用例（仿 L1376「一条源抛异常…」的既有搭法）：

```kotlin
    @Test
    fun `聚合搜索候选集包含脚本行并产出其结果`() = runTest {
        dao.upsert(
            BookSourceEntity(
                url = SCRIPT_URL, name = "脚本源",
                ruleJson = """{"bookSourceUrl":"$SCRIPT_URL","bookSourceName":"脚本源"}""",
                enabled = true, format = SourceFormat.SCRIPT.raw,
            )
        )
        // 注入假工厂：Script 分支给一个返回固定结果的假 parser（真解析器会发网络请求）
        val fake = managerWithFactory { definition ->
            when (definition) {
                is SourceDefinition.Native -> throw AssertionError("本用例不应构造原生 parser")
                is SourceDefinition.Script -> FakeSearchParser(listOf(SearchBookEntity().apply {
                    name = "脚本书"; noteUrl = "https://s/1"; tag = SCRIPT_URL
                }))
            }
        }
        val events = fake.searchAcross("凡人", 1).toList()
        assertTrue(events.any { it is AggregateSearchEvent.SourceStarted && it.sourceUrl == SCRIPT_URL })
        assertTrue(events.any { it is AggregateSearchEvent.SourceResult && it.books.single().tag == SCRIPT_URL })
        assertTrue(events.last() is AggregateSearchEvent.AllFinished)
    }
```

（`FakeSearchParser` 仿该文件既有内联假件写法实现 `BookParser`；`managerWithFactory` 沿用既有测试的工厂注入构造路径，名字以现场为准。）

- [x] **Step 2: 运行确认失败**

Run: `./gradlew :lib_book_common:compileDebugUnitTestKotlin`
Expected: FAIL——`ScriptSourcePendingParser` 引用悬空（桩已删后）；先实现再删亦可，顺序取「先改测试、再改实现、最后删文件」。

- [x] **Step 3: 实现**

`BookSourceManagerImpl.kt`：

1. 工厂 Script 分支（L152-158）：

```kotlin
    private val parserFactory: (SourceDefinition) -> BookParser = { definition ->
        when (definition) {
            is SourceDefinition.Native -> JsoupBookParser(definition.rule, okHttpClient)
            // 真解析器：规则惰性装载（getParserFor 契约「脚本行不 null、构造期不抛」由它承担），
            // 取文走 @Named("source") 同一纯净客户端（不带 token，见类 KDoc 顶部）
            is SourceDefinition.Script -> ScriptBookParser(definition.rawJson, okHttpClient)
        }
    }
```

2. `toDefinition` 的 WARN（L521）降级改写：

```kotlin
            SourceFormat.SCRIPT -> {
                Logger.d(TAG, "脚本书源求值路由: ${entity.url}")
                SourceDefinition.Script(entity.ruleJson)
            }
```

（原 WARN 的理由「一次命中意味着有本书绑在脚本源上被点开、排查时看不出这条路由被走过多少次」保留为 debug 级观测；不再是异常态就不该用 WARN 吵日志。同处 KDoc 的「当前默认工厂的 Script 分支给出 ScriptSourcePendingParser」句改写为真解析器描述。）

3. `searchAcross` 候选集（L647-659）与 `searchOneSource`：

```kotlin
    override fun searchAcross(
        keyword: String,
        page: Int,
        skipSourceUrls: Set<String>,
    ): Flow<AggregateSearchEvent> = flow {
        // 候选集 = 启用中的**两种格式**行：脚本源参与聚合搜索是 2d 的落地项（ADR-0029）。
        // 不走 getEnabledSources()（规则类型化读面，脚本行被 toRule 挡下）；toItem 对两种
        // 出身都给真 url/name（脚本行的 rule 是实体列合成的展示壳，url/name 即列值）
        seeding.await()
        val sources = dao.getEnabled().mapNotNull { toItem(it) }.filterNot { it.rule.url in skipSourceUrls }
        emitAll(
            sources.asFlow().flatMapMerge(AGGREGATE_SEARCH_CONCURRENCY) { item ->
                searchOneSource(item, keyword, page)
            }
        )
        emit(AggregateSearchEvent.AllFinished)
    }

    private fun searchOneSource(
        item: BookSourceItem,
        keyword: String,
        page: Int,
    ): Flow<AggregateSearchEvent> = flow {
        emit(AggregateSearchEvent.SourceStarted(item.rule.url, item.rule.name))
        try {
            val parser = getParserFor(item.rule.url)
                ?: throw IllegalStateException("书源清单里的源取不到解析器：${item.rule.name}(${item.rule.url})")
            val books = parser.searchBook(keyword, page)
            emit(AggregateSearchEvent.SourceResult(item.rule.url, books))
            emit(AggregateSearchEvent.SourceFinished(item.rule.url, hasMore = books.isNotEmpty()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 脚本源的类型化失败（JS 待执行/不支持能力/坏 JSON）在此收敛为该源的 Failed——
            // 「这条源解不动」不说成「搜不到结果」（§12）
            Logger.w(TAG, "聚合搜索单源失败: ${item.rule.url}", e)
            emit(AggregateSearchEvent.SourceFailed(item.rule.url, e))
            emit(AggregateSearchEvent.SourceFinished(item.rule.url, hasMore = false))
        }
    }
```

（`getParserFor` 的 KDoc「脚本书源不在这三种里：它拿到的是桩解析器」段改写为真解析器 + 惰性装载语义；`getEnabledSources` KDoc 的「实况消费方是聚合搜索」句改为「默认源回落」（聚合搜索已改走条目面）；`BookSourceManager.kt` 接口 KDoc 的 searchAcross「集合取自 [getEnabledSources]」同步改为「启用中的两种格式行」。）

4. 删除两个桩文件（`DeleteFile`）。

5. `ScriptRuleExceptions.kt` 类 KDoc：删去「与 `com.ebook.source.analyze.ScriptInterpreterPendingException` 的分工」整段（类已不存在），改写为：「本类的存在理由（承地基计划的口径）：`BookParser` 消费链上 null 恒等于『书源不存在』，而『规则读不懂』『用了未覆盖的语法』『JS 段待沙箱』是三种不同失败，混起来会把用户支去重导一条本来好的源。」

- [x] **Step 4: 运行测试通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest :lib_book_common:testDebugUnitTest`
Expected: PASS——manager 测试改写后全绿；既有聚合三用例（1376/1437/1474 行）零改动通过（候选集换成 toItem 后原生行的集合不变）。

- [ ] **Step 5: 红线 + 提交**

```bash
git grep -in "[l]egado" && echo "VIOLATION" || echo "CLEAN"
git add lib_book_source lib_book_common
git commit -m "$(cat <<'EOF'
feat(all): 脚本书源接管求值并接入聚合搜索

- parserFactory 的 Script 分支换 ScriptBookParser，删除待实现桩与桩异常
- searchAcross 候选集改为两种格式的启用行，脚本源单源失败收敛为事件
- 坏 JSON 脚本行在首次求值抛类型化装载失败，getParserFor 契约不变
EOF
)"
```

---

### Task 8: 接线——正文读取器脚本分支

**Files:**
- Modify: `lib_book_common/src/main/java/com/ebook/common/analyze/source/JsoupSourceReader.kt`
- Test: `lib_book_common/src/test/java/com/ebook/common/analyze/source/JsoupSourceReaderTest.kt`（追加）

- [x] **Step 1: 写失败的测试**

```kotlin
    /** 脚本正文假件：实现 public 接缝（internal 求值件跨模块测试不可见，这正是接缝存在的理由） */
    private class FakeScriptContentParser(
        private val text: String,
        private val error: Exception? = null,
    ) : BookParser, ScriptContentParser {
        override suspend fun searchBook(content: String, page: Int) = emptyList<SearchBookEntity>()
        override suspend fun getBookInfo(bookShelf: BookShelfEntity) = bookShelf
        override suspend fun getChapterList(bookShelf: BookShelfEntity) = WebChapterEntity(bookShelf, false)
        override suspend fun getKindBook(url: String, page: Int) = emptyList<SearchBookEntity>()
        override suspend fun fetchLibraryData() = LibraryEntity()
        override suspend fun fetchChapterText(contentRef: String): String {
            error?.let { throw it }
            return text
        }
    }

    @Test
    fun `脚本源正文抓取后落盘并按行切段`() = runTest {
        val store = tempStore()   // 沿用该文件既有的 BookStore 测试搭法
        val reader = readerWithParser(FakeScriptContentParser("第一段\n第二段"))
        val content = reader.readChapter(
            ChapterEntry(0, "第一章", "https://s.example/c1.html"),
            BookLocation("book-1", BookFormat.NETWORK, "https://script.example.com"),
        )
        assertEquals(listOf("第一段", "第二段"), content.paragraphs)
        assertTrue(store.hasChapter(BookLocation("book-1", BookFormat.NETWORK, "https://script.example.com"), 0))
    }

    @Test
    fun `脚本源空正文不落盘返回空段落`() = runTest {
        val reader = readerWithParser(FakeScriptContentParser("   "))
        val content = reader.readChapter(ChapterEntry(0, "x", "https://s/c1.html"), BookLocation("b", BookFormat.NETWORK, "https://s"))
        assertTrue(content.paragraphs.isEmpty())
    }

    @Test
    fun `脚本源类型化异常原样上抛并记录错误 URL`() = runTest {
        val typed = RuntimeException("该规则含可执行脚本段，需脚本沙箱执行器才能求值")
        val reader = readerWithParser(FakeScriptContentParser("", typed))
        val error = runCatching {
            reader.readChapter(ChapterEntry(0, "x", "https://s/c1.html"), BookLocation("b", BookFormat.NETWORK, "https://s"))
        }.exceptionOrNull()
        assertEquals(typed, error)  // 不裹成「章节内容解析失败」：类型化消息是给用户的真话（§12）
    }
```

（`readerWithParser`/`tempStore` 沿用该文件既有 fixture 搭法——用 `FakeBookSourceManager` 把 `getParserFor` 指向假件；名字以现场为准。）

- [x] **Step 2: 运行确认失败**

Run: `./gradlew :lib_book_common:testDebugUnitTest --tests "com.ebook.common.analyze.source.JsoupSourceReaderTest"`
Expected: FAIL——`ScriptContentParser` 接缝未被读取器消费。

- [x] **Step 3: 实现**

`JsoupSourceReader.kt`——`resolveJsoupParser` 放宽为 `resolveParser`（四成因判据原样保留，只换返回类型与第 4 支消息），`fetchAndStore` 顶部按 parser 类型分岔：

```kotlin
    /**
     * 取**这本书归属书源**的 parser（多书源共存的落点，见 ADR-0016）。
     *
     * 四种成因判据与既有口径逐字一致（本地 tag 编程错误 / 空白 tag 脏数据 / 查不到行书源失效 /
     * 不支持抓正文编程错误），只是第 4 支的判据从「不是 JsoupBookParser」变成
     * 「既不是 JsoupBookParser（原生）也不是 ScriptContentParser（脚本）」——2d 起脚本源
     * 在这里拿到真正文抓取能力，不再被降级成编程错误。
     */
    internal suspend fun resolveParser(location: BookLocation): BookParser {
        if (location.sourceUrl == BookShelfEntity.LOCAL_TAG) {
            throw IllegalStateException(
                "网络正文读取拿到了本地书：tag=${BookShelfEntity.LOCAL_TAG}, bookId=${location.bookId}"
            )
        }
        if (location.sourceUrl.isBlank()) {
            throw BookSourceNotFoundException(location.sourceUrl, "bookId=${location.bookId}")
        }
        val parser = bookSourceManager.getParserFor(location.sourceUrl)
            ?: throw BookSourceNotFoundException(location.sourceUrl)
        return parser
    }

    private suspend fun fetchAndStore(entry: ChapterEntry, location: BookLocation): ChapterContent {
        val parser = resolveParser(location)
        // 取源刻意放在 try 之外（理由不变：书源失效不是解析失败，不能被裹掉）
        return when (parser) {
            is JsoupBookParser -> fetchAndStoreNative(parser, entry, location)
            is ScriptContentParser -> fetchAndStoreScript(parser, entry, location)
            else -> throw IllegalStateException("该书源不支持抓取网络正文：${location.sourceUrl}")
        }
    }

    /** 原生路径：既有 fetchAndStore 主体原样搬入（rule/BookSourceNetwork/分页判定/清理规则零改动） */
    private suspend fun fetchAndStoreNative(
        parser: JsoupBookParser,
        entry: ChapterEntry,
        location: BookLocation,
    ): ChapterContent { /* 既有实现主体整段搬入，本 Task 不改一行逻辑 */ }

    /**
     * 脚本路径：解析器答「这一章的文本」，本类答「怎么存」。
     *
     * 两条刻意与原生不同的口径：
     * - **异常原样上抛**（原生裹成「章节内容解析失败」）：脚本求值的类型化异常自带面向用户
     *   的中文消息（「需沙箱执行器」「不支持的能力」），裹掉等于把真话换成一句查不出根因的
     *   套话。错误 URL 清单照记（诊断线索），`CancellationException` 不记不裹先上抛；
     * - 其余（空正文不落盘、`\n` 切段、不写缩进、TextNormalizer 归读取层）与原生逐字同口径——
     *   章文件格式是存储层契约，两种出身必须同形，否则换源后既有章文件读不回来。
     */
    private suspend fun fetchAndStoreScript(
        parser: ScriptContentParser,
        entry: ChapterEntry,
        location: BookLocation,
    ): ChapterContent {
        val rawText: String
        try {
            rawText = parser.fetchChapterText(entry.contentRef)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "fetchAndStoreScript: ", e)
            ErrorAnalyzeContentManager.writeNewErrorUrl(context, entry.contentRef)
            throw e   // 原样上抛：见 KDoc 第一条口径
        }
        val stored = TextNormalizer.unifyNewlines(rawText).split('\n')
        if (stored.none { it.isNotBlank() }) {
            Logger.w(TAG, "正文为空，不写章文件: ${entry.contentRef}")
            return ChapterContent(title = entry.title, paragraphs = emptyList())
        }
        store.writeChapter(location, entry.index, stored)
        return ChapterContent(title = entry.title, paragraphs = stored)
    }
```

（既有 `resolveJsoupParser` 的测试用例改调 `resolveParser` 断言返回类型；「取到的 parser 不支持抓正文时报编程错误」用例保留——喂一个既非 Jsoup 也非 Script 的裸假件。）

- [x] **Step 4: 运行测试通过**

Run: `./gradlew :lib_book_common:testDebugUnitTest`
Expected: PASS——新 3 例 + 既有读取器用例（改调 `resolveParser`）零回归。

- [ ] **Step 5: 红线 + 提交**

```bash
git grep -in "[l]egado" && echo "VIOLATION" || echo "CLEAN"
git add lib_book_common
git commit -m "$(cat <<'EOF'
feat(lib_book_common): 正文读取器接入脚本书源分支

- resolveParser 放宽返回类型，fetchAndStore 按 Jsoup/ScriptContentParser 分岔
- 脚本路径异常原样上抛（类型化消息即用户真话），存储口径与原生逐字同形
- 空正文不落盘、\n 切段、TextNormalizer 归读取层，两种出身章文件同格式
EOF
)"
```

---

### Task 9: 端到端合成金标准（真实语料债的顶位）

**Files:**
- Create: `lib_book_source/src/test/java/com/ebook/source/script/ScriptFixtureSources.kt`
- Create: `lib_book_source/src/test/java/com/ebook/source/script/ScriptSourceEndToEndTest.kt`

- [x] **Step 1: 写失败的测试**

`ScriptFixtureSources.kt`——三型合成源（按规格已核实形态手写；**它们锁的是解析器编排行为**，真实源语义锁仍欠语料，见 Task 10 的债登记）：

```kotlin
package com.ebook.source.script

/**
 * 端到端合成源（金标准顶位，非真实语料）：覆盖 2c 欠下的四类证据形态中的三类可离线形态——
 * HTML 链式型、JSONPath API 型、`<>` 分页 + POST 型。gbk 的字节解码属 OkHttp 传输层，
 * 假 transport 断言 charset 进请求即止（真机验证归人工清单）。
 * 真实语料（642 条）不可得时的顶位；语料回来后按 ADR-0029 决策 8 补 5 条真源 fixtures。
 */
internal object ScriptFixtureSources {

    /** HTML 链式型：GET 搜索、链式列表、详情、字符串规则目录翻页、正文翻页 + 净化 */
    val htmlChain: String = """
        {"bookSourceUrl":"https://www.example.com","bookSourceName":"HTML链式型",
         "searchUrl":"/search?key={{key}}&page={{page}}",
         "ruleSearch":{"bookList":"class.bookbox","name":"tag.h4@text","bookUrl":"tag.a@href",
           "author":"tag.p@text","coverUrl":"tag.img@src"},
         "ruleBookInfo":{"name":"tag.h1@text","author":"class.info@text","intro":"class.intro@text",
           "coverUrl":"tag.img@src","tocUrl":"class.catalog@href"},
         "ruleToc":{"chapterList":"tag.li","chapterName":"tag.a@text","chapterUrl":"tag.a@href",
           "nextTocUrl":"class.next@href"},
         "ruleContent":{"content":"id.content@textNodes","nextContentUrl":"class.next@href",
           "replaceRegex":"##\n本章未完.*继续阅读##"}}
    """.trimIndent()

    /** JSONPath API 型：POST 搜索 + JSON 响应，详情/目录/正文全 JSONPath */
    val jsonApi: String = """
        {"bookSourceUrl":"https://api.example.com","bookSourceName":"JSONAPI型",
         "searchUrl":"/v1/books,{\"method\":\"POST\",\"body\":\"kw={{key}}&pn={{page}}\"}",
         "ruleSearch":{"bookList":"$.data.books","name":"$.name","author":"$.author","bookUrl":"$.url",
           "coverUrl":"$.cover","intro":"$.summary"},
         "ruleBookInfo":{"name":"$.name","author":"$.author","intro":"$.summary","tocUrl":"$.tocUrl"},
         "ruleToc":{"chapterList":"$.chapters","chapterName":"$.title","chapterUrl":"$.url"},
         "ruleContent":{"content":"$.content"}}
    """.trimIndent()

    /** `<>` 分页型：搜索首页不带页码段（§6.4 本仓规定的形态②），目录数组形态 */
    val anglePage: String = """
        {"bookSourceUrl":"https://www.example.com","bookSourceName":"尖括号分页型",
         "searchUrl":"/s<,{{page}}>",
         "ruleSearch":{"bookList":"class.bookbox","name":"tag.h4@text","bookUrl":"tag.a@href"},
         "ruleToc":{"chapterList":"tag.li","chapterName":"tag.a@text","chapterUrl":"tag.a@href",
           "nextTocUrl":["https://www.example.com/toc1.html","https://www.example.com/toc2.html"]},
         "ruleContent":{"content":"id.content@textNodes"}}
    """.trimIndent()
}
```

`ScriptSourceEndToEndTest.kt`——假页表驱动「搜索 → 详情 → 目录 → 正文」全链：

```kotlin
package com.ebook.source.script

import com.ebook.db.entity.BookShelfEntity
import com.ebook.source.analyze.ScriptBookParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptSourceEndToEndTest {

    /** 假页表：key 是 strip 尾段后的绝对地址（与 RecordingTransport 同法，这里按用例内联） */
    private class TableTransport(private val table: Map<String, String>) : ScriptTransport {
        val requests = mutableListOf<ScriptRequest>()
        override suspend fun execute(request: ScriptRequest): String {
            requests += request
            return table[ScriptUrlOption.splitTail(request.url)?.first ?: request.url] ?: ""
        }
    }

    @Test
    fun `HTML 链式型源走通搜索详情目录正文全链`() = runTest {
        val transport = TableTransport(
            mapOf(
                "https://www.example.com/search?key=%E5%87%A1%E4%BA%BA&page=1" to """
                    <div class="bookbox"><h4><a href="/book/1.html">凡人修仙传</a></h4>
                    <p>忘语</p><img src="/c/1.jpg"/></div>""",
                "https://www.example.com/book/1.html" to """
                    <h1>凡人修仙传</h1><i class="info">忘语</i><i class="intro">仙侠</i>
                    <img src="/c/1.jpg"/><a class="catalog" href="/book/1/toc.html">目录</a>""",
                "https://www.example.com/book/1/toc.html" to """
                    <ul><li><a href="/c1.html">第一章</a></li></ul><a class="next" href="toc_2.html">下一页</a>""",
                "https://www.example.com/book/1/toc_2.html" to """
                    <ul><li><a href="/c2.html">第二章</a></li></ul>""",
                "https://www.example.com/c1.html" to """
                    <div id="content">第一章正文<br/>本章未完，点击继续阅读</div><a class="next" href="c1_2.html">下一页</a>""",
                "https://www.example.com/c1_2.html" to """<div id="content">第一章后半</div>""",
            )
        )
        val parser = ScriptBookParser(ScriptFixtureSources.htmlChain, transport)

        val books = parser.searchBook("凡人", 1)
        assertEquals("凡人修仙传", books.single().name)

        val shelf = BookShelfEntity().apply { noteUrl = books.single().noteUrl; tag = books.single().tag }
        assertEquals("https://www.example.com/book/1/toc.html", parser.getBookInfo(shelf).bookInfo!!.chapterUrl)
        val chapters = parser.getChapterList(shelf).data.chapterList
        assertEquals(listOf("第一章", "第二章"), chapters.map { it.durChapterName })

        val text = parser.fetchChapterText(chapters.first().contentRef)
        // 净化正则含前导 \n：把删段连同它前面的换行一起吃掉，段落之间不残留空行
        assertEquals("第一章正文\n第一章后半", text)
    }

    @Test
    fun `JSONPath API 型源走通全链且 POST 体带编码关键词`() = runTest {
        val transport = TableTransport(
            mapOf(
                "https://api.example.com/v1/books" to """
                    {"data":{"books":[{"name":"凡人修仙传","author":"忘语",
                    "url":"https://api.example.com/v1/book/1","cover":"https://api.example.com/c/1.jpg",
                    "summary":"仙侠"}]}}""",
                "https://api.example.com/v1/book/1" to """
                    {"name":"凡人修仙传","author":"忘语","summary":"仙侠巨著",
                    "tocUrl":"https://api.example.com/v1/book/1/toc"}""",
                "https://api.example.com/v1/book/1/toc" to """
                    {"chapters":[{"title":"第一章","url":"https://api.example.com/v1/c/1"}]}""",
                "https://api.example.com/v1/c/1" to """{"content":"第一章的 JSON 正文"}""",
            )
        )
        val parser = ScriptBookParser(ScriptFixtureSources.jsonApi, transport)
        assertEquals(1, parser.searchBook("凡人", 1).size)
        assertEquals("POST", transport.requests.first().method)
        assertEquals("kw=%E5%87%A1%E4%BA%BA&pn=1", transport.requests.first().body)

        val shelf = BookShelfEntity().apply { noteUrl = "https://api.example.com/v1/book/1" }
        val chapters = parser.getChapterList(parser.getBookInfo(shelf)).data.chapterList
        assertEquals("第一章的 JSON 正文", parser.fetchChapterText(chapters.single().contentRef))
    }

    @Test
    fun `尖括号分页型搜索首页不带页码段`() = runTest {
        val transport = TableTransport(
            mapOf(
                "https://www.example.com/s" to """<div class="bookbox"><h4><a href="/book/1.html">书</a></h4></div>""",
            )
        )
        val parser = ScriptBookParser(ScriptFixtureSources.anglePage, transport)
        assertEquals(1, parser.searchBook("k", 1).size)
        // 页码 1：`<,1>` 整段（含分隔符）不进 URL（§6.4 本仓规定，2c 已锁形——此处锁装配层不回退）
        assertEquals("https://www.example.com/s", transport.requests.single().url)
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `./gradlew :lib_book_source:compileDebugUnitTestKotlin`
Expected: FAIL——`ScriptFixtureSources` 未定义。

- [x] **Step 3: 实现**

合成源与用例已含实现内容（本 Task 无主代码改动——它是对 Task 1-7 的回归性验收；若红灯暴露装配缺陷，回对应 Task 修正并登记「执行期修正记录」）。

- [x] **Step 4: 运行测试通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest`
Expected: PASS——3 例全绿。

- [ ] **Step 5: 红线 + 提交**

```bash
git grep -in "[l]egado" && echo "VIOLATION" || echo "CLEAN"
git add lib_book_source
git commit -m "$(cat <<'EOF'
test(lib_book_source): 脚本书源端到端合成金标准用例

- 三型合成源（HTML链式/JSONPath API/尖括号分页）锁装配编排行为
- 真实语料语义锁登记为待偿债（语料文件缺失，见规格 §11 与人工清单）
EOF
)"
```

---

### Task 10: 文档同步、全量验证与人工装机清单

**Files:**
- Modify: `docs/superpowers/specs/2026-09-08-script-source-rule-grammar.md`（§11 新增条目 + §1.5/§10 注记）
- Modify: `AGENTS.md`（书源实战建议段 2d 边界改写）
- Modify: `docs/adr/0029-script-book-source-import.md`（落地状态补记）
- Modify: `docs/superpowers/plans/2026-09-08-script-book-source-foundation.md`（路线图 2d 状态）
- Modify: `docs/test-coverage-todo.md`（人工装机清单登记）
- Modify: 本计划文件（勾选、执行期修正记录）

- [x] **Step 1: 规格就地更新**

§11 追加（编号顺延 20 起）：

- **§11-20 `{{key}}` 的编码**：2d 本仓规定——搜索关键词在装进 `EvalContext.key` 前按 `URLEncoder.encode(UTF-8)` 表单百分号编码；不编码时中文关键词在 OkHttp `HttpUrl` 直接非法。上游是否对 `{{key}}` 自行编码无文档，待真实语料验证（过度编码的证据：搜索词含 `+`/`%` 的源取回空结果）。
- **§11-21 `init` 的 AllInOne 键取值语义**：规格 §1.4 的「字段按对象键取值」读法属 JS 分支（Plan 3）；2d 本仓规定——AllInOne 产物以**首个匹配**为条目上下文，字段规则经 `evaluateOnItem`（`$n` 组引用）求值；非 Matches 结果视为无预处理。
- **§11-22 `canReName` 无行为差**：本仓 `getBookInfo` 的入参 shelf 不携带搜索值（`addFromSearch` 只装 noteUrl/tag），「允许覆盖搜索页取值」无从发生——字段解出即填，与原生 `getBookInfo` 同形。规格 §1.4 该行注记「本管道无行为差」。
- **§11-23 翻页数组形态的访问次序**：`nextTocUrl`/`nextContentUrl` 的 URL 数组按固定页序访问，**入口先抓**、数组元素与入口同址（剥尾段后比对）时跳过不终止；目录链在数组形态下同样适用「零新增即到底」（§12 判据复用——固定清单也可能配错，零新增是护栏不是障碍）。
- **§11-24 content 字段的收敛例外**：单值字段一律 `firstText`（§11-2），但 content 是段落性字段——各形态值集按 `\n` 连接（`textNodes`/多匹配的段落不许丢）；`replaceRegex` 缺前导 `##` 时补一个按净化形态生效。
- **§11-25 合成金标准与真实语料债**：2c 欠下的语料证据债（JSONPath 子集扩集、`<>` 真实形态、选项键频度、§11-13 的 `[` 开头误判与朴素引号判定）在 2d 以**仓内合成源**顶位锁编排行为；真实 642 条语料不可得（文件已不在桌面原路径），5 条真源金标准 fixtures 待语料回位后按 ADR-0029 决策 8 补齐。
- **§11-26 isVip/updateTime 无落点**：规格 §1.5 标 ✅ 但 `ChapterListEntity` 无对应列（与原生链路同形不存）——2d 起按「可解不存」注记，实体加列时再落地。

- [x] **Step 2: AGENTS.md 更新**

书源实战建议段里「**JSONPath 与 URL 语义、取文层已在 2c 落地……仍未接页面（`ScriptSourcePendingParser` 原位，2d 才替换）**」与「**BookParser 实现与翻页链/聚合搜索归 2d——脚本行求值依旧命中 `ScriptSourcePendingParser`……**」两段改写为 2d 实况：

> **求值链路已全线落地（2d，2026-09-09）**：`ScriptBookParser` 实现 `BookParser` 五面（搜索/详情/目录/发现）与 `ScriptContentParser` 正文接缝，桩与桩异常已删除——聚合搜索候选含脚本行、正文读取按 `ScriptContentParser` 分岔、脚本源可搜索可加书架可全链路阅读。四条新口径：(a) 脚本 URL 的关键词 `{{key}}` 按表单百分号编码（§11-20）；(b) `getParserFor` 对脚本行**永不 null**，坏 JSON 在首次求值抛类型化装载失败——null 仍只表示「书源不存在/坏行」；(c) 目录/正文翻页判据（零新增/回环/上限）与原生同套，但**不移植** `ChapterPageMatcher`（§7.3）；(d) **书城默认源机制仍原生**——`currentSource` 载体不变、`LibraryViewModel` 的 format 过滤保留、脚本行不当默认源的三条锁形测试原样有效；书城接线（默认源类型重构 + 切换器放开 + `getBookTypeList` 脚本分支）归后续计划。导入报告的逐项能力警示（`ScriptRuleSet.unsupported` 上 UI）同留后续。

（同步删掉该段中「脚本行求值目前命中 `ScriptSourcePendingParser` 抛 `ScriptInterpreterPendingException`，而 null 恒等于……两者**不得混用**」的表述，替换为上面的 (b)。）

- [x] **Step 3: ADR-0029 与路线图补记**

`docs/adr/0029-script-book-source-import.md` 的「落地状态」节追加：

```markdown
- **2026-09-09（阶段二，Plan 2 之 2a~2d）**：清洁室解释器四段全部落地——词法与组合（2a）、
  HTML 求值（2b）、JSONPath 与 URL 取文层（2c）、`BookParser` 装配与接线（2d）。脚本源现在
  可搜索（聚合搜索候选）、可加书架、可全链路阅读；桩解析器与桩异常已删除。**仍未落地**：
  书城默认源接线（`currentSource` 仍原生载体，归后续小计划）、`:js` 沙箱执行器（Plan 3，
  含 JS 规则段照实报「需沙箱」）、决策 8 的真实语料金标准 fixtures（语料文件缺失，以仓内
  合成源顶位锁编排，债见规格 §11-25）。
```

`2026-09-08-script-book-source-foundation.md` 路线图 2d 段补执行结果（照 2a/2b/2c 的写法：Task 全绿、用例数、边界一句话）。

- [x] **Step 4: 人工装机清单登记**

`docs/test-coverage-todo.md` 按既有节式追加「人工装机验证清单（脚本书源阶段二，2026-09-09）」：

1. 导入一条**纯声明式**真实脚本源（无 `<js>`）→ 搜索关键词：聚合进度含该源、结果可点进详情；
2. 从脚本源结果**加入书架** → 目录加载正常（多页目录站点翻到底）→ 翻开一章正文正常（分页章节拼接完整）；
3. 导入一条**含 JS** 的脚本源 → 搜索/阅读它给出的失败提示是「需沙箱执行器」类真话，**不是**「书源已失效」，也不是静默空结果；
4. gbk 站源装机验证：charset 选项显式字节解码不乱码（模拟器/真机）；
5. `<,{{page}}>` 分页的真实源验证（§6.4/§11-7 的 2c 债）；
6. 原生源回归：搜索/书城/阅读全链路零变化（桩删除不碰原生路径）；
7. 书城切换器**仍只列原生源**（脚本行不出现——2d 边界，归后续计划）。

- [x] **Step 5: 全量验证**

```bash
./gradlew test
./gradlew :module_app:assembleDebug
git grep -in "[l]egado"      # 零命中
```

已知既有 flake：`module_find` 的 `LibraryViewModelTest` 偶发 `Dispatchers.Main is used concurrently with setting it`，命中时重跑确认，不要顺手改。

- [ ] **Step 6: 提交**

```bash
git add AGENTS.md docs/
git commit -m "$(cat <<'EOF'
docs: 登记脚本书源 2d 落地口径与语料证据债

- 规格 §11 追加七条 2d 口径（key 编码/init 语义/翻页数组/content 收敛等）
- AGENTS.md 更新能力边界：五面求值与接线落地，书城默认源仍归后续
- ADR-0029 落地状态补记阶段二；人工装机清单登记 test-coverage-todo
EOF
)"
```

---

## 退出判据

1. `./gradlew test` 全绿、`:module_app:assembleDebug` 成功；`:lib_book_source` 用例数在 2c 的 290 之上按各任务预期增加（Task 1: +3、Task 2: +8、Task 3: +3、Task 4: +4、Task 5: +6、Task 6: +6、Task 9: +3，预期 ≥ 323）。
2. `ScriptSourcePendingParser` 与 `ScriptInterpreterPendingException` 全仓零引用（`git grep` 零命中，含 KDoc）；`getParserFor` 对脚本行返回 `ScriptBookParser` 且坏 JSON 行首求值抛类型化装载失败。
3. 聚合搜索候选含脚本行（新用例锁形）；既有聚合三用例与「脚本行不当默认源」三用例**零改动**通过——2d 的边界（书城不接线）由它们继续锁住。
4. 正文读取器脚本分支：章文件格式与原生同形（`\n` 切段、空不落盘），类型化异常原样上抛（用例锁形）。
5. `git grep -in "[l]egado"` 零命中；无新增编译警告。
6. 规格与 AGENTS.md 与 ADR-0029 与实现四者口径一致（Task 10 完成）；人工装机清单已登记且**未执行**（Agent 止于编译与静态检查，装机归人工——AGENTS.md 分工条款）。

## 留给后续计划的接口

- **2e（书城接线）**：`currentSource`/`observeDefaultSource` 重构为格式中立类型（密封 `SourceDefinition` + 展示信息）、`LibraryViewModel.sources` 放开 format 过滤、`BookSourceRepository.getBookTypeList` 的脚本分支（`exploreUrl` 条目 → `BookType`，url 字段承载 URL 规则串）、`setDefaultSource` 对脚本行的启用路径。届时三条「脚本行不当默认源」锁形测试随机制重构改写（判据从「载体是原生规则」变成「载体可表达两种出身」）。
- **导入报告升级**：`ScriptRuleSet.unsupported`（LOGIN/WEB_JS/XPATH/JS…）逐项上导入预览 UI，替换 module_me 现有的正则全文扫描（`contains("<js>")`）——两处各判一次会漂移出「预览说没事、求值报不支持」的分裂。
- **`OkHttpScriptTransport` 协程取消**（2c 执行期修正记录第 8 条登记的债）：`call.cancel()` 接线，取消后不再跑满重试。
- **Plan 3（沙箱）**：`JsEvaluationPendingException` 的求值点全部就位（URL 选项 `js`/`bodyJs`、规则段 `@js:`/`<js>`、`{{}}` 的 JS 表达式、`init` 的 JS 分支）——沙箱落地时把这些点接到执行器。

## 执行期修正记录

1. **Task 8 断言形态**：计划原文（Step 用例）要 `assertSame(typed, failure)` 锁「类型化异常原样上抛」。
   实测红灯暴露这是**测不到的**：kotlinx 协程的栈迹恢复会在 `withContext(Dispatchers.IO)` 边界**复制**
   异常对象（诊断打印：`same=false`、`cause=IllegalStateException`、消息逐字相等、栈深度 52→7），
   实例身份断言永远假。改为「消息逐字相等 + 不含『章节内容解析失败』」两条，既锁住透出也锁住没被裹。
   测试里留了注释说明这条机制，防止后来人「顺手改回 assertSame」。
2. **Task 8 接缝改名与第四成因搬家**：`resolveJsoupParser` → `resolveParser`（返回类型
   `JsoupBookParser` → `BookParser`），原第 4 条成因「parser 不支持抓正文」的 `as? JsoupBookParser`
   向下转型**必须拆掉**（脚本源会被它误判成「不支持」），判据搬家到 `fetchAndStore` 的 `when-else`。
   5 处测试调用点随改名更新。
3. **Task 9 用例数与一页 URL 期望**：计划写「3 例」，实际交付 **4 例**——三型合成源之外补一例
   尖括号分页的**第二页**（只锁首页等于没锁页码推进）。首版期望写成 `/s,2`，与实现给出的 `/s2` 冲突；
   核对 `ScriptUrlResolver.anglePages` 与 `ScriptUrlResolverTest:95-97` 后确认仓内 §6.4 的读法是有意的
   并已锁形，**改测试不改实现**（真实源验证仍欠，见装机清单第 5 项）。
4. **Task 10 AGENTS.md 措辞**：计划给定的替换段开头是「求值链路已全线落地」。照抄会与同段末尾
   「`@js:` 待 Plan 3」自相矛盾，收窄为「**声明式**求值链路已全线**接页面**」，并补一句
   「接了页面不等于 JS 已能跑，覆盖率上限仍是纯声明式那部分」。
5. **Task 10 桩残留的连带纠偏**：计划只点名规格 §11/§1.5/§10、AGENTS.md、ADR-0029、路线图与装机清单，
   但 `git grep` 显示桩还留在四处旧表述里，一并就地校正（否则文档自相矛盾）：规格头部「相关实现」里
   指向 `ScriptSourcePendingParser.kt` 的「当前桩」一条、规格 §12「失败要如实报」条、ADR-0029
   「当前求值状态」与「未落地」两条、AGENTS.md 跨模块可见性条里的「桩解析器」。
   `docs/test-coverage-todo.md` 阶段一第 7 项（判据是「解释器未实现」提示）标注**作废并指向阶段二**
   而不删除——它是人工台账，删了会看不出这条为何消失。
6. **未改的旧文档**：`2026-09-08-script-book-source-foundation.md` 与各分段计划正文里 2a/2b/2c 时点的
   「脚本行求值仍命中桩」保留原样——那是分段时的实况记录，不是当前口径；只在路线图 2d 段追加执行结果。
   `.qoder/repowiki/` 内自动生成的网络 API 文档提到 `JsoupSourceReader` 抛 `IllegalStateException`，
   对原生分支仍成立（脚本分支原样重抛），不手改生成物。
7. **退出判据 2 的读法（照实说）**：桩与桩异常在**全部代码（含 KDoc）、AGENTS.md、ADR-0029、规格、
   装机清单**里已零引用；但全文 `git grep` 仍会在四份历史计划文档与本计划正文里命中——它们记录着
   「新建这个桩」与「本计划替掉它」。把历史计划正文抹平等于销毁分段证据，因此判据 2 按
   「代码与当前口径文档零引用」达成，**不是**字面的全仓全文零命中。
