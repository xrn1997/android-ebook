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
