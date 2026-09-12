package com.ebook.source.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
