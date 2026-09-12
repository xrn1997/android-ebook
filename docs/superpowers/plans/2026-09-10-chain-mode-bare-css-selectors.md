# 链模式裸 CSS 选择器修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **状态说明（2026-09-10 追加）**：本计划**已执行完毕**，产出在 HEAD `85af3409` 内
> （`ChainKind.CSS` 与 `ChainTail` 均已接线），勾选框未回写。另注：本文「执行偏差」段引用的那些提交 hash
> 是那批工作的**原始提交**，整支被压进 `85af3409` 后在任一分支上都不可达（`git branch --contains` 为空），
> 只能用 `git cat-file` 取出——照 hash 去 `git show` 会报 unknown revision。
> **实况以代码、KDoc 与 ADR 为准，不要把空勾选框当成待办。**

**Goal:** 让脚本解释器的默认（链式）模式把非上游前缀的链段当 CSS 选择器交给 Jsoup，修掉「裸 CSS 源静默搜不出书」这个影响 ~62% 真实语料的根因 bug。

**Architecture:** 两处改动。(A) `ChainLink` 段分类：非末段、无 `class./tag./id./text./children` 前缀、非纯索引的段 → 新 `ChainKind.CSS`（含 `tag.N` 位置序号拆分），`ElementBackends.apply` 对 CSS 段走既有 `selectIn`（Jsoup.select + 类型化语法错误）。(B) 末段「选择器 vs 属性名取值器」的歧义按**字段口径**消解：新增 `ChainTail`（AUTO/SELECTOR），列表字段（bookList/chapterList/explore）传 SELECTOR（末段裸词=选择器→Nodes），单值字段沿用 AUTO（末段裸词=`@任意属性名`取值器→Texts，保住 `@content`/`@value`/`@_src`）。`ScriptRuleEvaluator.evaluate` 加默认参 `tail=AUTO` 一路透传到 `evaluateChain`，组合符（`||`/`&&`/`%%`）逐支带同一口径。

**Tech Stack:** Kotlin、Jsoup、JUnit4、kotlinx-coroutines-test；规格 `docs/superpowers/specs/2026-09-08-script-source-rule-grammar.md`、ADR-0029。

**清洁室纪律：** 语法按「公开文档 + 语料实证 + Jsoup CSS 语义」定，不参照、不复制任何上游引擎代码；全仓（代码/注释/文档/fixture）不出现生态项目名。

**证据基线（2026-09-10 语料普查，642 条）：** `ruleSearch.bookList` 裸 CSS 283 条；≥1 个裸 CSS 核心选择器 399 条（62%）；纯上游前缀仅 145 条。裸段形态频度：bare-tag 4314、dot-class 2417（含 `.pagination a:contains(下頁)` 这类后代+伪类）、tag.dotindex（`a.0`）1118、hash-id 506、tag.dotclass（`div.foo`）323、tag+bracket（`a[href*=x]`）164、dot-class+bracket（`.autor2[2:1]`）30。

**触发本计划的实测：** 真语料金标准 `ScriptRealSourceGoldenTest` 的「手机看书」用例（`scripted_real/sjks`，已抓真响应冻结）搜索返回 0 条；追到 `ElementBackends.apply` 的 `ChainKind.ATTRIBUTE -> filter { hasAttr(name) }`，`.box@ul@li` 三段被判成属性名筛选 → 恒空。

---

## File Structure

- `lib_book_source/src/main/java/com/ebook/source/script/ChainLink.kt` — 段分类：新增 `ChainKind.CSS`、`ChainTail`、`splitTrailingPositional`，重写 `link()`/`parseChain()`
- `lib_book_source/src/main/java/com/ebook/source/script/ElementBackends.kt` — `apply()` 加 CSS 分支；`evaluateChain()` 加 `tail` 参
- `lib_book_source/src/main/java/com/ebook/source/script/ScriptRuleEvaluator.kt` — `evaluate()`/`evaluateNode()`/`evaluateLeaf()`/组合符透传 `tail`
- `lib_book_source/src/main/java/com/ebook/source/script/ScriptFieldExtractor.kt` — `evaluateList()` 传 `SELECTOR`（列表字段口径）
- `lib_book_source/src/test/java/com/ebook/source/script/ChainLinkTest.kt` — 更新 `tag.a@data-x@y`、补裸 CSS 段分类用例
- `lib_book_source/src/test/java/com/ebook/source/script/ElementBackendsTest.kt` — 补裸 CSS 链求值用例
- `lib_book_source/src/test/java/com/ebook/source/script/ScriptRealSourceGoldenTest.kt` — sjks 用例收尾断言（去 dump）转绿
- `docs/superpowers/specs/2026-09-08-script-source-rule-grammar.md` — §2.1/§2.5/§3.2/§4 重写
- `docs/adr/0029-script-book-source-import.md` — 落地状态追加这条架构级反转

---

## Task 1: 规格重写（链模式裸 CSS 段 + 末段口径）

规格是事实源（AGENTS.md：「与它冲突时以规格为准」），先改规格再改实现。

**Files:**
- Modify: `docs/superpowers/specs/2026-09-08-script-source-rule-grammar.md`（§2.1 line135、§2.5、§3.2、§4）

- [ ] **Step 1: §2.1 调和 line135 与 §2.5 line220**

把 line135 那一行（`@css:` | CSS 选择器 | **必须以 `@css:` 开头**……CSS 没有「裸写」形态）改为：

```
| `@css:` | CSS **模式**标志 | 整条规则是一个 CSS 选择器 + 尾随取值器时以 `@css:` 开头〔语法文档〕。注意：这是**整条规则的模式标志**，与「默认链式模式里某个 `@` 段本身是不是 CSS 选择器」是两件事——后者见 §2.5（链段可以是裸 CSS 选择器）。 |
```

- [ ] **Step 2: §2.5 增「链段可以是裸 CSS 选择器」一条**

在 §2.5「类型（第一段）」那条之后、`示例` 之前插入：

```
- **裸 CSS 段（无类型前缀）**：一个 `@` 段若不以 `class./id./tag./text./children` 开头、不是纯位置序号、不是末段取值器，则整段按 **CSS 选择器**交给 Jsoup（`Element.select`）在当前节点集内收窄——支持类 `.box`、id `#after_link`、标签 `ul`/`li`、复合 `div.foo`、属性 `a[href*=/article/]`、后代与伪类 `.pagination a:contains(下頁)`。上游的 `class./tag.` 前缀只是它的**扩展简写**，二者并存〔语料实证：642 条里 283 条 bookList 用裸 CSS；§2.5 line220 的 `head@.1@text` 里 `head` 即裸标签段〕。
- **裸 CSS 段的位置序号**：`tag.N`（如 `a.0`/`span.2`）= CSS 选 `tag` 再取第 N 个，与方括号索引 `[N]` 等价（§4）。判据：按最后一个顶层 `.` 切，尾段能解析成索引才拆（`a.0`→选 a 取 0；`div.foo` 的 `foo` 不是索引→整段是 CSS 复合选择器；`.box` 前导点不拆→CSS 类选择器）。
```

- [ ] **Step 3: §3.2 增「末段是选择器还是取值器，按字段口径定」**

在 §3.2 表格之后、`未证实的取值器` 之前插入：

```
**末段歧义的消解（本仓规定）**：一个裸词末段既可能是 CSS 标签选择器（`.box@ul@li` 的 `li`，列表字段要的是**节点**），也可能是 `@任意属性名` 取值器（`option@value` 的 `value`、`meta@content` 的 `content`，单值字段要的是**文本**）。两者无法从规则串本身区分，故按**字段口径**定：

- **列表字段**（`bookList`/`chapterList`/`ruleExplore.bookList`）：末段一律按**选择器**解，结果是节点集（对应上游的 getElements）。
- **单值字段**（`name`/`author`/`content`/`coverUrl`/`tocUrl`/`chapterUrl`/`nextTocUrl`/`nextContentUrl` 等）：末段是已知取值器（text/html/href/src/all/ownText/textNodes）按取值器；否则按 `@任意属性名` 取值器（对应上游的 getContent）。

实现上由调用方把口径（`ChainTail.SELECTOR` / `ChainTail.AUTO`）传进求值器；缺省 AUTO 即单值口径。**已知取值器关键字在两种口径下都按取值器**（作者显式写了 `@text` 就尊重它）。
```

- [ ] **Step 4: §4 补一行裸 CSS 上的索引**

在 §4 表格 `[0]` 那行之后补：

```
| `a.0` / `.autor2[2:1]` | 裸 CSS 段同样可带位置序号：`tag.N` 与 `tag[N]` 等价（§2.5）；`[start:end]` 闭区间口径不变 | 语料实证（tag.dotindex 1118 例、dot-class+bracket 30 例） |
```

- [ ] **Step 5: 提交**

```bash
git add docs/superpowers/specs/2026-09-08-script-source-rule-grammar.md
git commit -m "docs: 规格补链模式裸 CSS 段与末段按字段口径消解"
```

---

## Task 2: ChainLink 段分类（ChainKind.CSS + ChainTail + 位置序号拆分）

**Files:**
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/ChainLink.kt`
- Test: `lib_book_source/src/test/java/com/ebook/source/script/ChainLinkTest.kt`

- [ ] **Step 1: 写失败测试**

在 `ChainLinkTest` 末尾（`空链返回空列表` 之前）加：

```kotlin
    @Test
    fun `裸 CSS 段非末位判为 CSS 选择器`() {
        // 规格 §2.5：无前缀、非末段的裸词是 CSS 选择器，不是属性名筛选
        assertEquals(
            listOf(
                ChainLink(ChainKind.CSS, ".box", null),
                ChainLink(ChainKind.CSS, "ul", null),
                ChainLink(ChainKind.CSS, "li", null),
            ),
            ChainLink.parseChain(".box@ul@li", ChainTail.SELECTOR),
        )
    }

    @Test
    fun `tag 点位置序号拆成 CSS 选择器加索引`() {
        // §2.5/§4：a.0 = CSS 选 a 再取第 0 个，与 a[0] 等价
        assertEquals(
            listOf(ChainLink(ChainKind.CSS, "a", IndexSelector.At(listOf(0)))),
            ChainLink.parseChain("a.0", ChainTail.SELECTOR),
        )
        // div.foo 的 foo 不是索引 → 整段是 CSS 复合选择器
        assertEquals(
            listOf(ChainLink(ChainKind.CSS, "div.foo", null)),
            ChainLink.parseChain("div.foo", ChainTail.SELECTOR),
        )
    }

    @Test
    fun `方括号索引与类选择器并存`() {
        assertEquals(
            listOf(ChainLink(ChainKind.CSS, ".autor2", IndexSelector.At(listOf(0)))),
            ChainLink.parseChain(".autor2[0]", ChainTail.SELECTOR),
        )
    }

    @Test
    fun `规格 line220 例子 head 点 1 取 text`() {
        // §2.5 line220：head@.1@text —— 裸标签 head 是选择器、.1 是 children 上的索引、text 是取值器
        assertEquals(
            listOf(
                ChainLink(ChainKind.CSS, "head", null),
                ChainLink(ChainKind.CHILDREN, "", IndexSelector.At(listOf(1))),
                ChainLink(ChainKind.ACCESSOR, "text", null),
            ),
            ChainLink.parseChain("head@.1@text"),
        )
    }

    @Test
    fun `单值口径末段裸词仍是属性名取值器`() {
        // §3.2：AUTO（单值字段）口径下末段裸词 = @任意属性名，保住 option@value / meta@content
        assertEquals(
            listOf(ChainLink(ChainKind.CSS, "option", null), ChainLink(ChainKind.ATTRIBUTE, "value", null)),
            ChainLink.parseChain("option@value", ChainTail.AUTO),
        )
        // 同一规则在列表口径下末段是选择器
        assertEquals(
            listOf(ChainLink(ChainKind.CSS, "option", null), ChainLink(ChainKind.CSS, "value", null)),
            ChainLink.parseChain("option@value", ChainTail.SELECTOR),
        )
    }

    @Test
    fun `已知取值器在列表口径下仍按取值器`() {
        // §3.2：作者显式写 @text 就尊重它，即便在 SELECTOR 口径
        assertEquals(
            listOf(ChainLink(ChainKind.CSS, "li", null), ChainLink(ChainKind.ACCESSOR, "text", null)),
            ChainLink.parseChain("li@text", ChainTail.SELECTOR),
        )
    }
```

更新既有 `不在括号里的连续 at 各切一段`（`tag.a@data-x@y`）：中段 `data-x` 现在是 CSS 选择器：

```kotlin
    @Test
    fun `不在括号里的连续 at 各切一段`() {
        // 锁住已知边界：没有括号保护时 `@` 一律是段分隔。中段裸词 data-x 按 §2.5 是 CSS 选择器，
        // 末段裸词 y 在 AUTO 口径下是属性名取值器。属性名里真含 @ 的写法在本格式无转义可用（§11-4）。
        assertEquals(
            listOf(
                ChainLink(ChainKind.TAG, "a", null),
                ChainLink(ChainKind.CSS, "data-x", null),
                ChainLink(ChainKind.ATTRIBUTE, "y", null),
            ),
            ChainLink.parseChain("tag.a@data-x@y"),
        )
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.ChainLinkTest"`
Expected: 编译失败（`ChainKind.CSS`、`ChainTail` 未定义；`parseChain` 无第二参）。

- [ ] **Step 3: 实现 ChainKind.CSS + ChainTail**

`ChainLink.kt` 顶部枚举改为：

```kotlin
/** 链式段的角色（规格 §2.5 第一段 + §3.2 取值器）。 */
internal enum class ChainKind { CLASS, ID, TAG, TEXT, CHILDREN, ACCESSOR, ATTRIBUTE, CSS }

/**
 * 链尾的判读口径（规格 §3.2「末段歧义的消解」）。
 *
 * 一个裸词末段既可能是 CSS 标签选择器（列表字段要节点）、又可能是 `@任意属性名` 取值器
 * （单值字段要文本），无法从规则串本身区分，故由调用方按字段口径传入。
 */
internal enum class ChainTail {
    /** 单值字段口径：末段裸词按 `@任意属性名` 取值器（缺省，与历史行为一致） */
    AUTO,

    /** 列表字段口径（bookList/chapterList/explore）：末段裸词按 CSS 选择器，结果是节点集 */
    SELECTOR,
}
```

- [ ] **Step 4: 重写 parseChain 与 link**

`parseChain` 加 `tail` 参并透传：

```kotlin
        fun parseChain(body: String, tail: ChainTail = ChainTail.AUTO): List<ChainLink> {
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
            return segments.mapIndexed { i, raw -> link(raw, isLast = i == segments.size - 1, tail = tail) }
        }
```

`link` 重写（保留前缀/索引/取值器既有分支，只改「裸段」兜底）：

```kotlin
        private fun link(raw: String, isLast: Boolean, tail: ChainTail): ChainLink {
            var text = raw.trim()
            var index: IndexSelector? = null
            // 尾随方括号索引先摘走（`tag.div[2:4]`、`.autor2[0]`）。用 lastIndexOf 且接受下标 0。
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
            // 裸位置（`0`、`-1`、`.1`、`!0:2`）等价于 children 上的索引
            val bare = text.removePrefix(".")
            val bareLooksLikeIndex = bare.isNotEmpty() && (bare.first().isDigitOrSign() || bare.first() == '!')
            if (index == null && bareLooksLikeIndex) {
                IndexSelector.parse(bare)?.let { return ChainLink(ChainKind.CHILDREN, "", it) }
            }
            // 取值器判定先于类型表（理由见原注释）：末段已知取值器恒按取值器，两种口径都尊重
            if (isLast && AccessorKind.of(text) != AccessorKind.ATTRIBUTE) {
                return ChainLink(ChainKind.ACCESSOR, text, index)
            }
            val parts = topLevelParts(text)
            TYPES[parts[0].lowercase()]?.let { kind ->
                var name = if (kind == ChainKind.CHILDREN) "" else (parts.getOrNull(1)?.trim() ?: "")
                val positional = parts.getOrNull(if (kind == ChainKind.CHILDREN) 1 else 2)?.trim()
                if (index == null && positional != null) index = IndexSelector.parse(positional)
                if (index == null) {
                    val bang = name.indexOf('!')
                    if (bang >= 0) {
                        IndexSelector.parse(name.substring(bang))?.let {
                            index = it
                            name = name.substring(0, bang)
                        }
                    }
                }
                return ChainLink(kind, name, index)
            }
            // 裸段（无上游前缀）。末段在单值口径下是 `@任意属性名` 取值器（§3.2），其余一律 CSS 选择器（§2.5）。
            if (isLast && tail == ChainTail.AUTO) {
                return if (index == null && text.toIntOrNull() == null) {
                    ChainLink(ChainKind.ATTRIBUTE, text, null)
                } else {
                    ChainLink(ChainKind.CHILDREN, text, index)
                }
            }
            // CSS 选择器段：拆尾随上游位置序号（a.0 → 选 a 取 0），再拆排除式（a!0:2）
            var css = text
            if (index == null) {
                val (base, pos) = splitTrailingPositional(css)
                css = base
                index = pos
            }
            if (index == null) {
                val bang = css.indexOf('!')
                if (bang >= 0) {
                    IndexSelector.parse(css.substring(bang))?.let {
                        index = it
                        css = css.substring(0, bang)
                    }
                }
            }
            return ChainLink(ChainKind.CSS, css, index)
        }

        /**
         * 拆裸 CSS 段尾随的上游位置序号（§2.5/§4）：`a.0` → ("a", At(0))；`div.foo` 的 `foo`
         * 不是索引 → 原样 ("div.foo", null)；`.box` 前导点不拆 → (".box", null)。
         * 按**最后一个顶层 `.`** 切，括号内的点（属性值里）不参与。
         */
        private fun splitTrailingPositional(text: String): Pair<String, IndexSelector?> {
            val dots = RuleScanner.topLevelOf(text, RuleScanner.depths(text), ".")
            val lastDot = dots.lastOrNull() ?: return text to null
            val base = text.substring(0, lastDot)
            if (base.isEmpty()) return text to null
            val idx = IndexSelector.parse(text.substring(lastDot + 1)) ?: return text to null
            return base to idx
        }
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.ChainLinkTest"`
Expected: PASS（全部用例绿）。

- [ ] **Step 6: 提交**

```bash
git add lib_book_source/src/main/java/com/ebook/source/script/ChainLink.kt lib_book_source/src/test/java/com/ebook/source/script/ChainLinkTest.kt
git commit -m "feat(lib_book_source): 链式段分类支持裸 CSS 选择器与按口径消解末段"
```

---

## Task 3: ElementBackends 链后端 CSS 分支 + tail 透传

**Files:**
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/ElementBackends.kt`
- Test: `lib_book_source/src/test/java/com/ebook/source/script/ElementBackendsTest.kt`

- [ ] **Step 1: 写失败测试**

在 `ElementBackendsTest` 末尾加（复用类内既有 `html` 与 `eval`；`eval` 走 AUTO，列表口径用显式 helper）：

```kotlin
    private fun evalSelector(rule: String, input: RuleValue = RuleValue.Page(html)): RuleResult =
        ScriptRuleEvaluator(EvalContext()).evaluate(rule, input, ChainTail.SELECTOR)

    @Test
    fun `裸 CSS 链段逐级收窄并返回节点集`() {
        // 列表口径：#info 下选 a，末段是选择器 → Nodes
        val r = evalSelector("#info@a") as RuleResult.Nodes
        assertEquals(listOf("第一章", "第二章"), r.elements.map { it.text() })
    }

    @Test
    fun `裸 CSS 类与标签复合选择`() {
        assertEquals(RuleResult.Texts(listOf("书名A", "书名B")), eval("#info .name@text"))
    }

    @Test
    fun `裸 CSS 属性选择器交给 Jsoup`() {
        assertEquals(RuleResult.Texts(listOf("/x/1.html")), eval("#info@a[data-x=\"7\"]@href"))
    }

    @Test
    fun `tag 点位置序号在链里收窄`() {
        assertEquals(RuleResult.Texts(listOf("第一章")), eval("#info@a.0@text"))
    }

    @Test
    fun `单值口径末段裸词取属性、列表口径末段裸词选节点`() {
        // AUTO：a@data-x 末段 data-x 是属性名 → 取属性值
        assertEquals(RuleResult.Texts(listOf("7")), eval("#info@a@data-x"))
        // SELECTOR：同形规则末段当选择器 → 选 <data-x> 元素，无 → Miss
        assertTrue(evalSelector("#info@a@data-x") is RuleResult.Miss)
    }

    @Test
    fun `非法裸 CSS 选择器判类型化语法错误`() {
        // §3.3：CSS 语法错误抛 RuleSyntaxException（可被 || 当未取到值），不静默空、不裸抛 Jsoup 异常
        val e = runCatching { eval("#info@.foo[") }.exceptionOrNull()
        assertTrue("实际：${e?.let { it::class.simpleName }}", e is RuleSyntaxException)
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.ElementBackendsTest"`
Expected: 编译失败（`evaluate` 无第三参；CSS 段求值未实现 → 即便编译过也红）。

- [ ] **Step 3: apply 加 CSS 分支**

`ElementBackends.apply` 的 `when (link.kind)` 里，在 `ChainKind.ATTRIBUTE` 分支之前加：

```kotlin
            // 裸 CSS 选择器段（§2.5）：整段交给 Jsoup.select，复用 selectIn 的类型化语法错误包装
            ChainKind.CSS -> {
                requireSelectorName(link, body)
                from.flatMap { selectIn(it, link.name, body) }
            }
```

- [ ] **Step 4: evaluateChain 加 tail 参并透传**

```kotlin
    fun evaluateChain(
        body: String,
        input: RuleValue,
        reverse: Boolean,
        expansion: Expansion,
        tail: ChainTail = ChainTail.AUTO,
    ): RuleResult {
        val filled = expansion.fill(body)
        val links = ChainLink.parseChain(filled, tail)
        if (links.isEmpty()) return RuleResult.Miss
        // ……（其余不变）
    }
```

（仅签名与 `parseChain(filled, tail)` 一行变化，方法体其余保持原样。）

- [ ] **Step 5: 跑测试确认通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.ElementBackendsTest"`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add lib_book_source/src/main/java/com/ebook/source/script/ElementBackends.kt lib_book_source/src/test/java/com/ebook/source/script/ElementBackendsTest.kt
git commit -m "feat(lib_book_source): 链式后端对裸 CSS 段走 Jsoup 选择并按口径定末段"
```

---

## Task 4: ScriptRuleEvaluator 透传 tail + ScriptFieldExtractor 列表口径

**Files:**
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/ScriptRuleEvaluator.kt`
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/ScriptFieldExtractor.kt`
- Modify: 列表字段调用点（`ScriptBookParser` / `ScriptTocPager` / `ScriptContentPager` 中经 `evaluateList` 取 bookList/chapterList/explore 的地方——见 Step 1 审计）

- [ ] **Step 1: 审计 evaluateList 与 evaluate 的调用点**

Run: `grep -rn "evaluateList\|\.evaluate(" lib_book_source/src/main/java/com/ebook/source`
Expected: 列出所有调用点。判读：经 `ScriptFieldExtractor.evaluateList` 且用于 **bookList/chapterList/ruleExplore.bookList** 的 → 列表口径（SELECTOR）；用于 **nextTocUrl/nextContentUrl** 的翻页链求值 → 这些规则末段恒为 `@href`/`@text` 取值器或前缀选择器，SELECTOR 口径不改变其行为（已知取值器两口径都按取值器），故可与列表字段共用 SELECTOR；`fieldText`/`evaluateOnItem`/`evaluateOnJsonItem`（单值字段）→ AUTO。把审计结论记进本计划「执行期修正记录」。

- [ ] **Step 2: 写失败测试（端到端列表口径）**

在 `ScriptFieldExtractorTest`（若无则新建 `lib_book_source/src/test/java/com/ebook/source/script/ScriptFieldExtractorTest.kt`）加：

```kotlin
package com.ebook.source.script

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptFieldExtractorTest {

    private val html = """
        <div class="box"><ul>
          <li><a href="/b/1">书一</a></li>
          <li><a href="/b/2">书二</a></li>
        </ul></div>
    """.trimIndent()

    @Test
    fun `evaluateList 用列表口径让裸 CSS 末段选出节点`() = runTest {
        val extractor = ScriptFieldExtractor(
            ScriptRuleEvaluator(EvalContext()),
            sourceRoot = "https://x.com",
        )
        val result = extractor.evaluateList(".box@ul@li", RuleValue.Page(html))
        val items = extractor.listItems(result)
        assertEquals(2, items.size)
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.ScriptFieldExtractorTest"`
Expected: FAIL（`evaluateList` 当前走 AUTO，末段 `li` 判成属性名 → Miss → 0 条）。

- [ ] **Step 4: evaluate 透传 tail**

`ScriptRuleEvaluator`：

```kotlin
    fun evaluate(rule: String, input: RuleValue, tail: ChainTail = ChainTail.AUTO): RuleResult {
        val (expansion, masked) = Interpolation.expand(rule, ctx) { inner ->
            val nested = RuleSplitter.parse(inner)
            evaluateNode(nested.root, input, Expansion.EMPTY, ChainTail.AUTO)
        }
        val parsed = RuleSplitter.parse(masked)
        val raw = evaluateNode(parsed.root, input, expansion, tail)
        // ……（替换段/回填/尾段逻辑不变）
    }
```

`evaluateNode` / `evaluateLeaf` / `firstOf` / `mergeAllOf` / `interleave` 各加 `tail: ChainTail` 参并向下透传；`evaluateLeaf` 的 `RuleMode.DEFAULT_CHAIN` 分支改为：

```kotlin
            RuleMode.DEFAULT_CHAIN -> ElementBackends.evaluateChain(node.body, input, node.reverse, expansion, tail)
```

组合符里每次递归 `evaluateNode(alt, input, expansion, tail)` 带同一 `tail`。`putVariables` 内嵌套求值与 `evaluateOnItem`/`evaluateOnJsonItem` 用 `ChainTail.AUTO`（单值口径）。`evaluate` 既有两参调用因默认参不受影响。

- [ ] **Step 5: ScriptFieldExtractor.evaluateList 传 SELECTOR**

```kotlin
    /** 规则求值转发：列表字段（bookList/chapterList/explore）用列表口径，末段裸词按选择器（§3.2） */
    fun evaluateList(rule: String, input: RuleValue): RuleResult =
        evaluator.evaluate(rule, input, ChainTail.SELECTOR)
```

`fieldText` 保持 `evaluator.evaluate(fieldRule, ...)`（AUTO）。若 Step 1 审计发现 nextTocUrl/nextContentUrl 走的是另一个入口而非 `evaluateList`，按审计结论给那个入口传 AUTO；若同走 `evaluateList`，因其末段恒为取值器/前缀，SELECTOR 不改变行为（在金标准 wuji 的 `text.下一页@href` 上回归验证）。

- [ ] **Step 6: 跑测试确认通过 + 全模块回归**

Run: `./gradlew :lib_book_source:testDebugUnitTest`
Expected: PASS，0 失败（含既有 469 例与 wuji 金标准——wuji 用 `class./tag.` 前缀，不受 tail 改动影响）。

- [ ] **Step 7: 提交**

```bash
git add lib_book_source/src/main/java/com/ebook/source/script/ScriptRuleEvaluator.kt lib_book_source/src/main/java/com/ebook/source/script/ScriptFieldExtractor.kt lib_book_source/src/test/java/com/ebook/source/script/ScriptFieldExtractorTest.kt
git commit -m "feat(lib_book_source): 列表字段以 SELECTOR 口径求值打通裸 CSS bookList"
```

---

## Task 5: sjks 真语料金标准转绿并收尾断言

**Files:**
- Modify: `lib_book_source/src/test/java/com/ebook/source/script/ScriptRealSourceGoldenTest.kt`（手机看书用例）

- [ ] **Step 1: 跑 sjks 用例 dump 真实产物**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.ScriptRealSourceGoldenTest"`
读 `lib_book_source/build/test-results/testDebugUnitTest/TEST-*.xml` 的 `SJKS_REQ/SJKS_FIRST/SJKS_INFO/SJKS_CHAP/SJKS_TEXT` 系统输出，核对：搜索出 6 条、首条「洪荒：从拜师西王母开始」、POST body 关键词编码、详情名剥半角括号「(1-345)」、author 含「作者：」前缀、kind「修真」、首章「第1节」、正文 `.content@html` 真实产物。

- [ ] **Step 2: 用核对后的真实值替换 dump 为断言**

把 `println(...)` 四行删掉，按 Step 1 读到的真实产物写 `assertEquals`/`assertTrue`（至少锁：搜索条数与首条 name/noteUrl/tag；POST body 的关键词编码形态；详情 name 剥括号；author/kind；首章 durChapterName/contentRef；正文含真实开篇、`.content@html` 语义）。**断言值以 Step 1 实测为准，不照抄本计划的占位描述**——若实测与「预期」不符，先判断是解析器 bug 还是规则真实局限，按 §3.3 口径处置并记进执行期修正记录。

- [ ] **Step 3: 跑测试确认通过**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.ScriptRealSourceGoldenTest"`
Expected: PASS（wuji + sjks 两条金标准全绿）。

- [ ] **Step 4: 提交**

```bash
git add lib_book_source/src/test/java/com/ebook/source/script/ScriptRealSourceGoldenTest.kt lib_book_source/src/test/resources/scripted_real/sjks
git commit -m "test(lib_book_source): 手机看书真语料金标准锁裸 CSS POST 搜索全链"
```

---

## Task 6: ADR-0029 沉淀 + 复制金标准到 vikbook/book15

**Files:**
- Modify: `docs/adr/0029-script-book-source-import.md`（落地状态追加裸 CSS 反转一条）
- Modify: `lib_book_source/src/test/java/com/ebook/source/script/ScriptRealSourceGoldenTest.kt`（加 vikbook、book15 两条）
- Create: `lib_book_source/src/test/resources/scripted_real/vikbook/`、`.../book15/`（curl 抓真响应冻结）

- [ ] **Step 1: ADR-0029 落地状态追加**

在「落地状态」段追加一条 `**2026-09-10（链模式裸 CSS 修复）**`：记「真语料金标准在手机看书源上暴露默认链式模式只认上游前缀、裸 CSS 段被判属性名静默返空，影响 ~62% 语料（283 条 bookList）；修复为链段按 Jsoup CSS 选择器求值、末段选择器/属性名歧义按字段口径（ChainTail）消解；规格 §2.1/§2.5/§3.2/§4 同步，反转了 2a 当时『CSS 只有 @css: 模式、无裸写』的取舍」。注明决策 8 的金标准 fixtures 由此从『合成顶位』进到『真站点真响应』。

- [ ] **Step 2: 抓 vikbook 真响应并写金标准**

vikbook（阅读书屋，无 JS、全裸 CSS：`#page@div[itemscope]` / `a.0@text` / `text.在线阅读@href` / `#content@.page` / `a@href` / `.calibre@html`）。用与 sjks 相同的 curl + iconv 流程抓搜索/详情/目录/正文真页冻进 `scripted_real/vikbook/`，写金标准用例（先 dump 核对再落断言，方法同 Task 5）。

- [ ] **Step 3: 抓 book15 阅读链真响应并写金标准**

book15（网阅小说，重 JS 源但**阅读链声明式**、exploreUrl 才是 JS）：只抓搜索/详情/目录/正文（`.list-item-panel` / `h3 a@text` / `.d-chapter-list dd a` / `@href` / `.chapter-content@html##…` / `#after_link@href`），JS 发现面**不入离线金标准**（归 Task 8 真机临时验证）。冻进 `scripted_real/book15/`，写金标准用例。

- [ ] **Step 4: 跑全部金标准 + 提交**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.ScriptRealSourceGoldenTest"`
Expected: PASS（wuji/sjks/vikbook/book15 四条）。

```bash
git add docs/adr/0029-script-book-source-import.md lib_book_source/src/test
git commit -m "test(lib_book_source): 补阅读书屋与网阅小说真语料金标准并沉淀 ADR"
```

---

## Task 7: 恶意脚本测试集（沙箱验收）

**Files:**
- Test: `lib_book_source/src/test/java/com/ebook/source/sandbox/MaliciousScriptTest.kt`（JVM 协议/限值层）
- Modify: `docs/test-coverage-todo.md`（真机侧恶意脚本验收项登记）

- [ ] **Step 1: 写四类攻击样本的 JVM 侧用例**

死循环 / 内存炸弹（`new Array(1e9)` 或字符串膨胀）/ 深递归（无终止自调用）/ 代理滥用（脚本内狂发外呼）四类。JVM 侧锁**协议与判据**：经现有沙箱假件/限值入口断言「死循环→中断器归类、堆超限→内存超限类、深递归→栈耗尽类、白名单外外呼→拒绝」，与既有 `:js` 限值用例同档（不重复，补「成集攻击样本」这一形态）。真内核+真跨进程的退化路径归真机 `connectedDebugAndroidTest`（已在 test-coverage-todo 人工清单）。

- [ ] **Step 2: 跑测试 + 提交**

Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.sandbox.MaliciousScriptTest"`
Expected: PASS。

```bash
git add lib_book_source/src/test/java/com/ebook/source/sandbox/MaliciousScriptTest.kt docs/test-coverage-todo.md
git commit -m "test(lib_book_source): 恶意脚本测试集锁沙箱四类攻击的限值判据"
```

---

## Task 8: JS 重源临时真机验证（验完即删，不进仓）

**Files:** 临时 instrumented test（验证后删除，不提交）

- [ ] **Step 1: 写临时 androidTest 跑一条活着的 JS 重源**

设备 `15f25610` 已连。挑一条**当前存活**的 JS 重源（如网阅小说的 JS 发现面、或丁丁/阿巴），写临时 `connectedDebugAndroidTest`：导入→搜索→读一章，经真 `:js` 沙箱跑通。Run: `./gradlew :lib_book_source:connectedDebugAndroidTest --tests "...TempJsHeavySourceTest"`。

- [ ] **Step 2: 确认通过后删除临时用例**

验证「沙箱+解析链对 JS 重源没问题」即达成目的。`rm` 临时 test 文件，`git status` 确认工作树不含它。**不提交**——JS 重源真站会过期，永久 fixture 无意义（与用户约定）。把「JS 重源端到端」记进 `docs/test-coverage-todo.md` 人工装机清单（真机+活站，人工验）。

---

## Task 9: 全量回归 + 债登记收尾

**Files:**
- Modify: `lib_book_source/src/test/java/com/ebook/source/script/ScriptFixtureSources.kt`（KDoc：真金标准已就位，合成源退居编排层补充）
- Modify: `lib_book_source/src/test/java/com/ebook/source/script/ScriptSourceEndToEndTest.kt`（KDoc：语料债已部分偿还）
- Modify: `docs/test-coverage-todo.md`（语料债条目更新）

- [ ] **Step 1: 更新合成源/ E2E 的「顶位」KDoc**

把「语料文件已不在原路径、合成源是顶位、真实源语义债一直挂着」改为「真站点真响应金标准已就位（`ScriptRealSourceGoldenTest` + `scripted_real/`，4 条声明式源），合成源退为编排层补充；JS 重源的端到端归真机人工清单」。

- [ ] **Step 2: 全量验证**

Run: `./gradlew test :module_app:assembleDebug`
Expected: BUILD SUCCESSFUL，0 失败 0 跳过。

- [ ] **Step 3: 提交**

```bash
git add lib_book_source/src/test docs/test-coverage-todo.md
git commit -m "docs: 真语料金标准就位后更新合成源顶位说明与语料债登记"
```

---

## Task 10: leading-`@` 空段语义（bug 2，执行期新增）

**触发**：book15 金标准（Task 6）被卡——`ruleToc.chapterUrl = "@href"` / `chapterName = "@text"` 的**整条规则取值器形态**解析不出值。实测 `evaluate("@href")` → Miss，`evaluate("href")` → Texts。语料里 ~19 处（chapterUrl/chapterName/bookUrl/name，约 15 条源）。**预存在缺陷，非本次回归。**

**根因**：`ChainLink.link` 对**空文本段**一律返回 `ChainKind.CHILDREN`。`@href` 切成 `["", "href"]`，空首段 → children，取值器作用在 `<a>` 的子元素（无）→ Miss。但格式语义里 leading `@` 是「取值器作用于**当前节点**」，空段应是**恒等**（当前节点），不是 children。

**关键约束**：`link` 开头的 `if (text.isEmpty())` 分支同时被两种输入到达——(a) 真正空段（leading `@` 切出的 `""`，index==null）；(b) **仅方括号索引**段（`[1]` 剥括号后 text 变空、index!=null）。二者必须区分：仅 (b) 是 children+索引，(a) 是恒等。

**Files:**
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/ChainLink.kt`
- Modify: `lib_book_source/src/main/java/com/ebook/source/script/ElementBackends.kt`
- Test: `lib_book_source/src/test/java/com/ebook/source/script/ChainLinkTest.kt`、`ElementBackendsTest.kt`
- Modify: `docs/superpowers/specs/2026-09-08-script-source-rule-grammar.md`（§2.5 增「空段=恒等」一条）

- [ ] **Step 1: 写失败测试**

`ChainLinkTest` 末尾加：

```kotlin
    @Test
    fun `leading at 空段是恒等而非 children`() {
        // 格式语义：`@href` = 对当前节点取 href。空首段是恒等，不是 children（children 会作用到子元素→Miss）
        assertEquals(
            listOf(ChainLink(ChainKind.IDENTITY, "", null), ChainLink(ChainKind.ACCESSOR, "href", null)),
            ChainLink.parseChain("@href"),
        )
        // 仅方括号索引段仍按 children 上的索引（与 `[1]`/`children[1]` 同解），不能被恒等改写
        assertEquals(
            listOf(ChainLink(ChainKind.CHILDREN, "", IndexSelector.At(listOf(1)))),
            ChainLink.parseChain("[1]"),
        )
    }
```

`ElementBackendsTest` 末尾加（现有 `html` 里 `<a href="/x/1.html" data-x="7">第一章</a>`）：

```kotlin
    @Test
    fun `整条规则取值器作用于当前节点`() {
        // `@href` / `@text` 这类整条即取值器的规则：作用在传入的当前节点（节点集）上，不是它的子元素
        val a = (eval("id.info@tag.a.0") as RuleResult.Nodes).elements.single()
        assertEquals(RuleResult.Texts(listOf("/x/1.html")), eval("@href", RuleValue.Nodes(listOf(a))))
        assertEquals(RuleResult.Texts(listOf("第一章")), eval("@text", RuleValue.Nodes(listOf(a))))
    }
```

- [ ] **Step 2: 跑测试确认失败** — Run: `./gradlew :lib_book_source:testDebugUnitTest --tests "com.ebook.source.script.ChainLinkTest"` → 编译失败（`ChainKind.IDENTITY` 未定义）。

- [ ] **Step 3: 实现**

`ChainKind` 加 `IDENTITY`：`internal enum class ChainKind { CLASS, ID, TAG, TEXT, CHILDREN, ACCESSOR, ATTRIBUTE, CSS, IDENTITY }`

`ChainLink.link` 的空文本分支改为按 index 区分：

```kotlin
            // 空文本段有两种来源：① 仅方括号索引（`[1]` 剥括号后变空、index!=null）→ children 上的索引；
            // ② 真正的空段（leading `@` 切出的 `""`、index==null）→ 恒等（当前节点），
            //    格式语义里 `@href` 是对当前节点取 href，不是对子元素取。
            if (text.isEmpty()) {
                return if (index != null) ChainLink(ChainKind.CHILDREN, "", index)
                else ChainLink(ChainKind.IDENTITY, "", null)
            }
```

`ElementBackends.apply` 加恒等分支（`from` 原样，不选不动）：

```kotlin
            // 空段=恒等：当前节点集原样传给下一步（§2.5「leading @ 作用于当前节点」）
            ChainKind.IDENTITY -> from
```

（`IDENTITY` 段不会带索引；`apply` 末尾的 `link.index` 裁剪对它天然是 null。）

- [ ] **Step 4: 规格 §2.5 增一条**（在「裸 CSS 段」两条之后）：

```
- **空段（恒等）**：`@` 切出的空串段表示**恒等**——节点集原样传下一步。整条规则以取值器开头（`@href`/`@text`，语料实证形态）时，取值器作用于**当前节点**而不是它的子元素；这与 §4「索引可作为段首规则（此时前面等价于 children）」不冲突——后者指以**索引**（`[1]`/`.1`）开头，段本身不空。
```

- [ ] **Step 5: 跑测试** — Run: `./gradlew :lib_book_source:testDebugUnitTest` → 全绿（现 484 + 新增）。

- [ ] **Step 6: 提交**

```bash
git add lib_book_source/src/main/java/com/ebook/source/script/ChainLink.kt lib_book_source/src/main/java/com/ebook/source/script/ElementBackends.kt lib_book_source/src/test/java/com/ebook/source/script/ChainLinkTest.kt lib_book_source/src/test/java/com/ebook/source/script/ElementBackendsTest.kt docs/superpowers/specs/2026-09-08-script-source-rule-grammar.md
git commit -m "fix(lib_book_source): 链式空段按恒等解 整条规则取值器作用于当前节点"
```

**收尾**：本 Task 完成后回到 Task 6 Step 3，重新抓 book15 真响应（之前删了未用 fixture）并补金标准用例。

## 执行期修正记录

（执行时逐条追加：计划写的与代码实况不一致、口径因实况而改。不删原计划文字。）

- **Task 3 与 Task 4 合并执行**：计划拆成两 Task，实则编译互锁——Task 2 的 `ChainKind.CSS` 让
  `ElementBackends.apply` 的穷尽 `when` 缺分支，`lib_book_source` 主源码不编译；且 Task 3 新增的
  测试要调 `evaluate(rule, input, tail)`，而该签名是 Task 4 才引入的。故合并成一次提交
  （`e1db1a8`）落地「后端 CSS 分支 + tail 透传 + `evaluateList`」。
- **计划 T4 的前提有误，bookList 未走 `evaluateList`**：`ScriptBookParser.parseBookList` 原先直调
  `evaluator.evaluate(...)`，不经 `ScriptFieldExtractor.evaluateList`——只改 `evaluateList` 修不了 bug、
  sjks 金标准也不转绿。执行时把 `parseBookList` 改接 `evaluateListField`（搜索与发现共用此方法）。
- **`evaluateList` 拆成两个方法（`69d253d`）**：该转发同时被 content（`ScriptContentPager`）与翻页链
  （`ScriptPageChain`）使用，而按 §3.2 这些是**单值字段**，应走 AUTO 而非 SELECTOR。拆为
  `evaluateListField`（SELECTOR，仅 bookList/chapterList/explore）与 `evaluateValue`（AUTO，content
  与 next-url），使只有真正的列表字段拿 SELECTOR。
- **新增 Task 10（bug 2：leading-`@` 空段 = 恒等）**：book15 金标准被「整条 `@取值器` 形态
  （`chapterUrl: "@href"`）」卡住——首个 `@` 前空段被当 `children` 解 → 零报错未取到值（语料 19 处、
  约 15 源）。经用户拍板就地修（`19ad788`）：空段按恒等解，仅方括号索引段（`[1]`，index!=null）仍按
  children+索引。修完 book15 目录 781 章全出（`5391c28`）。
- **T7 结论：恶意脚本集已被既有用例全覆盖**，新写文件即重复。故未建 `MaliciousScriptTest.kt`，只登记
  设备侧验证项（`59d0517`）。四类攻击的覆盖图见该提交说明。
- **T8 走 `JsRuntimeBridge`（进程内真内核）而非 `:js` 独立进程**：用网阅小说的真 `exploreUrl` JS 载荷
  在真机跑通，临时用例验完即删；跨进程路径另有 `SandboxConnectionTest` 覆盖。登记于 `4b60793`。
- **测试基线为 485（非计划写的 484）**：无用例被删或跳过，仅计数笔误。

## Self-Review 结论

- **覆盖**：根因（裸 CSS 段→CSS 选择器）落 Task 2/3；末段歧义（列表 vs 单值）落 Task 2（ChainTail）/Task 4（透传+evaluateList）；规格调和（§2.1/§2.5/§3.2/§4）落 Task 1；ADR 沉淀落 Task 6；决策 8 金标准（4 条声明式真响应）落 Task 5/6；恶意脚本集落 Task 7；JS 重源临时真机验证落 Task 8；债登记落 Task 9。
- **回归面**：唯一改动的既有用例是 `ChainLinkTest` 的 `tag.a@data-x@y`（中段 ATTRIBUTE→CSS）；`ElementBackendsTest` 的 ATTRIBUTE 用例都是末段（`tag.a@data-x`）走 AUTO 不变；`evaluate` 加默认参 `tail=AUTO`，所有既有两参调用零改动；wuji 金标准与合成源全用 `class./tag.` 前缀，不受 tail/CSS 改动影响。Task 4 Step 6 与 Task 9 Step 2 两道全量回归兜底。
- **占位符**：Task 5 Step 2、Task 6 Step 2/3 的断言值明确要求「以实测 dump 为准、不照抄占位」——这是金标准方法学的刻意设计（先跑真产物、独立核对、再冻结），不是计划缺漏。
- **类型一致性**：`ChainKind.CSS`（Task 2 定义，Task 3 消费）；`ChainTail.AUTO/SELECTOR`（Task 2 定义，Task 3/4 消费）；`evaluate(rule, input, tail)`（Task 4 定义，ScriptFieldExtractor 消费）；`splitTrailingPositional`（Task 2 内部）。
- **清洁室**：语法依据是公开文档（§2.5 line220 例子）+ 语料实证（283/1118/2417 等频度）+ Jsoup CSS 语义，未参照任何上游引擎源码；fixture 入仓前核命名红线词（全仓零出现，见 ADR-0029 命名红线）为零（sjks/wuji 已核 false）。
