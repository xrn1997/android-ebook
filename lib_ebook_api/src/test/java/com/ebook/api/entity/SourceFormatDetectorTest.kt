package com.ebook.api.entity

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceFormatDetectorTest {

    @Test
    fun `含 bookSourceUrl 键的 JSON 判为脚本书源格式`() {
        val keys = setOf("bookSourceName", "bookSourceUrl", "searchUrl", "ruleSearch")
        assertEquals(SourceFormat.SCRIPT, SourceFormat.detect(keys))
    }

    @Test
    fun `原生格式键集判为 native`() {
        val keys = setOf("name", "url", "searchUrl", "ruleSearch", "ruleToc")
        assertEquals(SourceFormat.NATIVE, SourceFormat.detect(keys))
    }

    @Test
    fun `空键集判为 native——判别只认脚本书源的特征键`() {
        assertEquals(SourceFormat.NATIVE, SourceFormat.detect(emptySet()))
    }
}
