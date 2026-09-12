# 脚本书源规则词法与解析（Plan 2a）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `lib_book_source` 内建成脚本书源**规则字符串的词法层**：括号感知的分隔符扫描、模式判定、`%%`/`||`/`&&` 切分成树、正则替换三形态、索引语法、链式规则的段结构与取值器，以及原始 JSON → 规则集的装载。产出是一个**纯函数、零网络、零 Jsoup** 的可测层。

**Architecture:** 本文件是三阶段「脚本书源解释器」（Plan 2）拆成的第一段。整体决策见 `docs/adr/0029-script-book-source-import.md`，**语法事实源是 `docs/superpowers/specs/2026-09-08-script-source-rule-grammar.md`（下称「规格」）**——本计划每一条判定都以它为准，冲突时以规格为准并回改本文件。后续三段各自另写计划：**2b** 求值器（拿 2a 的树去解 HTML/JSON，含 CSS/JSONPath/正则三后端与变量系统）、**2c** URL 语义与网络（`,{...}` 选项、POST、charset、`{{page}}`/`<>`）、**2d** `BookParser` 实现与聚合搜索接入 + 金标准 fixtures，替掉 Plan 1 的 `ScriptSourcePendingParser`。2a 落地后脚本书源仍然**不可求值**（桩还在），但「规则能不能被读懂」这件事第一次变成可断言的。

**Tech Stack:** Kotlin（AGP 9 内置 Kotlin）、kotlinx-serialization（`JsonObject` 装载）、JUnit 4。无新依赖。

**术语红线（全程有效）：** 全仓禁止出现该生态项目名字样——代码、注释、KDoc、测试、文档、提交信息一律用「脚本书源 / 脚本书源格式」。检查命令必须带 `--exclude-dir=build`（`build/` 里 Material3 西班牙语资源里「展开」一词的拼写含有该子串会误命中），检查模式写作 `"[l]egado"`（方括号只作用于匹配语义）。以 `git grep -in "[l]egado"` 为准，恒为零命中。

---

## 为什么 2a 要先切出来

规格 §2~§4 的难点全部在「**这条规则字符串到底被切成什么**」，而不是「切出来的东西怎么求值」：括号深度、组合符优先级、索引方言、`##` 的三形态、取值器与属性的区分——每一条都必须在没有 DOM、没有网络、没有 JS 引擎的条件下先有一个唯一答案，并且这个答案要能用极小的单测锁住。把这层单独做完的收益：

1. **2b 的求值器不必同时调试两件事**（语义解释 + 词法切分），故障可定位；
2. 规格 §11 列的 13 条未知项里，**有 6 条纯属词法**（组合符优先级、`<>`、索引越界、`%%` 空段、转义、`##` 嵌套），本段就能逐条钉成代码事实；
3. 「一条源能不能被本项目读懂」可以在导入时就给出**逐规则**的报告，而不是等到用户点开这本书才发现解不出来。

## 与 Plan 1 的衔接（不要重新发明）

- Plan 1 已建 `:lib_book_source` 与包 `com.ebook.source.analyze`（原生解析器）；本段新开平级包 **`com.ebook.source.script`**。
- `com.ebook.api.entity.ScriptSourceRule` 是**导入校验用的最小模型**（6 个顶层字段），本段的 `ScriptRuleSet` 是**求值用的规则装载**，两者读同一份原始 JSON、职责不同，**不得合并**（合并会让导入校验被迫携带全部规则字段，也会让规格里「未知键忽略」的口径渗进校验层）。
- 规格 §12 列的既有裁决（`ListPageUrl`、`TocPager`、`TocPageUrl.join`、`ChapterPageMatcher`）**原生链路独占**，脚本链路不得复用其判定口径；本段是纯词法，天然不碰它们——但也不许「顺手」调用。
- 规格 §11-5 的 `useRealUrl` 经查证不存在，已从能力清单删除，**不要实现**。
- 规格 §1.1 已订正 `bookSourceType` 取值（`0` 文本、`1` 音频、`2` 图片、`3` 文件、`4` 视频）；`ScriptSourceRule.kt` 的 KDoc 仍写着旧顺序（行为用的是 `!= 0`，不受影响），**Task 8 一并校正**。

## 本段要钉死的规格条款（决定成败的 5 件事）

| # | 规格出处 | 钉死的口径 | 落在 |
|---|---|---|---|
| 1 | §2.3 嵌套 | 分隔符判定一律先看括号深度（`{{}}` 计 2、`{}`/`[]` 计 1），深度 > 0 内部的 `%%`/`||`/`&&`/`##`/`@` **不切** | Task 1 |
| 2 | §2.3 次序 | 切分次序固定 `%%` → `||` → `&&` → `@`，实现成**树**（`Percent`/`FirstOf`/`AllOf`/`Leaf`），空段为 `Empty` | Task 4 |
| 3 | §2.4 正则三形态 | `##` 尾段只在**第一个**深度 0 的 `##` 处剥；三井号收尾 = OnlyOne；只到一个 `##` = 净化且替换为空 | Task 3 |
| 4 | §4 索引 | 区间是**闭区间**（规格 §4 已就此订判据）；端点按集合边界裁剪、单个越界索引跳过、全被过滤即「未取到值」，**一律不抛**；`[-1:0]` 与「start > end」都表达反序 | Task 5 |
| 5 | §2.1 标志表 | 模式识别按表内顺序先匹配者胜；`@cache:` 判 `UNSUPPORTED`（规格 §11-10：一切材料检索不到该前缀）；XPath/JS 判出但**不在本段求值** | Task 2 |

---

## File Structure（本计划产出/改动的文件全景）

```
lib_book_source/
  src/main/java/com/ebook/source/script/
    RuleScanner.kt            [新建] 括号深度与「深度 0 分隔符」定位（规格 §2.3 嵌套）
    RuleMode.kt               [新建] 模式标志表 + 载荷剥离（§2.1）
    RegexReplacement.kt       [新建] ## 尾段的三形态解析（§2.4）
    RuleSplitter.kt           [新建] 规则串 → RuleNode 树 + 替换尾段（§2.2/§2.3）
    IndexSelector.kt          [新建] 索引方言解析 + 对 List 的裁剪（§4）
    ChainLink.kt              [新建] 默认模式段链结构、取值器与属性区分（§2.5/§3.2）
    ScriptRuleExceptions.kt   [新建] 类型化异常（规格 §3.3/§9 的「失败要如实报」）
    ScriptRuleSet.kt          [新建] 原始 JSON → 顶层 URL + 六类规则对象字段表（§1）
  src/test/java/com/ebook/source/script/
    RuleScannerTest.kt  RuleModeTest.kt  RegexReplacementTest.kt  RuleSplitterTest.kt
    IndexSelectorTest.kt  ChainLinkTest.kt  ScriptRuleSetTest.kt   [新建]
  build.gradle.kts            [不改] 现有依赖已够（词法层零外部依赖）
lib_ebook_api/src/main/java/com/ebook/api/entity/ScriptSourceRule.kt  [改：Task 8 校正 bookSourceType KDoc]
AGENTS.md / 规格文档 / 本文件  [改：Task 8 记录落地与本段边界]
```

包内可见性：**本段全部类型对 `lib_book_source` 之外不需要可见**（求值器在 2b，与实现同模块），因此按 Kotlin 惯例标 `internal`；被 2b 之后跨模块使用的东西到那时再放开。**注意 Plan 1 的教训**：`internal` 只在同模块（含同模块测试源集）可见，测试必须与本段实现同模块，否则编译不过。

---

### Task 1: `RuleScanner` —— 括号深度与可切分位置

**Files:**
- Create: `lib_book_source/src/main/java/com/ebook/source/script/RuleScanner.kt`
- Test: `lib_book_source/src/test/java/com/ebook/source/script/RuleScannerTest.kt`

- [x] **Step 1: 写失败的测试**

创建 `lib_book_source/src/test/java/com/ebook/source/script/RuleScannerTest.kt`：

```kotlin
package com.ebook.source.script

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 规格 §2.3「嵌套」：`{{}}`/`{}`/`[]` 内的分隔符不参与切分。
 * 这些形态全部来自真实语料与文档示例，任一条被切错都会把整条规则腰斩。
 */
class RuleScannerTest {

    @Test
    fun `深度数组把普通字符记为 0`() {
        assertEquals(listOf(0, 0, 0, 0), RuleScanner.depths("abcd").toList())
    }

    @Test
    fun `双花括号内部深度为 2`() {
        // {{  a  }}  → 下标 2..3 在内部
        val d = RuleScanner.depths("{{ab}}")
        assertEquals(0, d[0])
        assertEquals(2, d[2])
        assertEquals(2, d[3])
    }

    @Test
    fun `花括号与方括号可嵌套且闭合后回到 0`() {
        val d = RuleScanner.depths("a[{b}]c")
        assertEquals(0, d[0])
        assertEquals(0, d[1])   // 开括号本身在外层
        assertEquals(1, d[2])
        assertEquals(2, d[3])
        assertEquals(1, d[4])   // 右括号按「先减后记」落在外层：闭合符本身不该被当成深水区内部
        assertEquals(0, d[5])
        assertEquals(0, d[6])
    }

    @Test
    fun `未闭合的括号不把尾部拖进深水区之外`() {
        // 只要求「不抛、且深度非负」：真实规则串存在残缺形态
        val d = RuleScanner.depths("a{b")
        assertEquals(3, d.size)
        assertEquals(1, d[2])
    }

    @Test
    fun `多余的右括号深度被夹到零不出现负数`() {
        val d = RuleScanner.depths("a}}b")
        assertTrueAllNonNegative(d)
    }

    @Test
    fun `顶层定位跳过括号内的分隔符`() {
        val s = "a&&b,{c&&d}&&e"
        val d = RuleScanner.depths(s)
        // 下标 1 是第一个 && 的起点（在顶层）；花括号内的 && 不算；末尾 } 之后还有一个
        assertEquals(listOf(1, s.lastIndexOf("&&")), RuleScanner.topLevelOf(s, d, "&&"))
    }

    @Test
    fun `顶层定位尊重索引区间`() {
        val s = "a||b||c"
        val d = RuleScanner.depths(s)
        assertEquals(listOf(1), RuleScanner.topLevelOf(s, d, "||", 0, 5))
    }

    @Test
    fun `空串与比 sep 短的串都返回空定位`() {
        assertEquals(emptyList<Int>(), RuleScanner.topLevelOf("", IntArray(0), "&&"))
        assertEquals(emptyList<Int>(), RuleScanner.topLevelOf("a", intArrayOf(0), "&&"))
    }

    private fun assertTrueAllNonNegative(d: IntArray) {
        d.forEachIndexed { i, v -> assertTrue("下标 $i 深度为负：$v", v >= 0) }
        assertEquals(0, d.last())
    }
}
```

补 `assertTrue` 的静态导入：`import org.junit.Assert.assertTrue`。

- [x] **Step 2: 运行确认失败**

Run: `./gradlew :lib_book_source:compileDebugUnitTestKotlin`
Expected: FAIL——`Unresolved reference: RuleScanner`。

- [x] **Step 3: 实现**

创建 `lib_book_source/src/main/java/com/ebook/source/script/RuleScanner.kt`：

```kotlin
package com.ebook.source.script

/**
 * 规则串的「可切分位置」扫描器（规格 §2.3 的「嵌套」条）。
 *
 * 为什么需要它：分隔符（`%%`/`||`/`&&`/`##`/`@`）在字面上无处不在，而语料里
 * `##[|\\]` 这类字符类、`{"body":"a&&b"}` 这类 URL 选项值、`{{$.id}}` 这类插值里
 * 都可能含分隔符。任何一次「裸切」都会把一条规则腰斩，且切错的后果不是崩溃而是
 * 解出**不相干的内容**——本仓最难查的那类故障。所以所有分隔符判定都必须先看括号深度。
 *
 * 深度口径：`{{`/`}}` 各计 2（双花括号是能自我识别的强标记，计 2 让 `{{` 与 `{`
 * 在同一套计数下共存），`{`/`}` 与 `[`/`]` 各计 1。右括号按「先减后记」处理，
 * 使闭合符本身落在外层深度上；多余的右括号用 `coerceAtLeast(0)` 夹住，
 * 深度恒非负——残缺规则串是真实存在的输入形态，扫描器不许抛。
 */
internal object RuleScanner {

    /** 每个下标处「包住该字符的括号层数」。返回数组长度恒等于入参长度。 */
    fun depths(s: String): IntArray {
        val d = IntArray(s.length)
        var depth = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val n = if (i + 1 < s.length) s[i + 1] else ' '
            if (c == '{' && n == '{') {
                d[i] = depth
                d[i + 1] = depth + 1
                depth += 2
                i += 2
            } else if (c == '}' && n == '}') {
                depth = (depth - 2).coerceAtLeast(0)
                d[i] = depth
                d[i + 1] = depth
                i += 2
            } else if (c == '{' || c == '[') {
                d[i] = depth
                depth++
                i++
            } else if (c == '}' || c == ']') {
                depth = (depth - 1).coerceAtLeast(0)
                d[i] = depth
                i++
            } else {
                d[i] = depth
                i++
            }
        }
        return d
    }

    /**
     * `sep` 在 `[from, to)` 内、且**起点位于深度 0** 的全部下标（按出现顺序）。
     *
     * 命中后跳过整个 `sep`，因此 `###` 这种收尾不会被当成两个 `##` 的重叠起点。
     */
    fun topLevelOf(
        s: String,
        d: IntArray,
        sep: String,
        from: Int = 0,
        to: Int = s.length,
    ): List<Int> {
        if (sep.isEmpty()) return emptyList()
        val out = mutableListOf<Int>()
        var i = from
        val last = to - sep.length
        while (i <= last) {
            if (d.getOrElse(i) { 0 } == 0 && s.startsWith(sep, i)) {
                out += i
                i += sep.length
            } else {
                i++
            }
        }
        return out
    }
}
```

- [x] **Step 4: 运行测试通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.RuleScannerTest"`
Expected: PASS（8 个用例）。

- [x] **Step 5: 红线 grep + 提交**

```bash
git grep -in "[l]egado" || echo CLEAN
git add lib_book_source/src/main/java/com/ebook/source/script/RuleScanner.kt lib_book_source/src/test/java/com/ebook/source/script/RuleScannerTest.kt
git commit -m "$(cat <<'EOF'
feat(lib_book_source): 脚本书源规则扫描器——括号深度与顶层分隔符定位

规则串里 %% || && ## @ 等分隔符与字面文本同形（字符类、URL 选项值、插值），
裸切不报错只会解出不相干的内容，故所有切分先过一层括号深度判定。
EOF
)"
```

---

### Task 2: `RuleMode` —— 模式标志表与载荷剥离

**Files:**
- Create: `lib_book_source/src/main/java/com/ebook/source/script/RuleMode.kt`
- Test: `lib_book_source/src/test/java/com/ebook/source/script/RuleModeTest.kt`

- [x] **Step 1: 写失败的测试**

```kotlin
package com.ebook.source.script

import org.junit.Assert.assertEquals
import org.junit.Test

/** 规格 §2.1 标志表：识别顺序即下表顺序，先匹配者胜。 */
class RuleModeTest {

    @Test
    fun `无标志即默认链式模式`() {
        assertEquals(RuleMode.DEFAULT_CHAIN to "tag.a.0@text", RuleMode.of("tag.a.0@text"))
    }

    @Test
    fun `显式默认标志 @@ 被剥掉`() {
        // §2.1/§5.1：@@ 是默认模式的显式写法，剥掉后载荷与省略写法同形
        assertEquals(RuleMode.DEFAULT_CHAIN to "tag.a@href", RuleMode.of("@@tag.a@href"))
    }

    @Test
    fun `css 前缀剥离后余下选择器`() {
        assertEquals(RuleMode.CSS to ".articleDiv p@textNodes", RuleMode.of("@css:.articleDiv p@textNodes"))
    }

    @Test
    fun `双斜杠开头是 XPath 且保留原文`() {
        assertEquals(RuleMode.XPATH to "//li[3]/a/@text()", RuleMode.of("//li[3]/a/@text()"))
    }

    @Test
    fun `xpath 前缀不区分大小写`() {
        assertEquals(RuleMode.XPATH to "//a/@href", RuleMode.of("@XPath://a/@href"))
        assertEquals(RuleMode.XPATH to "//a/@href", RuleMode.of("@xpath://a/@href"))
    }

    @Test
    fun `点美元号开头是 JSONPath 并保留原文`() {
        assertEquals(RuleMode.JSON_PATH to "$.data[0].bookName", RuleMode.of("$.data[0].bookName"))
        assertEquals(RuleMode.JSON_PATH to "$._id", RuleMode.of("@json:$._id"))
    }

    @Test
    fun `单花括号遗留形态剥掉花括号后按 JSONPath 处理`() {
        assertEquals(RuleMode.JSON_PATH to "$._id", RuleMode.of("{$._id}"))
    }

    @Test
    fun `冒号开头是正则 AllInOne 并剥掉前导冒号`() {
        assertEquals(
            RuleMode.REGEX_ALL_IN_ONE to "href=\"([^\"]+)\"[^>]*>([^<]*)",
            RuleMode.of(":href=\"([^\"]+)\"[^>]*>([^<]*)"),
        )
    }

    @Test
    fun `js 前缀与内联 js 都判为 JS 模式`() {
        assertEquals(RuleMode.JS to "result.replace(/x/,'')", RuleMode.of("@js:result.replace(/x/,'')"))
        assertEquals(
            RuleMode.JS to "tag.li<js>1</js>//a",
            RuleMode.of("tag.li<js>1</js>//a"),
        )
    }

    @Test
    fun `put 与 get 前缀各自成模式`() {
        assertEquals(RuleMode.VARIABLE_PUT to "{bid:\"//a/@href\"}", RuleMode.of("@put:{bid:\"//a/@href\"}"))
        assertEquals(RuleMode.VARIABLE_GET to "bid", RuleMode.of("@get:bid"))
    }

    @Test
    fun `cache 前缀判为不支持而非猜测语义`() {
        // 规格 §11-10：@cache: 在一切已读材料里检索不到，猜语义会产出「看着正常、实则错到底」的结果
        val (mode, body) = RuleMode.of("@cache:100")
        assertEquals(RuleMode.UNSUPPORTED, mode)
        assertEquals("@cache:100", body)
    }

    @Test
    fun `空串判为默认模式且载荷为空`() {
        assertEquals(RuleMode.DEFAULT_CHAIN to "", RuleMode.of(""))
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `./gradlew :lib_book_source:compileDebugUnitTestKotlin`
Expected: FAIL——`RuleMode` 未定义。

- [x] **Step 3: 实现**

```kotlin
package com.ebook.source.script

/**
 * 一条规则（或规则的一段）用哪种解析模式（规格 §2.1 标志表）。
 *
 * 模式必须在链式切分**之前**判定：标志前缀本身就是语义的一部分，
 * 而 `@` 同时也是链式模式的分隔符——先切再判会把 `@css:` 切成一个空段。
 *
 * [XPATH]、[JS]、[UNSUPPORTED] 在本段（2a）只被**识别**，求值分别延后到
 * 2b（XPath 已由 ADR-0029 判为明确延后）、Plan 3（JS 需沙箱执行器）与导入报告
 * （不支持项）。识别出来却不实现，和静默当成默认模式解错，是两件完全不同的事。
 */
internal enum class RuleMode {
    DEFAULT_CHAIN,
    CSS,
    XPATH,
    JSON_PATH,
    REGEX_ALL_IN_ONE,
    VARIABLE_PUT,
    VARIABLE_GET,
    JS,
    UNSUPPORTED,
    ;

    companion object {

        /** 判定模式并剥掉标志前缀，返回（模式, 载荷）。剥前缀是为了 2b 拿到的就是净内容。 */
        fun of(rule: String): Pair<RuleMode, String> {
            val body = rule.trim()
            return when {
                // `@@` 是默认（链式）模式的**显式**标志（§2.1 第一行「直接写时可以省略 `@@`」，
                // §5.1 更要求插值里写默认规则必须带它）。必须在此剥掉：留着它会让下游按 `@`
                // 切链式段时先切出两个空段，把 `@@tag.a@href` 变成「空、空、tag.a、href」。
                body.startsWith("@@") -> DEFAULT_CHAIN to body.substring(2)
                body.startsWith("@js:") -> JS to body.substring(4)
                // <js></js> 可出现在任意位置并充当分隔符（§2.1），故用 contains 且整串交给 JS 处理
                body.contains("<js>") -> JS to body
                body.startsWith("@css:") -> CSS to body.substring(5)
                body.startsWith("@xpath:", ignoreCase = true) -> XPATH to body.substring(7)
                body.startsWith("//") -> XPATH to body
                body.startsWith("@json:", ignoreCase = true) -> JSON_PATH to body.substring(6)
                body.startsWith("$.") -> JSON_PATH to body
                body.startsWith("@put:") -> VARIABLE_PUT to body.substring(5)
                body.startsWith("@get:") -> VARIABLE_GET to body.substring(5)
                body.startsWith("@cache:") -> UNSUPPORTED to body
                body.length >= 2 && body.startsWith("{") && !body.startsWith("{{") && body.endsWith("}") ->
                    // 单花括号是上一代格式遗留，文档明示只走 JSONPath（§2.1）。
                    // 必须先排除 `{{`：整条就是一个插值的规则（`{{java.xxx}}`）属 JS/插值，不是 JSONPath
                    JSON_PATH to body.substring(1, body.length - 1)
                body.startsWith(":") -> REGEX_ALL_IN_ONE to body.substring(1)
                else -> DEFAULT_CHAIN to body
            }
        }
    }
}
```

- [x] **Step 4: 运行测试通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.RuleModeTest"`
Expected: PASS（11 个用例）。

- [x] **Step 5: 红线 grep + 提交**

```bash
git grep -in "[l]egado" || echo CLEAN
git add lib_book_source/src/main/java/com/ebook/source/script/RuleMode.kt lib_book_source/src/test/java/com/ebook/source/script/RuleModeTest.kt
git commit -m "$(cat <<'EOF'
feat(lib_book_source): 脚本书源规则模式标志表

模式必须在链式切分前判定：@ 既是标志前缀的一部分又是链式分隔符，先切再判会把
@css: 切成空段。XPath/JS/不支持前缀只识别不求值——识别出来不实现，和静默按
默认模式解错内容是两件不同的事。
EOF
)"
```

---

### Task 3: `RegexReplacement` —— `##` 尾段的三形态

**Files:**
- Create: `lib_book_source/src/main/java/com/ebook/source/script/RegexReplacement.kt`
- Test: `lib_book_source/src/test/java/com/ebook/source/script/RegexReplacementTest.kt`

- [x] **Step 1: 写失败的测试**

```kotlin
package com.ebook.source.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 规格 §2.4 正则三形态中的两形态（净化与 OnlyOne）；AllInOne 是模式不是替换（Task 2）。 */
class RegexReplacementTest {

    @Test
    fun `两个井号之间是模式其后是替换内容`() {
        assertEquals(
            RegexReplacement(pattern = "全文阅读", replacement = "", onlyFirst = false),
            RegexReplacement.parse("##全文阅读"),
        )
    }

    @Test
    fun `替换内容为空时第二个井号段可省略`() {
        assertEquals(
            RegexReplacement(pattern = "搜索.*手机访问", replacement = "", onlyFirst = false),
            RegexReplacement.parse("##搜索.*手机访问##"),
        )
    }

    @Test
    fun `三井号收尾是 OnlyOne 只对第一个匹配替换`() {
        assertEquals(
            RegexReplacement(
                pattern = "/book/(\\d+)",
                replacement = "https://img.x.com/bookpic/s$1.jpg",
                onlyFirst = true,
            ),
            RegexReplacement.parse("##/book/(\\d+)##https://img.x.com/bookpic/s$1.jpg###"),
        )
    }

    @Test
    fun `替换文本里的捕获组引用原样保留`() {
        val r = RegexReplacement.parse("##a##b$1c")
        assertEquals("b$1c", r?.replacement)
    }

    @Test
    fun `模式文本里的井号按第一个未闭合的分隔判定`() {
        // 语料要求：##$##{"webView":true} 惯用法（§2.4）——模式是 `$`，替换是那串选项
        val r = RegexReplacement.parse("##\$##{\"webView\":true}")
        assertEquals("\$", r?.pattern)
        assertEquals("{\"webView\":true}", r?.replacement)
        assertTrue("非三井号收尾即净化形态", r != null && !r.onlyFirst)
    }

    @Test
    fun `不以井号开头则不是替换段`() {
        assertNull(RegexReplacement.parse("tag.a@text"))
    }

    @Test
    fun `只有两个井号时模式为空串`() {
        assertEquals(RegexReplacement(pattern = "", replacement = "", onlyFirst = false), RegexReplacement.parse("##"))
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `./gradlew :lib_book_source:compileDebugUnitTestKotlin`
Expected: FAIL——`RegexReplacement` 未定义。

- [x] **Step 3: 实现**

```kotlin
package com.ebook.source.script

/**
 * `##` 尾段：正则替换的净化与 OnlyOne 两形态（规格 §2.4）。
 *
 * - 净化（[onlyFirst] = false）：**循环匹配替换**，跟在任意其他规则之后；
 * - OnlyOne（[onlyFirst] = true，三井号收尾）：只对**第一个**匹配替换。
 *
 * [pattern] 与 [replacement] 都按 Java 正则方言使用（文档示例含 `(?i)`、`\w`、`\d`、`.*?`），
 * 本类只负责**切出这两段**，不执行替换——执行在 2b。把「解析替换段」与「跑替换」分开，
 * 是因为词法层必须能脱离任何被解析的文档单独锁形，而跑替换需要 Jsoup 之外的输入语义。
 *
 * 为什么在**第一个** `##` 处剥：语料的 `##$##{...}` 惯用法要求模式段之后的 `##` 属于
 * 「模式与替换的分界」，而替换文本内部再出现 `##` 时它已经是字面量了。
 */
internal data class RegexReplacement(
    val pattern: String,
    val replacement: String,
    val onlyFirst: Boolean,
) {
    companion object {

        /** [tail] 必须以 `##` 开头（调用方在第一个深度 0 的 `##` 处切），否则返回 null */
        fun parse(tail: String): RegexReplacement? {
            if (!tail.startsWith("##")) return null
            var rest = tail.substring(2)
            var onlyFirst = false
            when {
                rest.endsWith("###") -> {
                    onlyFirst = true
                    rest = rest.substring(0, rest.length - 3)
                }
                rest.endsWith("##") -> rest = rest.substring(0, rest.length - 2)
            }
            val sep = rest.indexOf("##")
            return if (sep < 0) {
                RegexReplacement(rest, "", onlyFirst)
            } else {
                RegexReplacement(rest.substring(0, sep), rest.substring(sep + 2), onlyFirst)
            }
        }
    }
}
```

- [x] **Step 4: 运行测试通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.RegexReplacementTest"`
Expected: PASS（7 个用例）。

- [x] **Step 5: 红线 grep + 提交**

```bash
git grep -in "[l]egado" || echo CLEAN
git add lib_book_source/src/main/java/com/ebook/source/script/RegexReplacement.kt lib_book_source/src/test/java/com/ebook/source/script/RegexReplacementTest.kt
git commit -m "$(cat <<'EOF'
feat(lib_book_source): 脚本书源正则替换尾段解析

解析与执行分离：本类只切出 pattern 与 replacement 两段，跑替换留给求值层。
在第一个未闭合的 ## 处剥离，是语料 ##$##{...} 惯用法成立的前提。
EOF
)"
```

---

### Task 4: `RuleSplitter` —— 规则串切成树

**Files:**
- Create: `lib_book_source/src/main/java/com/ebook/source/script/RuleNode.kt`
- Create: `lib_book_source/src/main/java/com/ebook/source/script/RuleSplitter.kt`
- Test: `lib_book_source/src/test/java/com/ebook/source/script/RuleSplitterTest.kt`

- [x] **Step 1: 写失败的测试**

```kotlin
package com.ebook.source.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 规格 §2.2/§2.3：切分次序 `%%` → `||` → `&&` → 模式判定，实现成树。
 *
 * 树形而非「三个列表字段」的理由：`%%` 与 `||` 的语义作用在不同层（一路交错取数 vs
 * 一路短路取第一个有值），扁平表达会让求值层自己去猜优先级，而优先级正是 §11-1 的未知项。
 */
class RuleSplitterTest {

    private fun parse(rule: String) = RuleSplitter.parse(rule).root

    @Test
    fun `单条链式规则是一个叶`() {
        assertEquals(
            RuleNode.Leaf(RuleMode.DEFAULT_CHAIN, "tag.a.0@text"),
            parse("tag.a.0@text"),
        )
    }

    @Test
    fun `双与号合并全部分支`() {
        assertEquals(
            RuleNode.AllOf(
                listOf(
                    RuleNode.Leaf(RuleMode.DEFAULT_CHAIN, "class.odd.0@tag.a.0@text"),
                    RuleNode.Leaf(RuleMode.DEFAULT_CHAIN, "tag.dd.0@tag.h1@text"),
                )
            ),
            parse("class.odd.0@tag.a.0@text&&tag.dd.0@tag.h1@text"),
        )
    }

    @Test
    fun `双竖线短路取第一个有值`() {
        assertEquals(
            RuleNode.FirstOf(
                listOf(
                    RuleNode.Leaf(RuleMode.DEFAULT_CHAIN, "class.odd.0@text"),
                    RuleNode.Leaf(RuleMode.DEFAULT_CHAIN, "tag.dd.0@text"),
                )
            ),
            parse("class.odd.0@text||tag.dd.0@text"),
        )
    }

    @Test
    fun `百分号是最外层——三路交错取数`() {
        val node = parse("a@text%%b@text%%c@text")
        assertEquals(
            RuleNode.Percent(
                listOf(
                    RuleNode.Leaf(RuleMode.DEFAULT_CHAIN, "a@text"),
                    RuleNode.Leaf(RuleMode.DEFAULT_CHAIN, "b@text"),
                    RuleNode.Leaf(RuleMode.DEFAULT_CHAIN, "c@text"),
                )
            ),
            node,
        )
    }

    @Test
    fun `百分号优先于竖线与双与号`() {
        // a&&b %% c||d  →  Percent(AllOf(a,b), FirstOf(c,d))
        assertEquals(
            RuleNode.Percent(
                listOf(
                    RuleNode.AllOf(
                        listOf(
                            RuleNode.Leaf(RuleMode.DEFAULT_CHAIN, "a"),
                            RuleNode.Leaf(RuleMode.DEFAULT_CHAIN, "b"),
                        )
                    ),
                    RuleNode.FirstOf(
                        listOf(
                            RuleNode.Leaf(RuleMode.DEFAULT_CHAIN, "c"),
                            RuleNode.Leaf(RuleMode.DEFAULT_CHAIN, "d"),
                        )
                    ),
                )
            ),
            parse("a&&b%%c||d"),
        )
    }

    @Test
    fun `空分支保留为 Empty 节点而不是被丢掉`() {
        // §2.3 空段行为：|| 的空支要能被「未取到值」语义表达，故必须留在树上
        assertEquals(
            RuleNode.FirstOf(listOf(RuleNode.Leaf(RuleMode.DEFAULT_CHAIN, "a"), RuleNode.Empty)),
            parse("a||"),
        )
    }

    @Test
    fun `替换尾段被剥出且不进入树`() {
        val p = RuleSplitter.parse("tag.a@text##全文阅读")
        assertEquals(RuleNode.Leaf(RuleMode.DEFAULT_CHAIN, "tag.a@text"), p.root)
        assertEquals(RegexReplacement("全文阅读", "", false), p.replacement)
    }

    @Test
    fun `括号内的组合符不切`() {
        assertEquals(
            RuleNode.Leaf(RuleMode.CSS, ".a[href=\"x&&y\"]@text"),
            parse("@css:.a[href=\"x&&y\"]@text"),
        )
    }

    @Test
    fun `插值内的组合符不切`() {
        assertEquals(
            RuleNode.Leaf(RuleMode.DEFAULT_CHAIN, "name{{a&&b}}"),
            parse("name{{a&&b}}"),
        )
    }

    @Test
    fun `URL 选项尾段内的双与号不切`() {
        assertEquals(
            RuleNode.Leaf(RuleMode.DEFAULT_CHAIN, "/search/,{\"body\":\"a&&b\"}"),
            parse("/search/,{\"body\":\"a&&b\"}"),
        )
    }

    @Test
    fun `js 段不参与组合切分`() {
        // §2.2 硬约束 1：组合符不辖 js。整条以 @js: 开头即为一个 JS 叶
        val node = parse("@js:result+'||'+x")
        assertEquals(
            RuleNode.Leaf(RuleMode.JS, "result+'||'+x"),
            node,
        )
    }

    @Test
    fun `正则 AllInOne 整条成一个叶`() {
        assertEquals(
            RuleNode.Leaf(RuleMode.REGEX_ALL_IN_ONE, "href=\"([^\"]+)\""),
            parse(":href=\"([^\"]+)\""),
        )
    }

    @Test
    fun `空规则串解析为 Empty 且无替换段`() {
        val p = RuleSplitter.parse("   ")
        assertEquals(RuleNode.Empty, p.root)
        assertNull(p.replacement)
    }

    @Test
    fun `文档原文示例四段链`() {
        // 规格 §2.5 的原文示例：class.odd.0@tag.a.0@text||tag.dd.0@tag.h1@text##全文阅读
        val p = RuleSplitter.parse("class.odd.0@tag.a.0@text||tag.dd.0@tag.h1@text##全文阅读")
        assertEquals(
            RuleNode.FirstOf(
                listOf(
                    RuleNode.Leaf(RuleMode.DEFAULT_CHAIN, "class.odd.0@tag.a.0@text"),
                    RuleNode.Leaf(RuleMode.DEFAULT_CHAIN, "tag.dd.0@tag.h1@text"),
                )
            ),
            p.root,
        )
        assertEquals(RegexReplacement("全文阅读", "", false), p.replacement)
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `./gradlew :lib_book_source:compileDebugUnitTestKotlin`
Expected: FAIL——`RuleNode` / `RuleSplitter` 未定义。

- [x] **Step 3: 实现 `RuleNode.kt`**

```kotlin
package com.ebook.source.script

/**
 * 一条规则串切分后的树（规格 §2.2/§2.3）。
 *
 * 四种组合形态各有独立真值语义，求值层必须能一比一读出来：
 * [Percent] 每路都求值后按序交错取数；[FirstOf] 短路——前一支未取到值才走下一支；
 * [AllOf] 每支都求值后合并；[Leaf] 才是真正带模式的规则体。
 *
 * [Empty] 表达「这一段是空串」：它**不是**没有节点，而是「未取到值」这个事实的载体
 * （§2.3 空段行为）。把它折叠掉会让 `a||` 与 `a` 变成同一棵树，短路语义随之消失。
 */
internal sealed interface RuleNode {
    object Empty : RuleNode

    data class Leaf(val mode: RuleMode, val body: String) : RuleNode

    data class AllOf(val parts: List<RuleNode>) : RuleNode

    data class FirstOf(val alternatives: List<RuleNode>) : RuleNode

    data class Percent(val streams: List<RuleNode>) : RuleNode
}
```

- [x] **Step 4: 实现 `RuleSplitter.kt`**

```kotlin
package com.ebook.source.script

/** 一条规则串的切分结果：求值树 + 被剥到末尾的正则替换段（规格 §9 的第 2 步与第 6 步）。 */
internal data class ParsedRule(
    val root: RuleNode,
    val replacement: RegexReplacement?,
    val raw: String,
)

/**
 * 规则串 → 树。切分次序固定 `%%` → `||` → `&&` → 模式判定（规格 §2.3，本仓规定）。
 *
 * 为什么这次序是「本仓规定」而不是上游实证：公开文档只给出三个组合符各自的语义，
 * 没有给出混用时的优先级。清洁室实现必须有唯一答案，否则同一份书源在两次实现里会解出
 * 不同结果。本段把语料与文档示例逐条锁进单测；将来与真实源行为冲突时按 bug 修
 * （ADR-0029「清洁室语义偏差按 bug 修」），改次序时**必须同时改本 KDoc 与单测**。
 *
 * 下标一律相对**原始规则串**：括号深度只算一次并随递归传递下标区间，避免对子串
 * 重算深度时下标平移导致的错位（这类错位的症状是「括号有时保护得住、有时保护不住」）。
 */
internal object RuleSplitter {

    fun parse(rule: String): ParsedRule {
        val d = RuleScanner.depths(rule)
        val cut = RuleScanner.topLevelOf(rule, d, "##").firstOrNull()
        val valueEnd = cut ?: rule.length
        val replacement = cut?.let { RegexReplacement.parse(rule.substring(it)) }
        return ParsedRule(node(rule, d, 0, valueEnd), replacement, rule)
    }

    private fun node(s: String, d: IntArray, from: Int, to: Int): RuleNode {
        val (start, end) = trim(s, from, to)
        if (start >= end) return RuleNode.Empty
        // §2.2 硬约束 1：组合符不辖 js。`@js:` 与 `<js>` 辖整条规则，故在组合切分之前判定——
        // 否则 JS 里的 `'||'` 这类字面量会被当成组合符把一条规则切成两支。
        val probe = RuleMode.of(s.substring(start, end))
        if (probe.first == RuleMode.JS) return RuleNode.Leaf(RuleMode.JS, probe.second)
        for (sep in COMBINATORS) {
            val hits = RuleScanner.topLevelOf(s, d, sep.symbol, start, end)
            if (hits.isEmpty()) continue
            val parts = ArrayList<RuleNode>(hits.size + 1)
            var cursor = start
            for (h in hits) {
                parts += node(s, d, cursor, h)
                cursor = h + sep.symbol.length
            }
            parts += node(s, d, cursor, end)
            return sep.wrap(parts)
        }
        val (mode, body) = RuleMode.of(s.substring(start, end))
        return RuleNode.Leaf(mode, body)
    }

    /** 三个组合符按优先级排列，并各自绑定其树节点 */
    private class Combinator(val symbol: String, val wrap: (List<RuleNode>) -> RuleNode)

    private val COMBINATORS = listOf(
        Combinator("%%") { RuleNode.Percent(it) },
        Combinator("||") { RuleNode.FirstOf(it) },
        Combinator("&&") { RuleNode.AllOf(it) },
    )

    /** 去掉区间两端空白，返回（新起, 新止）。空白不改括号深度，故可安全缩区间。 */
    private fun trim(s: String, from: Int, to: Int): Pair<Int, Int> {
        var i = from
        var j = to
        while (i < j && s[i].isWhitespace()) i++
        while (j > i && s[j - 1].isWhitespace()) j--
        return i to j
    }
}
```

- [x] **Step 5: 运行测试通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.RuleSplitterTest"`
Expected: PASS（14 个用例）。

若 `js 段不参与组合切分` 用例失败，说明 `node()` 里的 JS 探针被漏掉或位置写错——探针必须在组合符循环之前。

- [x] **Step 6: 运行本模块全部测试**

Run: `./gradlew :lib_book_source:testDebugUnitTest`
Expected: PASS（既有 38 + 新增 40 = 78）。

- [x] **Step 7: 红线 grep + 提交**

```bash
git grep -in "[l]egado" || echo CLEAN
git add lib_book_source/src/main/java/com/ebook/source/script lib_book_source/src/test/java/com/ebook/source/script
git commit -m "$(cat <<'EOF'
feat(lib_book_source): 脚本书源规则串切分为组合树

切分次序固定 %% → || → && → 模式判定，公开文档没给混用优先级，本仓把唯一答案
钉成树形与单测；空段留作 Empty 节点，折叠掉它会让 a|| 与 a 同树、短路语义消失。
EOF
)"
```

---

### Task 5: `IndexSelector` —— 索引方言

**Files:**
- Create: `lib_book_source/src/main/java/com/ebook/source/script/IndexSelector.kt`
- Test: `lib_book_source/src/test/java/com/ebook/source/script/IndexSelectorTest.kt`

- [x] **Step 1: 写失败的解析测试**

```kotlin
package com.ebook.source.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 规格 §4 索引语法表。 */
class IndexSelectorTest {

    @Test
    fun `单个非负索引`() {
        assertEquals(IndexSelector.At(listOf(0)), IndexSelector.parse("[0]"))
    }

    @Test
    fun `负索引保留原值由求值时换算`() {
        assertEquals(IndexSelector.At(listOf(-1)), IndexSelector.parse("[-1]"))
    }

    @Test
    fun `逗号索引集`() {
        assertEquals(IndexSelector.At(listOf(1, 3)), IndexSelector.parse("[1,3]"))
    }

    @Test
    fun `排除式索引在方括号形态下以逗号分隔`() {
        assertEquals(IndexSelector.Excluding(listOf(1, 3)), IndexSelector.parse("[!1,3]"))
    }

    @Test
    fun `排除式序号在位置段里以冒号分隔`() {
        // 规格 §2.5：!0:2:-1（排除第 1 个、第 3 个、最后一个）
        assertEquals(IndexSelector.Excluding(listOf(0, 2, -1)), IndexSelector.parse("!0:2:-1"))
    }

    @Test
    fun `闭区间`() {
        assertEquals(IndexSelector.Slice(2, 4, null), IndexSelector.parse("[2:4]"))
    }

    @Test
    fun `带步长的区间`() {
        assertEquals(IndexSelector.Slice(1, 9, 2), IndexSelector.parse("[1:9:2]"))
    }

    @Test
    fun `省略端点的区间`() {
        assertEquals(IndexSelector.Slice(null, 3, null), IndexSelector.parse("[:3]"))
        assertEquals(IndexSelector.Slice(2, null, null), IndexSelector.parse("[2:]"))
        assertEquals(IndexSelector.All, IndexSelector.parse("[:]"))
        assertEquals(IndexSelector.All, IndexSelector.parse("[]"))
    }

    @Test
    fun `反序区间`() {
        assertEquals(IndexSelector.Slice(-1, 0, null), IndexSelector.parse("[-1:0]"))
    }

    @Test
    fun `无方括号的裸位置`() {
        assertEquals(IndexSelector.At(listOf(1)), IndexSelector.parse("1"))
        assertEquals(IndexSelector.At(listOf(-1)), IndexSelector.parse("-1"))
    }

    @Test
    fun `非索引文本返回 null`() {
        assertNull(IndexSelector.parse("odd"))
        assertNull(IndexSelector.parse("class.odd"))
        assertNull(IndexSelector.parse(""))
        assertNull(IndexSelector.parse("[a]"))
        assertNull(IndexSelector.parse("[1:2:3:4]"))
    }

    @Test
    fun `端点带空格仍能解析`() {
        assertEquals(IndexSelector.Slice(1, 2, null), IndexSelector.parse("[ 1 : 2 ]"))
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `./gradlew :lib_book_source:compileDebugUnitTestKotlin`
Expected: FAIL——`IndexSelector` 未定义。

- [x] **Step 3: 实现解析与裁剪**

```kotlin
package com.ebook.source.script

/**
 * 索引方言（规格 §4）：`[0]` `[-1]` `[1,3]` `[!1,3]` `[2:4]` `[1:9:2]` `[:3]` `[2:]` `[:]` `[-1:0]`，
 * 以及位置段里的裸写法 `0` `-1` `!0:2:-1`。
 *
 * **区间是闭区间**（规格 §4 已订判据）：文档同时说「start 为 0 时可省略、end 为 -1 时可省略」
 * 与「`[:]` 即全取」，只有闭区间能让这两句同时成立。半开会让「省略 end」变成丢掉最后一个元素。
 *
 * 解析阶段**不换算负索引**：负数是「从尾数」的语义，只有拿到列表长度才能换算，
 * 而词法层不知道长度。把换算留在 [applyTo]，词法与求值的分工才不会在越界处理上互相猜。
 */
internal sealed interface IndexSelector {

    object All : IndexSelector

    data class At(val indexes: List<Int>) : IndexSelector

    data class Excluding(val indexes: List<Int>) : IndexSelector

    /** [start] / [end] / [step] 为 null 表示该端点省略；闭区间；start > end 时反向遍历 */
    data class Slice(val start: Int?, val end: Int?, val step: Int?) : IndexSelector

    companion object {

        /** 解析一段索引文本（可带或不带方括号）；不是索引形态则返回 null，交回调用方按名称处理 */
        fun parse(text: String): IndexSelector? {
            var body = text.trim()
            if (body.startsWith("[") && body.endsWith("]")) body = body.substring(1, body.length - 1).trim()
            if (body.isEmpty()) return if (text.contains(':')) All else null
            if (body == ":") return All
            if (body.startsWith("!")) {
                // 排除式：方括号形态用逗号（[!1,3]）、位置段形态用冒号（!0:2:-1），两种都收
                val nums = body.drop(1).split(',', ':').map { it.trim() }
                if (nums.isEmpty() || nums.any { it.toIntOrNull() == null }) return null
                return Excluding(nums.map { it.toInt() })
            }
            if (body.contains(':')) {
                val parts = body.split(':')
                if (parts.size > 3) return null
                val v = parts.map { it.trim().let { t -> if (t.isEmpty()) null else t.toIntOrNull() } }
                if (parts.filter { it.trim().isNotEmpty() }.any { it.trim().toIntOrNull() == null }) return null
                return Slice(v.getOrNull(0), v.getOrNull(1), v.getOrNull(2))
            }
            val nums = body.split(',').map { it.trim() }
            if (nums.any { it.toIntOrNull() == null }) return null
            return At(nums.map { it.toInt() })
        }

        /**
         * 按规格 §4 + 本仓规定裁剪：**逐项过滤、端点按集合边界裁剪、越界的单个索引跳过、
         * 全部被过滤即返回空列表**。任何情况都不抛——越界在语料里是常态（`[2:999]`），
         * 抛出去会让一条写歪了下标的规则毁掉整本书的目录。
         */
        fun List<*>.select(sel: IndexSelector): List<*> = when (sel) {
            All -> this
            is At -> sel.indexes.mapNotNull { resolve(it, size) }.map { this[it] }
            is Excluding -> {
                val drop = sel.indexes.mapNotNull { resolve(it, size) }.toSet()
                filterIndexed { i, _ -> i !in drop }
            }
            is Slice -> slice(sel)
        }

        private fun resolve(i: Int, n: Int): Int? = (if (i < 0) n + i else i).takeIf { it in 0 until n }

        private fun List<*>.slice(sel: Slice): List<*> {
            val n = size
            if (n == 0) return emptyList()
            val a = sel.start?.let { if (it < 0) (n + it).coerceAtLeast(0) else it.coerceAtMost(n - 1) } ?: 0
            // 省略 end 按「取到末尾」解（闭区间语义），而不是 Python 的「到 -1 之前」
            val b = sel.end?.let { if (it < 0) (n + it).coerceAtLeast(0) else it.coerceAtMost(n - 1) } ?: (n - 1)
            val step = (sel.step ?: 1).let { if (it == 0) 1 else kotlin.math.abs(it) }
            // start > end 本身就是反序信号（[-1:0] 的形态），与 step 的正负无关
            val out = ArrayList<Int>()
            if (a <= b) { var i = a; while (i <= b) { out += i; i += step } }
            else { var i = a; while (i >= b) { out += i; i -= step } }
            return out.map { this[it] }
        }
    }
}
```

> 区间端点的校验口径：凡非空端点必须能转成整数，否则整段不是索引（返回 null 交回调用方按名称处理）。

- [x] **Step 4: 运行解析测试通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.IndexSelectorTest"`
Expected: PASS（12 个用例）。

- [x] **Step 5: 写裁剪语义的测试**

在 `IndexSelectorTest` 追加（同一文件，紧接类末尾）：

```kotlin
    // ---- 裁剪语义（规格 §4 的「本仓规定」：端点裁剪、越界跳过、不抛） ----

    private val five = listOf("a", "b", "c", "d", "e")

    @Test
    fun `闭区间含两端`() {
        assertEquals(
            listOf("c", "d", "e"),
            IndexSelector.run { five.select(IndexSelector.Slice(2, 4, null)) },
        )
    }

    @Test
    fun `省略 end 即取到末尾`() {
        assertEquals(
            listOf("c", "d", "e"),
            IndexSelector.run { five.select(IndexSelector.Slice(2, null, null)) },
        )
    }

    @Test
    fun `负索引从尾数`() {
        assertEquals(listOf("e"), IndexSelector.run { five.select(IndexSelector.At(listOf(-1))) })
    }

    @Test
    fun `区间端点越界按边界裁剪`() {
        assertEquals(
            listOf("c", "d", "e"),
            IndexSelector.run { five.select(IndexSelector.Slice(2, 999, null)) },
        )
    }

    @Test
    fun `单个越界索引被跳过而不抛`() {
        assertEquals(
            listOf("a"),
            IndexSelector.run { five.select(IndexSelector.At(listOf(0, 9, -9))) },
        )
    }

    @Test
    fun `start 大于 end 表达反序`() {
        assertEquals(
            five.reversed(),
            IndexSelector.run { five.select(IndexSelector.Slice(-1, 0, null)) },
        )
    }

    @Test
    fun `步长按绝对值走`() {
        assertEquals(
            listOf("a", "c", "e"),
            IndexSelector.run { five.select(IndexSelector.Slice(0, 4, 2)) },
        )
    }

    @Test
    fun `排除式去掉指定项`() {
        assertEquals(
            listOf("b", "d"),
            IndexSelector.run { five.select(IndexSelector.Excluding(listOf(0, 2, -1))) },
        )
    }

    @Test
    fun `全部越界时返回空列表`() {
        assertEquals(emptyList<String>(), IndexSelector.run { five.select(IndexSelector.At(listOf(7, 8))) })
        assertEquals(emptyList<String>(), IndexSelector.run { emptyList<String>().select(IndexSelector.All) })
    }
```

- [x] **Step 6: 运行测试通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.IndexSelectorTest"`
Expected: PASS（21 个用例）。

- [x] **Step 7: 红线 grep + 提交**

```bash
git grep -in "[l]egado" || echo CLEAN
git add lib_book_source/src/main/java/com/ebook/source/script/IndexSelector.kt lib_book_source/src/test/java/com/ebook/source/script/IndexSelectorTest.kt
git commit -m "$(cat <<'EOF'
feat(lib_book_source): 脚本书源索引方言解析与裁剪

区间取闭：文档同时说「end 为 -1 时可省略」与「[:] 即全取」，只有闭区间两句同时成立。
越界一律逐项过滤不抛——写歪的下标不该毁掉整本书的目录，而语料里 [2:999] 是常态。
EOF
)"
```

---

### Task 6: `ChainLink` 与取值器 —— 段结构

**Files:**
- Create: `lib_book_source/src/main/java/com/ebook/source/script/ChainLink.kt`
- Test: `lib_book_source/src/test/java/com/ebook/source/script/ChainLinkTest.kt`

- [x] **Step 1: 写失败的测试**

```kotlin
package com.ebook.source.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 规格 §2.5（链式段结构）与 §3.2（取值器全集）。 */
class ChainLinkTest {

    @Test
    fun `三段式类型名称位置`() {
        assertEquals(
            listOf(
                ChainLink(ChainKind.CLASS, "odd", IndexSelector.At(listOf(0))),
                ChainLink(ChainKind.TAG, "a", IndexSelector.At(listOf(0))),
                ChainLink(ChainKind.ACCESSOR, "text", null),
            ),
            ChainLink.parseChain("class.odd.0@tag.a.0@text"),
        )
    }

    @Test
    fun `文本定位类型`() {
        assertEquals(
            listOf(ChainLink(ChainKind.TEXT, "下一章", null)),
            ChainLink.parseChain("text.下一章"),
        )
    }

    @Test
    fun `children 不需要名称与位置`() {
        assertEquals(
            listOf(ChainLink(ChainKind.CHILDREN, "", IndexSelector.At(listOf(1)))),
            ChainLink.parseChain("children[1]"),
        )
    }

    @Test
    fun `裸索引段等价于 children`() {
        // 规格 §4：索引可作为段首规则，此时前面等价于 children
        assertEquals(
            listOf(ChainLink(ChainKind.CHILDREN, "", IndexSelector.At(listOf(1)))),
            ChainLink.parseChain("[1]"),
        )
        assertEquals(
            listOf(ChainLink(ChainKind.CHILDREN, "", IndexSelector.At(listOf(1)))),
            ChainLink.parseChain(".1"),
        )
    }

    @Test
    fun `href 是取值器而任意属性名走 ATTRIBUTE`() {
        // §3.2：href/src 是「属性快捷名」，与 text/html 同档，故判 ACCESSOR 而非 ATTRIBUTE
        assertEquals(
            listOf(
                ChainLink(ChainKind.TAG, "a", null),
                ChainLink(ChainKind.ACCESSOR, "href", null),
            ),
            ChainLink.parseChain("tag.a@href"),
        )
        assertEquals(
            listOf(ChainLink(ChainKind.ATTRIBUTE, "_src", null)),
            ChainLink.parseChain("_src"),
        )
    }

    @Test
    fun `取值器全集都判为 ACCESSOR`() {
        val tokens = listOf("text", "ownText", "textNodes", "html", "all", "href", "src")
        tokens.forEach {
            assertEquals("取值器 $it 判错", listOf(ChainLink(ChainKind.ACCESSOR, it, null)), ChainLink.parseChain(it))
        }
    }

    @Test
    fun `末段是 text 点内容时按 TEXT 类型而非取值器`() {
        // `text` 既是取值器又是类型关键字，区分依据是有没有后续段：text.下一章 是类型
        assertEquals(
            listOf(ChainLink(ChainKind.TEXT, "下一章", null)),
            ChainLink.parseChain("text.下一章"),
        )
    }

    @Test
    fun `属性选择器内部的 at 被方括号保护`() {
        // `[@href]` 里的 @ 是 CSS 属性选择器的一部分，被括号深度保护；
        // 整个 `a[@href]` 原样留给选择器，由求值层交给 Jsoup——词法层不拆 CSS
        assertEquals(
            listOf(
                ChainLink(ChainKind.TAG, "a[@href]", null),
                ChainLink(ChainKind.ACCESSOR, "text", null),
            ),
            ChainLink.parseChain("tag.a[@href]@text"),
        )
    }

    @Test
    fun `不在括号里的连续 at 各切一段`() {
        // 锁住已知边界：没有括号保护时 `@` 一律是段分隔，`data-x@y` 会切成两段。
        // 属性名里真含 @ 的写法（如邮箱、`@media`）在本格式无转义可用（§11-4），
        // 这是词法层的既成口径，改它必须同时改规格。
        assertEquals(
            listOf(
                ChainLink(ChainKind.TAG, "a", null),
                ChainLink(ChainKind.ATTRIBUTE, "data-x", null),
                ChainLink(ChainKind.ATTRIBUTE, "y", null),
            ),
            ChainLink.parseChain("tag.a@data-x@y"),
        )
    }

    @Test
    fun `属性名带方括号选择器时末段仍是取值器`() {
        assertEquals(
            listOf(
                ChainLink(ChainKind.TAG, "meta[@name=\"x\"]", null),
                ChainLink(ChainKind.ATTRIBUTE, "content", null),
            ),
            ChainLink.parseChain("tag.meta[@name=\"x\"]@content"),
        )
    }

    @Test
    fun `选择器与取值器的切分`() {
        assertEquals(".articleDiv p" to "textNodes", SelectorAndAccessor.split(".articleDiv p@textNodes"))
        assertEquals("$._id" to null, SelectorAndAccessor.split("$._id"))
        assertEquals("[property=og:image]" to "content", SelectorAndAccessor.split("[property=og:image]@content"))
    }

    @Test
    fun `取值器识别不区分大小写`() {
        assertEquals(AccessorKind.TEXT, AccessorKind.of("text"))
        assertEquals(AccessorKind.TEXT, AccessorKind.of("TEXT"))
        assertEquals(AccessorKind.TEXT_NODES, AccessorKind.of("textNodes"))
        assertEquals(AccessorKind.ATTRIBUTE, AccessorKind.of("content"))
    }

    @Test
    fun `规格列为未证实的取值器不当作已知取值器`() {
        // §3.2 未证实清单：@textNode（单数）与二手写法冲突，本仓不实现、按属性处理并留给报告
        assertEquals(AccessorKind.ATTRIBUTE, AccessorKind.of("textNode"))
    }

    @Test
    fun `空链返回空列表`() {
        assertEquals(emptyList<ChainLink>(), ChainLink.parseChain(""))
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `./gradlew :lib_book_source:compileDebugUnitTestKotlin`
Expected: FAIL——`ChainLink` / `SelectorAndAccessor` / `AccessorKind` 未定义。

- [x] **Step 3: 实现**

```kotlin
package com.ebook.source.script

/** 链式段的角色（规格 §2.5 第一段 + §3.2 取值器）。 */
internal enum class ChainKind { CLASS, ID, TAG, TEXT, CHILDREN, ACCESSOR, ATTRIBUTE }

/**
 * 链式规则的一段：`类型 . 名称 . 位置`，或一个取值器/属性名。
 *
 * [index] 合并了两种写法：位置段（`class.odd.0` 里的 `0`）与方括号索引
 * （`tag.div[2:4]`），因为二者语义相同（§4 明说索引也能作为段首规则出现）。
 * 分成两个字段会让求值层去猜「同时出现时谁优先」，而文档没有答案。
 */
internal data class ChainLink(
    val kind: ChainKind,
    val name: String,
    val index: IndexSelector?,
) {
    companion object {

        private val TYPES = mapOf("class" to ChainKind.CLASS, "id" to ChainKind.ID, "tag" to ChainKind.TAG, "text" to ChainKind.TEXT, "children" to ChainKind.CHILDREN)

        /**
         * 按深度 0 的 `@` 切段并逐段结构化。
         *
         * 「末段才可能是取值器」这条判据是必须的：`text.下一章` 里的 `text` 是类型关键字，
         * 而链尾的 `text` 是取值器，同一个词两种身份，位置是唯一可用的区分依据。
         */
        fun parseChain(body: String): List<ChainLink> {
            if (body.isBlank()) return emptyList()
            val d = RuleScanner.depths(body)
            val hits = RuleScanner.topLevelOf(body, d, "@")
            val segments = ArrayList<String>(hits.size + 1)
            var cursor = 0
            for (h in hits) {
                segments += body.substring(cursor, h)
                cursor = h + 1
            }
            segments += body.substring(cursor)
            return segments.mapIndexed { i, raw -> link(raw, isLast = i == segments.size - 1) }
        }

        private fun link(raw: String, isLast: Boolean): ChainLink {
            var text = raw.trim()
            var index: IndexSelector? = null
            // 尾随方括号索引先摘走（`tag.div[2:4]`，也覆盖整段就是 `[1]` 的形态）
            if (text.endsWith("]")) {
                val open = text.lastIndexOf('[')
                if (open >= 0) {
                    IndexSelector.parse(text.substring(open))?.let {
                        index = it
                        text = text.substring(0, open)
                    }
                }
            }
            if (text.isEmpty()) return ChainLink(ChainKind.CHILDREN, "", index)
            // 裸位置（`0`、`-1`、`.1`、`[1]`、`!0:2`）等价于 children 上的索引
            val bare = text.removePrefix(".")
            if (index == null && bare.isNotEmpty() && bare.first().isDigitOrSign()) {
                IndexSelector.parse(bare)?.let { return ChainLink(ChainKind.CHILDREN, "", it) }
            }
            val parts = text.split('.')
            TYPES[parts[0].lowercase()]?.let { kind ->
                // children 不需要名称与位置；其余类型第二段是名称、第三段是位置
                val name = if (kind == ChainKind.CHILDREN) "" else parts.getOrNull(1)?.trim() ?: ""
                val positional = parts.getOrNull(if (kind == ChainKind.CHILDREN) 1 else 2)?.trim()
                if (index == null && positional != null) index = IndexSelector.parse(positional)
                return ChainLink(kind, name, index)
            }
            val accessor = AccessorKind.of(text)
            return when {
                isLast && accessor != AccessorKind.ATTRIBUTE -> ChainLink(ChainKind.ACCESSOR, text, index)
                index == null && text.toIntOrNull() == null -> ChainLink(ChainKind.ATTRIBUTE, text, null)
                else -> ChainLink(ChainKind.CHILDREN, text, index)
            }
        }

        private fun Char.isDigitOrSign() = this == '-' || this == '+' || isDigit()
    }
}

/** 取值器（规格 §3.2）。未列入者一律按属性名处理，不猜。 */
internal enum class AccessorKind(val token: String) {
    TEXT("text"),
    OWN_TEXT("ownText"),
    TEXT_NODES("textNodes"),
    HTML("html"),
    ALL("all"),
    HREF("href"),
    SRC("src"),
    ATTRIBUTE(""),
    ;

    companion object {
        /** 已知取值器按不区分大小写匹配（大小写在上游文档两种写法都出现，本仓统一不敏感） */
        fun of(token: String): AccessorKind {
            val t = token.trim()
            return entries.firstOrNull { it != ATTRIBUTE && it.token.equals(t, ignoreCase = true) } ?: ATTRIBUTE
        }
    }
}

/**
 * 选择器型模式（CSS / JSONPath / XPath）的「选择器 + 尾随取值器」切分。
 *
 * 与默认链式模式的分工不同：这里只找**最后一个**深度 0 的 `@`，因为选择器内部合法地
 * 含有 `@`（XPath 的 `@text()`、属性值里的 `@`），而尾随取值器恒在最右。
 */
internal object SelectorAndAccessor {
    fun split(body: String): Pair<String, String?> {
        val d = RuleScanner.depths(body)
        val hits = RuleScanner.topLevelOf(body, d, "@")
        val last = hits.lastOrNull() ?: return body to null
        val token = body.substring(last + 1)
        // 末段不是取值器（如 `//img/@_src` 里的 `_src` 是属性）时仍按「选择器 + 属性」切
        return body.substring(0, last) to token.ifBlank { null }
    }
}
```

- [x] **Step 4: 运行测试通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.ChainLinkTest"`
Expected: PASS。若某条「已知行为」用例暴露出上面代码的真实分歧，以规格 §2.5/§3.2 为准修改**实现与测试两侧**，并在提交信息里记下改了哪条口径——不许只改测试让它变绿。

- [x] **Step 5: 红线 grep + 提交**

```bash
git grep -in "[l]egado" || echo CLEAN
git add lib_book_source/src/main/java/com/ebook/source/script/ChainLink.kt lib_book_source/src/test/java/com/ebook/source/script/ChainLinkTest.kt
git commit -m "$(cat <<'EOF'
feat(lib_book_source): 脚本书源链式段结构与取值器

text 既是类型关键字又是取值器，位置是唯一区分依据，故末段才判取值器。
选择器型模式只切最后一个顶层 @：XPath 与属性值里合法地含 @，尾随取值器恒在最右。
EOF
)"
```

---

### Task 7: `ScriptRuleSet` —— 原始 JSON 的规则装载

**Files:**
- Create: `lib_book_source/src/main/java/com/ebook/source/script/ScriptRuleSet.kt`
- Create: `lib_book_source/src/main/java/com/ebook/source/script/ScriptRuleExceptions.kt`
- Test: `lib_book_source/src/test/java/com/ebook/source/script/ScriptRuleSetTest.kt`

- [x] **Step 1: 写失败的测试**

```kotlin
package com.ebook.source.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 规格 §1 顶层字段与六类规则对象。未知键忽略（§1.1 末段），不支持项必须如实登记。 */
class ScriptRuleSetTest {

    private val communityJson = """
    {
      "bookSourceUrl": "https://abc.example.com",
      "bookSourceName": "测试脚本源",
      "bookSourceGroup": "书源群",
      "bookSourceType": 0,
      "enabled": true,
      "searchUrl": "/search/{key},POST,{\"body\":\"key={{key}}&page={{page}}\",\"charset\":\"gbk\"}",
      "exploreUrl": "男生::/nan/{{page}}\n女生::/nv",
      "ruleSearch": {
        "bookList": "class.bookbox",
        "name": "tag.a.0@text",
        "author": "class.author@text",
        "bookUrl": "tag.a.0@href",
        "checkKeyWord": "凡人"
      },
      "ruleToc": { "chapterList": "-:<li><a[^"]+"([^"]*)">([^<]*)", "chapterName": "$2", "chapterUrl": "$1", "nextTocUrl": [] },
      "ruleContent": { "content": "@css:#content@html", "nextContentUrl": "text.下一页@href" },
      "ruleBookInfo": { "init": ":<div>(.*)</div>", "tocUrl": "" },
      "unknownFutureKey": { "whatever": 1 },
      "customOrder": 7
    }
    """.trimIndent()

    @Test
    fun `装载出顶层 URL 与六类规则对象的字段表`() {
        val set = ScriptRuleSet.load(communityJson)
        assertEquals("https://abc.example.com", set.sourceUrl)
        assertEquals("测试脚本源", set.name)
        assertTrue(set.searchUrl!!.startsWith("/search/"))
        assertEquals("class.bookbox", set.rule(RuleObjectKind.SEARCH, "bookList"))
        assertEquals("@css:#content@html", set.rule(RuleObjectKind.CONTENT, "content"))
    }

    @Test
    fun `非字符串的规则字段被忽略并登记为不支持`() {
        // 语料里 nextTocUrl 常写成 []（空数组），它不是规则串，装载不得当成空规则混过校验
        val set = ScriptRuleSet.load(communityJson)
        assertTrue(
            "nextTocUrl 是数组形态，应被记为非常规字段",
            set.irregularFields.contains("ruleToc.nextTocUrl"),
        )
    }

    @Test
    fun `未知顶层键被忽略而不报错`() {
        val set = ScriptRuleSet.load(communityJson)
        assertEquals("https://abc.example.com", set.sourceUrl)
    }

    @Test
    fun `声明了登录与 webJs 与 XPath 的源登记对应不支持项`() {
        val withUnsupported = """
        {
          "bookSourceUrl": "https://x.example", "bookSourceName": "x",
          "loginUrl": "https://x.example/login",
          "ruleContent": { "webJs": "1+1", "content": "//div/@text()" }
        }
        """.trimIndent()
        val set = ScriptRuleSet.load(withUnsupported)
        assertTrue(set.unsupported.contains(ScriptUnsupported.LOGIN))
        assertTrue(set.unsupported.contains(ScriptUnsupported.WEB_JS))
    }

    @Test
    fun `规则串里的 js 与 XPath 分别登记`() {
        val set = ScriptRuleSet.load(
            """
            {"bookSourceUrl":"https://y","bookSourceName":"y",
             "ruleSearch":{"bookList":"tag.li<js>1</js>","name":"@js:result","author":"//a/@text()"}}
            """.trimIndent()
        )
        assertTrue(set.unsupported.contains(ScriptUnsupported.JS))
        assertTrue(set.unsupported.contains(ScriptUnsupported.XPATH))
    }

    @Test
    fun `没有任何规则对象时装载成功`() {
        val set = ScriptRuleSet.load("""{"bookSourceUrl":"https://z","bookSourceName":"z"}""")
        assertEquals("https://z", set.sourceUrl)
        assertTrue(set.rule(RuleObjectKind.SEARCH, "name") == null)
    }

    @Test
    fun `非法 JSON 抛类型化异常而非序列化细节`() {
        val e = runCatching { ScriptRuleSet.load("{not json") }.exceptionOrNull()
        assertTrue("实际：${e?.let { it::class.simpleName }}", e is ScriptRuleParseException)
        assertTrue(e!!.message!!.contains("脚本书源"))
    }

    @Test
    fun `数组形态的书源文件取第一条并说明整包在导入侧已拆开`() {
        // 规格 §1.1：一个书源文件是书源对象的数组；导入链路逐条处理后把单个对象交给本装载器
        val e = runCatching { ScriptRuleSet.load("[{\"bookSourceUrl\":\"https://a\"}]") }.exceptionOrNull()
        assertTrue("数组应被拒：${e?.message}", e is ScriptRuleParseException)
    }

    @Test
    fun `原始 JSON 整块保留供求值层按需重读`() {
        val set = ScriptRuleSet.load(communityJson)
        assertTrue(set.raw === communityJson || set.raw == communityJson)
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `./gradlew :lib_book_source:compileDebugUnitTestKotlin`
Expected: FAIL——`ScriptRuleSet` 等未定义。

- [x] **Step 3: 实现异常类型**

```kotlin
package com.ebook.source.script

/**
 * 脚本书源求值失败的基类。
 *
 * 为什么必须类型化（承 Plan 1 的口径）：`BookParser` 消费链上 null 恒等于「书源不存在」，
 * 而「规则读不懂」「规则用了未覆盖的语法」「JS 段待沙箱」是三种完全不同的失败，
 * 混起来会让用户被支去重导一条本来好的源。消息面向用户文案，禁止出现生态项目名。
 */
internal sealed class ScriptRuleException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** 规则装载失败：JSON 读不出来、或形态根本不是本装载器接受的书源对象 */
internal class ScriptRuleParseException(message: String, cause: Throwable? = null) : ScriptRuleException(message, cause)

/** 规则串有确定的语法错误（括号不配对、索引形态非法到无法解释）；§3.3 本仓规定：按「未取到值」参与 || 短路 */
internal class RuleSyntaxException(val rule: String) : ScriptRuleException(
    "规则语法无法解释：${rule.take(80)}"
)

/** 规则含 JS 段：Plan 2 不实现求值，Plan 3 的沙箱执行器落地后自动生效 */
internal class JsEvaluationPendingException(val rule: String) : ScriptRuleException(
    "该规则含可执行脚本段，需脚本沙箱执行器才能求值：${rule.take(80)}"
)

/** 规则用了本仓明确不支持/延后的能力（XPath、webJs、@cache: 等） */
internal class UnsupportedRuleFeatureException(val feature: String, val rule: String) : ScriptRuleException(
    "该规则使用了本项目不支持的能力（$feature）：${rule.take(80)}"
)
```

- [x] **Step 4: 实现装载器**

```kotlin
package com.ebook.source.script

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 六类规则对象（规格 §1.2~§1.7）。`ruleReview` 不在内：本仓不实现（§1.7）。 */
internal enum class RuleObjectKind(val jsonKey: String) {
    SEARCH("ruleSearch"),
    EXPLORE("ruleExplore"),
    BOOK_INFO("ruleBookInfo"),
    TOC("ruleToc"),
    CONTENT("ruleContent"),
}

/** 整源级的不支持/延后能力，供导入报告与求值前置检查共用。 */
internal enum class ScriptUnsupported { LOGIN, WEB_JS, SOURCE_REGEX, JS_LIB, COVER_DECODE, EXPLORE_SCREEN, BOOK_URL_PATTERN, DOWNLOAD_URLS, REVIEW, XPATH, JS, CACHE_PREFIX }

/**
 * 脚本书源的求值输入：原始 JSON 的**规则装载视图**（规格 §1）。
 *
 * 与 `com.ebook.api.entity.ScriptSourceRule` 的分工：后者是导入校验用的最小模型
 * （只有 6 个顶层字段），本类是求值要用的全量规则表。两者读同一份原始 JSON、职责不同，
 * 合并会让导入校验被迫携带全部规则字段，并让「未知键忽略」这条格式得以存活的口径
 * 渗进校验层（ADR-0029 决策 1：原始入库零翻译）。
 *
 * 装载只保留**字符串形态的规则字段**：非字符串（数组、对象、数字）记进 [irregularFields]
 * 而不猜其语义——语料里 `nextTocUrl: []` 与 `nextTocUrl: "..."` 并存，数组形态的展开是
 * 求值层的事（§7.1 的「URL 数组」），在词法层把它当成空规则会让「没配」与「配了多个」同形。
 */
internal data class ScriptRuleSet(
    val sourceUrl: String,
    val name: String,
    val group: String,
    val type: Int,
    val enabled: Boolean,
    val enabledExplore: Boolean,
    val searchUrl: String?,
    val exploreUrl: String?,
    val objects: Map<RuleObjectKind, Map<String, String>>,
    val irregularFields: List<String>,
    val unsupported: List<ScriptUnsupported>,
    val raw: String,
) {
    fun rule(kind: RuleObjectKind, field: String): String? = objects[kind]?.get(field)

    companion object {

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** 顶层非规则对象的键：值若是字符串也可能是 URL 选项之类，只取本仓认识的这几个 */
        private val TOP_LEVEL_URL_KEYS = listOf("searchUrl", "exploreUrl")

        fun load(rawJson: String): ScriptRuleSet {
            val root = try {
                json.parseToJsonElement(rawJson)
            } catch (e: Exception) {
                throw ScriptRuleParseException("脚本书源 JSON 无法解析：${e.message}", e)
            }
            val obj = root as? JsonObject
                ?: throw ScriptRuleParseException(
                    "脚本书源必须整块装载；数组由导入链路逐条拆开后交给本装载器"
                )
            val irregular = mutableListOf<String>()
            val unsupported = linkedSetOf<ScriptUnsupported>()
            obj["loginUrl"]?.let { if (it.stringOrNull().isNotBlank()) unsupported += ScriptUnsupported.LOGIN }
            obj["jsLib"]?.let { if (it.stringOrNull().isNotBlank()) unsupported += ScriptUnsupported.JS_LIB }
            obj["coverDecodeJs"]?.let { if (it.stringOrNull().isNotBlank()) unsupported += ScriptUnsupported.COVER_DECODE }
            obj["exploreScreen"]?.let { if (it.stringOrNull().isNotBlank()) unsupported += ScriptUnsupported.EXPLORE_SCREEN }
            obj["bookUrlPattern"]?.let { if (it.stringOrNull().isNotBlank()) unsupported += ScriptUnsupported.BOOK_URL_PATTERN }
            if (obj.containsKey("ruleReview")) unsupported += ScriptUnsupported.REVIEW
            // webJs / sourceRegex 是 ruleContent 对象里的**键名**（§1.6），不是规则串内容：
            // 只能按键名检测。用「整串里含 webJs 字样」去凑会在别的字段上误报、又漏掉真配了这两键的源。
            (obj["ruleContent"] as? JsonObject)?.let { content ->
                if (content.containsKey("webJs")) unsupported += ScriptUnsupported.WEB_JS
                if (content.containsKey("sourceRegex")) unsupported += ScriptUnsupported.SOURCE_REGEX
                if (content.containsKey("contentBatch")) irregular += "ruleContent.contentBatch"
            }

            val urls = TOP_LEVEL_URL_KEYS.associateWith { obj.stringOrNull(it) }
            val objects = RuleObjectKind.entries.associateWith { kind ->
                val node = obj[kind.jsonKey] as? JsonObject ?: return@associateWith emptyMap<String, String>()
                node.entries.associate { (field, value) ->
                    when {
                        value is JsonPrimitive && value.isString -> {
                            val rule = value.contentOrNull.orEmpty()
                            classify(rule, "${kind.jsonKey}.$field", unsupported, irregular)
                            field to rule
                        }
                        value is JsonArray -> {
                            // 数组形态：逐条分类其元素，再登记为非常规字段（求值层按 §7.1 展开）
                            value.forEach { el ->
                                val s = el.stringOrNull()
                                if (s != null) classify(s, "${kind.jsonKey}.$field", unsupported, irregular)
                            }
                            irregular += "${kind.jsonKey}.$field"
                            field to ""
                        }
                        else -> {
                            irregular += "${kind.jsonKey}.$field"
                            field to ""
                        }
                    }
                }
            }
            urls["searchUrl"]?.let { classify(it, "searchUrl", unsupported, irregular) }
            urls["exploreUrl"]?.let { classify(it, "exploreUrl", unsupported, irregular) }

            return ScriptRuleSet(
                sourceUrl = obj.stringOrNull("bookSourceUrl").orEmpty(),
                name = obj.stringOrNull("bookSourceName").orEmpty(),
                group = obj.stringOrNull("bookSourceGroup").orEmpty(),
                type = obj["bookSourceType"].intOrZero(),
                enabled = obj["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true,
                enabledExplore = obj["enabledExplore"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true,
                searchUrl = urls["searchUrl"]?.ifBlank { null },
                exploreUrl = urls["exploreUrl"]?.ifBlank { null },
                objects = objects,
                irregularFields = irregular.toList(),
                unsupported = unsupported.toList(),
                raw = rawJson,
            )
        }

        /** 逐条规则的能力判定：命中 JS/XPath/@cache: 就登记，不猜语义 */
        private fun classify(
            rule: String,
            path: String,
            unsupported: MutableSet<ScriptUnsupported>,
            irregular: MutableList<String>,
        ) {
            if (rule.isBlank()) return
            val (mode, _) = RuleMode.of(rule)
            when (mode) {
                RuleMode.JS -> unsupported += ScriptUnsupported.JS
                RuleMode.XPATH -> unsupported += ScriptUnsupported.XPATH
                RuleMode.UNSUPPORTED -> {
                    unsupported += ScriptUnsupported.CACHE_PREFIX
                    irregular += path
                }
                else -> Unit
            }
        }

        private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
        private fun JsonElement?.intOrZero(): Int = (this as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
    }
}
```

需要补 import：`kotlinx.serialization.json.JsonElement`。

- [x] **Step 5: 运行测试通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.ScriptRuleSetTest"`
Expected: PASS（9 个用例）。

- [x] **Step 6: 红线 grep + 提交**

```bash
git grep -in "[l]egado" || echo CLEAN
git add lib_book_source/src/main/java/com/ebook/source/script lib_book_source/src/test/java/com/ebook/source/script
git commit -m "$(cat <<'EOF'
feat(lib_book_source): 脚本书源规则集装载与类型化失败

原始 JSON 的规则视图与导入校验用的最小模型分开：合并会让未知键忽略的口径渗进校验层。
只保留字符串形态的规则字段，数组与对象登记为非常规字段——把 nextTocUrl 的数组当空规则
会让「没配」和「配了多个」同形。
EOF
)"
```

---

### Task 8: 文档同步与全段验证

**Files:**
- Modify: `lib_ebook_api/src/main/java/com/ebook/api/entity/ScriptSourceRule.kt`（`bookSourceType` KDoc 校正）
- Modify: `AGENTS.md`
- Modify: `docs/superpowers/specs/2026-09-08-script-source-rule-grammar.md`（登记 2a 的落地口径）
- Modify: `docs/superpowers/plans/2026-09-08-script-book-source-foundation.md`（路线图一节补 2a 已落地的指针）

- [x] **Step 1: 校正 `bookSourceType` 的 KDoc**

规格 §1.1 与 §12 末段已核实真实取值是 `0` 文本、`1` 音频、`2` 图片、`3` 文件/下载站、`4` 视频，而 `ScriptSourceRule.kt` 现在写的是「0=文本，1=图片(漫画)，2=音频」。逻辑用的是 `!= 0`，行为不受影响，但注释失实（AGENTS.md 禁止「代码已改、注释仍是旧描述」的同族问题：注释与事实不一致）。把该字段的 KDoc 改成：

```kotlin
    /**
     * 书源内容类型：`0` 文本、`1` 音频、`2` 图片（漫画）、`3` 文件/下载站、`4` 视频。
     * 非 0 一律给「非文本源」导入警示——本项目是文字阅读器，判断只看「是不是 0」，
     * 不逐类区分（音频/图片/视频的处置结果相同：明示可能无法正常阅读）。
     */
```

- [x] **Step 2: AGENTS.md 书源段补记 2a 的落地事实**

在「涉及书源改动时」那条实战建议的末尾（`JsoupSourceReader` 一句之后）追加一段，说清：脚本书源的**词法层**在 `lib_book_source` 的 `com.ebook.source.script`，事实源是规格文档；`RuleSplitter` 的 `%%`→`||`→`&&` 次序与索引闭区间都是**本仓规定**而非上游实证，改动必须同时改规格与本仓规定两处；求值层（2b）尚未落地，脚本行仍命中 `ScriptSourcePendingParser`。语气与长度对齐同段既有文字，不要写成清单。

- [x] **Step 3: 规格文档登记已钉死项**

在规格 §11 未知项清单里，把已由 2a 变成代码事实的条目就地标注「已按本仓规定钉死：见 `RuleSplitter`/`IndexSelector` 与其单测」的条目（§11-1 组合次序、§11-8 索引越界，以及 §2.3 的空段与嵌套口径），其余条目保持原样。**不新增章节、不改写既有段落**（ADR/规格是就地更新，不层层打补丁）。

- [x] **Step 4: 全量验证**

```bash
./gradlew test          # 预期：全模块 PASS（Plan 1 结束时 557 + 本段新增 ~100）
./gradlew :module_app:assembleDebug
git grep -in "[l]egado"     # 预期：零命中
```

已知既有 flake：`module_find` 的 `LibraryViewModelTest` 偶发 `Dispatchers.Main is used concurrently with setting it`（`tearDown` 与真实 IO 线程竞争，已在 Plan 1 的「执行期修正记录」登记）。命中时重跑确认，不要误判为本段回归，也不要顺手改。

- [x] **Step 5: 提交**

```bash
git add lib_ebook_api/src/main/java/com/ebook/api/entity/ScriptSourceRule.kt AGENTS.md docs/superpowers/specs/2026-09-08-script-source-rule-grammar.md docs/superpowers/plans/
git commit -m "$(cat <<'EOF'
docs: 登记脚本书源词法层落地口径并校正书源类型注释

- ScriptSourceRule 的 bookSourceType 注释取值顺序与文档及语料不符（行为用 != 0 未受影响）
- 规格 §11 标注已由代码钉死的两条未知项，AGENTS.md 记下文法层位置与本仓规定条款
EOF
)"
```

---

## 退出判据

1. `./gradlew test` 全模块通过；`./gradlew :lib_book_source:testDebugUnitTest` 包含本段 7 个测试类。
2. 规格 §2、§3、§4 的每条判定都能在代码或单测里指到落点；§11 的 13 条未知项中属于词法的 6 条已钉死或已标注为不支持。
3. `git grep -in "[l]egado"` 零命中；无新增编译警告。
4. **无需人工装机**：本段不接任何 UI、不改启动链路、不改网络行为，脚本书源的求值结果仍是 Plan 1 的桩。人工清单沿用 Plan 1 文末的 7 条（登记于 `docs/test-coverage-todo.md`），本段不新增。

## 留给 2b/2c/2d 的接口

- 2b 消费 `ParsedRule`（`RuleNode` 树 + `RegexReplacement`）、`ChainLink.parseChain`、`SelectorAndAccessor.split`、`IndexSelector.select`，输入是 Jsoup `Element`/JSON `JsonElement`，不碰任何网络。**2b 不只是「把树跑起来」，它还要自己长出三件本段没做的能力**：规则内的 `{{}}` 插值展开（§5.1，本段只保证插值不被切坏）、`@put:`/`@get:` 变量系统（§5.2，本段只判出模式）、以及把 `RuleNode.Leaf.reverse` 真正作用到取到的列表上（§2.5 的反序前缀本段只识别并剥净，见规格 §11-14 的作用域口径）。URL 字段上的 `{{page}}` 展开仍在 2c，两边别重复实现；`ScriptSourceRule`（导入校验的最小模型）与 `ScriptRuleSet`（求值用的规则装载）的分工同样不变——不要因为 2b 两边都要读就合并它们。
- 2c 消费 `ScriptRuleSet.searchUrl` / `exploreUrl`（`{{}}` 展开与 `,{...}` 选项尾段解析在 2c，本段只保证「整串不被切坏」）。
- 2d 把 `ScriptRuleSet` + 2b/2c 装成 `BookParser` 实现，替换 `ScriptSourcePendingParser`，并按 `ScriptUnsupported` 清单把「不支持项」报告接到导入 UI（Plan 1 的 `BookSourceValidator.validateScript` 是它的挂载点）。
- 本段所有类型标 `internal`：2b 与实现同模块即可用。若将来某个类型需要跨模块（参考 Plan 1 的教训：`internal` 对依赖方的测试源集不可见），放开时必须连测试一起搬同模块。

---

## 执行期修正记录（2026-09-08，Task 1-8 落地时）

- **`@@` 已补进模式表**（commit `500862a`）：规格 §2.1 第一行与 §5.1 都要求默认模式可显式写 `@@`，
  本计划初稿漏了。留着它会让下游按 `@` 切链式段时先切出两个空段。`RuleModeTest` 因此 12 例（原计划 11）。
- **正则 AllInOne 也不参与组合符切分**（依 §2.2 硬约束 1；§2.3 已在实现期补出步骤 1b）：
  本计划 Task 4 的 JS 探针只挡 `@js:`/`<js>`，未挡以 `:` 开头的 AllInOne，而 `()` 不计入括号深度，
  于是 `:x("a||b")` 这类正则里的字面量会被当成组合符把一条规则切成两支——**静默解错内容**。
  Task 4 落地时探针未含此支，由 Task 6 的提交一并补上（含单测）。
- **Task 5 示例代码三处已按落地实况修正**：① `emptyList()` 在 `List<*>` 返回位上推不出类型，写 `emptyList<Any?>()`；
  ② `[]` 走 `All` 的判定要用「原文是否带方括号」而不是 `body.contains(':')`（`[:]` 已被后面的 `body == ":"` 接住，
  原写法是死代码且把 `[]` 判成 null）；③ 类 KDoc 里 `[applyTo]` 是不存在的引用，实为 `[select]`。
- **计数订正**：Task 4 Step 6 的「既有 38 + 新增 40 = 78」写作时未计 `@@` 新增的一例；
  Task 1-5 落地后 `:lib_book_source` 实为 **100 例**（38 既有 + 8 + 12 + 7 + 14 + 21），全仓 618 例。
- **本段仍是纯词法**：脚本行求值依旧命中 `ScriptSourcePendingParser`，未接任何 UI/网络/启动链路，
  不新增人工装机项；Plan 1 文末的 7 条清单不变。
- **Task 6/7 的落地用例数**：`ChainLinkTest` 16 例（比初稿多 `排除式序号挂在名称后时名称与索引分开`
  与 `属性选择器里的点不切段` 两条——`class.x!0:2@text`、`tag.a[@href^="https://x.com/"]` 这类语料形态
  不补上就会把「选不中且零报错」的选择器交给求值层）；`ScriptRuleSetTest` 10 例（多
  `延后能力按键名登记而非按规则串内容猜`）。Task 1-7 落地后 `:lib_book_source` 为 **128 例**（38 既有 + 90 脚本包）。
- **列表反序 `-` 是本段的欠账、不是 2b 的延后项**（commit `17cf613`）：规格 §2.5 明写「在取列表的规则最前面
  加负号 `-`」并给出语料实证形态 `-:<li>...`，这条串一直躺在 Task 7 的夹具里，而 `RuleMode.of` 没有任何
  前导 `-` 分支——它被判成 `DEFAULT_CHAIN`，正则身份与反序身份一起丢，求值层会拿一条链式规则去解一个正则，
  与上面「正则 AllInOne 不参与组合符切分」是同一类静默解错（§10 能力矩阵本就把它列为 ✅）。修法：`of` 返回
  `RuleHead(mode, body, reverse)`，**先**剥前导 `-`（仅当余串以已知标志开头，`-1`/`text.-x` 里的减号属载荷）
  再走 §2.1 标志表，`RuleNode.Leaf` 带 `reverse`。`RuleModeTest` 因此 16 例、`RuleSplitterTest` 18 例，
  `:lib_book_source` **134 例**、全仓 653 例（该次全仓数含 `module_app` 两个 flavor 的各一条 `ExampleUnitTest`）。
- **规格 §11 由 13 条增至 14 条**：本段新产生的未知项「`-` 反序标志与组合符混用时的作用域」登记为 §11-14，
  本仓按「每条规则段各自」实现。上面「为什么 2a 要先切出来」与退出判据第 2 条里的「13 条」按落稿时的清单读。
