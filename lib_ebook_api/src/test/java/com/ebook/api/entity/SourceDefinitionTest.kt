package com.ebook.api.entity

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SourceDefinition] 展示信息成员的载体口径：Native 读规则自带字段、Script 读实体列合成字段。
 * 2e 起本类型是默认源的载体（默认源经 `BookSourceManager.observeDefaultSource()` 订阅获得），
 * 展示信息不再要求调用方分格式取。
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
