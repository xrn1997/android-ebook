package com.ebook.me.view

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [parseDocSections]（res/raw 协议文本解析器）的纯函数单元测试。
 */
class DocContentTest {

    @Test
    fun `解析出标题与正文成对章节`() {
        val raw = """
            # 一、服务内容
            第一段内容。

            # 二、账号与安全
            第二段内容。
        """.trimIndent()

        val sections = parseDocSections(raw)

        assertEquals(2, sections.size)
        assertEquals(DocSection("一、服务内容", "第一段内容。"), sections[0])
        assertEquals(DocSection("二、账号与安全", "第二段内容。"), sections[1])
    }

    @Test
    fun `多行正文按换行拼接为一段`() {
        val raw = """
            # 标题
            第一行
            第二行
        """.trimIndent()

        val sections = parseDocSections(raw)

        assertEquals("第一行\n第二行", sections.single().body)
    }

    @Test
    fun `正文中的空行被忽略`() {
        val raw = """
            # 标题

            第一行


            第二行
        """.trimIndent()

        assertEquals("第一行\n第二行", parseDocSections(raw).single().body)
    }

    @Test
    fun `空文本或纯空白解析为空列表`() {
        assertEquals(emptyList<DocSection>(), parseDocSections(""))
        assertEquals(emptyList<DocSection>(), parseDocSections("   \n  "))
    }
}
