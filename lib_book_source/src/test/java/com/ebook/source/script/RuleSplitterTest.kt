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
        // 步骤 3（URL 选项尾段剥出）落地后：尾段整体从树上剥出、独立成 optionTail——
        // 值里的 `&&` 既不参与组合切分，也不再留在叶体里；本用例从 2a 的「不切」
        // 演进为同时锁「剥出」这一步（剥错成垃圾段的症状是静默解错内容）
        val p = RuleSplitter.parse("/search/,{\"body\":\"a&&b\"}")
        assertEquals(RuleNode.Leaf(RuleMode.DEFAULT_CHAIN, "/search/"), p.root)
        assertEquals(",{\"body\":\"a&&b\"}", p.optionTail)
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
    fun `正则 AllInOne 内的组合符字面量不切`() {
        // §2.2 硬约束 1：组合符「不包括 js 和正则」。`()` 不计入括号深度，
        // 所以这道豁免只能靠模式判定，不能靠扫描器——少了它这条正则会被切成两支。
        assertEquals(
            RuleNode.Leaf(RuleMode.REGEX_ALL_IN_ONE, "x(\"a||b\")"),
            parse(":x(\"a||b\")"),
        )
    }

    @Test
    fun `正则 AllInOne 与百分号同串时仍整条算一个正则段`() {
        assertEquals(
            RuleNode.Leaf(RuleMode.REGEX_ALL_IN_ONE, "<li>(.*?)</li>%%<dd>(.*?)</dd>"),
            parse(":<li>(.*?)</li>%%<dd>(.*?)</dd>"),
        )
    }

    @Test
    fun `正则 AllInOne 内的双井号不算替换尾段`() {
        // §2.3 的「嵌套」条明说「正则段内的 # 不参与 ## 判定」，而步骤 1b（以 : 开头整条即正则段）
        // 排在步骤 2（剥 ## 尾段）之前。剥错了不会报错，只会让正则少一段、
        // 并把条目×捕获组的二维结果拿去跑一次无意义的文本替换（§2.4 的 AllInOne 本就不在 ## 的适用场景里）
        val p = RuleSplitter.parse(":<li>([^<]+)</li>##<dd>")
        assertEquals(RuleNode.Leaf(RuleMode.REGEX_ALL_IN_ONE, """<li>([^<]+)</li>##<dd>"""), p.root)
        assertNull(p.replacement)
    }

    @Test
    fun `js 段内的双井号不算替换尾段`() {
        // 同上，依据是 §2.3 步骤 1（@js: / <js> 先于剥尾段）
        val p = RuleSplitter.parse("@js:result+'##'+x")
        assertEquals(RuleNode.Leaf(RuleMode.JS, "result+'##'+x"), p.root)
        assertNull(p.replacement)
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

    @Test
    fun `目录反序形态整条是一个带反序标志的正则叶`() {
        // 规格 §2.5 的语料实证形态（亦即 ScriptRuleSetTest 夹具里的 ruleToc.chapterList）：
        // `-` 是列表反序标志、`:` 是 AllInOne 标志，两个身份都得留在树上。少了 `-` 这一维，
        // 求值层就会拿一条链式规则去解一个正则——静默解错内容。
        assertEquals(
            RuleNode.Leaf(RuleMode.REGEX_ALL_IN_ONE, """<li><a[^"]+"([^"]*)">([^<]*)""", reverse = true),
            parse("""-:<li><a[^"]+"([^"]*)">([^<]*)"""),
        )
    }

    @Test
    fun `反序标志按支各判而不是辖整条组合表达式`() {
        // 本仓口径：反序是**每条规则段自己的**标志（§2.5 与 Leaf 的 KDoc）。
        // 「`-` 与组合符混用时到底谁被反序」规格没有答案，记在 §11 的未知项里，2b 不得自行推广。
        assertEquals(
            RuleNode.FirstOf(
                listOf(
                    RuleNode.Leaf(RuleMode.DEFAULT_CHAIN, "tag.a@text", reverse = true),
                    RuleNode.Leaf(RuleMode.DEFAULT_CHAIN, "tag.b@text", reverse = false),
                )
            ),
            parse("-@@tag.a@text||tag.b@text"),
        )
    }

    @Test
    fun `URL 选项尾段从取值段剥出`() {
        val parsed = RuleSplitter.parse("""tag.a@href,{"charset":"gbk"}""")
        assertEquals(""",{"charset":"gbk"}""", parsed.optionTail)
        assertEquals("tag.a@href", (parsed.root as RuleNode.Leaf).body)
    }

    @Test
    fun `引号内的逗号与花括号不构成尾段`() {
        val parsed = RuleSplitter.parse("""tag.a@text&&x,"a},{b"""")
        // 逗号不在顶层（引号内），整条按原样切分
        assertNull(parsed.optionTail)
    }

    @Test
    fun `单花括号 JSONPath 不误剥选项尾段`() {
        val parsed = RuleSplitter.parse("{$.a}")
        assertNull(parsed.optionTail)
    }

    @Test
    fun `AllInOne 整条不剥选项尾段`() {
        // §6.1：选项不挂在选择器/正则语法后面——正确写法是 ##$##{...} 惯用法（替换文本带尾段）
        val parsed = RuleSplitter.parse(""":<li>([^<]+),{"a":1}""")
        assertNull(parsed.optionTail)
    }

    @Test
    fun `替换段与选项尾段并存时各归各`() {
        val parsed = RuleSplitter.parse("""tag.a@text,{"charset":"gbk"}##全文""")
        assertEquals(""",{"charset":"gbk"}""", parsed.optionTail)
        assertEquals("全文", parsed.replacement?.pattern)
    }

    @Test
    fun `替换文本里的选项尾段不当作取值尾段`() {
        // 各归各的反向：## 之后的 ,{...} 是替换文本（##$##,{...} 惯用法，替换文本带尾段），
        // 不能扫进取值段尾段——否则惯用法在替换层写进去、回附层又剥出来，尾段凭空走形
        val parsed = RuleSplitter.parse("""tag.a@text##$##,{"charset":"gbk"}""")
        assertNull(parsed.optionTail)
        assertEquals(""",{"charset":"gbk"}""", parsed.replacement?.replacement)
    }
}
