package com.ebook.source.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规格 §2.1 标志表：先匹配者胜；§2.5 的前导 `-`（列表反序）在同一处判定里剥出。
 *
 * 两种 JS 形态先于表内其他标志判定——规格 §9 要求 `<js>` 出现在任何一步都按 JS 处置，
 * 详见 [RuleMode] 的 KDoc。
 *
 * 每条用例都整体比较 [RuleHead] 的三个字段（mode / body / reverse）：只比前两个会让
 * 「反序身份」这一维失去锁形，而 §2.5 的语料形态正是靠它才没被降级成链式规则。
 */
class RuleModeTest {

    @Test
    fun `无标志即默认链式模式`() {
        assertEquals(
            RuleHead(RuleMode.DEFAULT_CHAIN, "tag.a.0@text", reverse = false),
            RuleMode.of("tag.a.0@text"),
        )
    }

    @Test
    fun `显式默认标志 @@ 被剥掉`() {
        // §2.1/§5.1：@@ 是默认模式的显式写法，剥掉后载荷与省略写法同形
        assertEquals(
            RuleHead(RuleMode.DEFAULT_CHAIN, "tag.a@href", reverse = false),
            RuleMode.of("@@tag.a@href"),
        )
    }

    @Test
    fun `css 前缀剥离后余下选择器`() {
        assertEquals(
            RuleHead(RuleMode.CSS, ".articleDiv p@textNodes", reverse = false),
            RuleMode.of("@css:.articleDiv p@textNodes"),
        )
    }

    @Test
    fun `双斜杠开头是 XPath 且保留原文`() {
        assertEquals(
            RuleHead(RuleMode.XPATH, "//li[3]/a/@text()", reverse = false),
            RuleMode.of("//li[3]/a/@text()"),
        )
    }

    @Test
    fun `xpath 前缀不区分大小写`() {
        assertEquals(
            RuleHead(RuleMode.XPATH, "//a/@href", reverse = false),
            RuleMode.of("@XPath://a/@href"),
        )
        assertEquals(RuleHead(RuleMode.XPATH, "//a/@href", reverse = false), RuleMode.of("@xpath://a/@href"))
    }

    @Test
    fun `点美元号开头是 JSONPath 并保留原文`() {
        assertEquals(
            RuleHead(RuleMode.JSON_PATH, "$.data[0].bookName", reverse = false),
            RuleMode.of("$.data[0].bookName"),
        )
        assertEquals(RuleHead(RuleMode.JSON_PATH, "$._id", reverse = false), RuleMode.of("@json:$._id"))
    }

    @Test
    fun `单花括号遗留形态剥掉花括号后按 JSONPath 处理`() {
        assertEquals(RuleHead(RuleMode.JSON_PATH, "$._id", reverse = false), RuleMode.of("{$._id}"))
    }

    @Test
    fun `冒号开头是正则 AllInOne 并剥掉前导冒号`() {
        assertEquals(
            RuleHead(RuleMode.REGEX_ALL_IN_ONE, "href=\"([^\"]+)\"[^>]*>([^<]*)", reverse = false),
            RuleMode.of(":href=\"([^\"]+)\"[^>]*>([^<]*)"),
        )
    }

    @Test
    fun `js 前缀与内联 js 都判为 JS 模式`() {
        assertEquals(
            RuleHead(RuleMode.JS, "result.replace(/x/,'')", reverse = false),
            RuleMode.of("@js:result.replace(/x/,'')"),
        )
        assertEquals(
            RuleHead(RuleMode.JS, "tag.li<js>1</js>//a", reverse = false),
            RuleMode.of("tag.li<js>1</js>//a"),
        )
    }

    @Test
    fun `标志键一律不区分大小写`() {
        // 规格 §2.1「标志键按不区分大小写匹配」。语料实证：大写形态有 `@CSS:`（64 处）、
        // `@JSon:`、`@XPath:`——后两个早已 ignoreCase，唯独 @css:/@js:/@put:/@get:/@cache:
        // 大小写敏感，症状是 `@CSS:table.luf_hit_list tr` 整条判成「规则语法无法解释」。
        // 一处口径分两半迟早会长出第三种行为，故整表统一。
        assertEquals(
            RuleHead(RuleMode.CSS, ".articleDiv p@textNodes", reverse = false),
            RuleMode.of("@CSS:.articleDiv p@textNodes"),
        )
        assertEquals(RuleMode.JS, RuleMode.of("@JS:result").mode)
        assertEquals(RuleMode.VARIABLE_PUT, RuleMode.of("@PUT:{bid:\"//a\"}").mode)
        assertEquals(RuleMode.VARIABLE_GET, RuleMode.of("@GET:bid").mode)
        assertEquals(RuleMode.UNSUPPORTED, RuleMode.of("@CACHE:100").mode)
        assertEquals(RuleMode.JSON_PATH, RuleMode.of("@Json:$._id").mode)
        // 载荷里的原样内容不受大小写判定影响：只剥标志头，不改写余串
        assertEquals(".articleDiv p@textNodes", RuleMode.of("@CSS:.articleDiv p@textNodes").body)
    }

    @Test
    fun `大写标志前的减号同样算反序`() {
        // hasKnownFlag 与 flagOf 必须同一口径：一边认大写、一边不认的话，
        // `-@CSS:.a@text` 会连减号一起当载荷交给链式后端，静默丢掉反序
        assertTrue(RuleMode.of("-@CSS:.a@text").reverse)
        assertEquals(RuleMode.CSS, RuleMode.of("-@CSS:.a@text").mode)
        assertEquals(".a@text", RuleMode.of("-@CSS:.a@text").body)
    }

    @Test
    fun `put 与 get 前缀各自成模式`() {
        assertEquals(
            RuleHead(RuleMode.VARIABLE_PUT, "{bid:\"//a/@href\"}", reverse = false),
            RuleMode.of("@put:{bid:\"//a/@href\"}"),
        )
        assertEquals(RuleHead(RuleMode.VARIABLE_GET, "bid", reverse = false), RuleMode.of("@get:bid"))
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
        assertEquals(RuleHead(RuleMode.DEFAULT_CHAIN, "", reverse = false), RuleMode.of(""))
    }

    // ---- 规格 §2.5「列表反序」：前导 `-` ----

    @Test
    fun `前导减号标记列表反序`() {
        // §2.5「在取列表的规则最前面加负号 `-`」，§10 能力矩阵把该项列为 ✅ 实现。
        // 减号必须剥净、且不能吞掉后面的 `:`（AllInOne 标志）——否则这条正则会被降级成链式规则，
        // 求值层拿链式规则去解正则，静默解错内容。
        assertEquals(
            RuleHead(RuleMode.REGEX_ALL_IN_ONE, "href=\"([^\"]+)\"", reverse = true),
            RuleMode.of("-:href=\"([^\"]+)\""),
        )
    }

    @Test
    fun `已知标志前的减号一律按反序处理`() {
        // 反序不专属正则：@@（显式默认）、@css:、@json:、裸 `//`、@js: 与内联 <js> 都收
        assertTrue(RuleMode.of("-@@tag.a@href").reverse)
        assertTrue(RuleMode.of("-@css:.a@text").reverse)
        assertTrue(RuleMode.of("-@json:$._id").reverse)
        assertTrue(RuleMode.of("-@XPath://a/@href").reverse)
        assertTrue(RuleMode.of("-//a/@text()").reverse)
        assertTrue(RuleMode.of("-@js:result").reverse)
        assertTrue(RuleMode.of("-tag.li<js>1</js>").reverse)
        // 剥完之后模式判定照旧，载荷里不留减号
        assertEquals(RuleMode.CSS, RuleMode.of("-@css:.a@text").mode)
        assertEquals(".a@text", RuleMode.of("-@css:.a@text").body)
    }

    @Test
    fun `减号只作用于已知标志`() {
        // `-1` 是裸负索引（§4，交 ChainLink/IndexSelector 处理）、`text.-x` 的减号在段中间，
        // 两处都不许被反序分支吃掉：误剥一个字符的后果是载荷变小且零报错。
        assertEquals(RuleHead(RuleMode.DEFAULT_CHAIN, "-1", reverse = false), RuleMode.of("-1"))
        assertEquals(RuleHead(RuleMode.DEFAULT_CHAIN, "text.-x", reverse = false), RuleMode.of("text.-x"))
        assertFalse("减号后面没有标志就不是反序", RuleMode.of("-tag.a@text").reverse)
    }

    @Test
    fun `ScriptRuleSetTest 夹具里的目录反序形态被正确识别`() {
        // 这条就是规格 §2.5 记的语料实证串（同 `ScriptRuleSetTest.communityJson` 的 ruleToc.chapterList）。
        // 它是本缺陷的回归锁：一旦反序前缀再次无人识别，这里第一个红。
        val corpus = """-:<li><a[^"]+"([^"]*)">([^<]*)"""
        val head = RuleMode.of(corpus)
        assertEquals(RuleMode.REGEX_ALL_IN_ONE, head.mode)
        assertTrue("§2.5 的 `-` 前导必须留成反序标志", head.reverse)
        assertEquals("""<li><a[^"]+"([^"]*)">([^<]*)""", head.body)
    }
}
