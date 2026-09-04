package com.ebook.me.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AppVersion] 解析与比较的纯函数单元测试。
 *
 * 锁两件事：
 * 1. **数值段优先、逐段比较**：`V1.10.0 > V1.9.0`（不当浮点比）、段数不等按 0 补齐、
 *    四段及以上不丢段（`1.2.3.9 > 1.2.4`——写死三段的实现会把 `1.2.3.4` 读成 `1.2.4`）
 * 2. **哪些串算解析失败**：判 null 与判出一个错版本，在「检查更新」里后果完全不同
 *    （null 会被调用方按「无法判定 → 检查失败」处置，错版本会直接给出错的角标结论）
 *
 * 另锁 [normalizeVersionTag]：字符串资源自带 `v` 前缀，未归一化的 tag 会渲染成 "vV1.2.0"。
 */
class AppVersionTest {

    private fun parsed(raw: String): AppVersion = AppVersion.parse(raw) ?: error("预期可解析：$raw")

    @Test
    fun `数字段按点分段解析，前缀 V 与 v 都可省`() {
        assertEquals(listOf(1, 2, 0), parsed("1.2.0").numbers)
        assertEquals(listOf(1, 2, 0), parsed("V1.2.0").numbers)
        assertEquals(listOf(1, 2, 0), parsed("v1.2.0").numbers)
        assertEquals(listOf(1, 2, 0), parsed("  V1.2.0  ").numbers)
    }

    @Test
    fun `段数可以少于一二三段，缺失段不参与解析`() {
        assertEquals(listOf(1), parsed("1").numbers)
        // 「只写主/次版本」的形态：两段各自入列，不补第三位 0（比较时按 0 补齐，见比较用例）
        assertEquals(listOf(1, 2), parsed("1.2").numbers)
    }

    @Test
    fun `尾缀只挂在最后一段数字之后`() {
        assertEquals(AppVersion(listOf(1, 2, 3), "abcd"), parsed("V1.2.3abcd"))
        // 两段带尾缀（如 V1.2beta）此前会被判解析失败，进而被误当成「已是最新版本」
        assertEquals(AppVersion(listOf(1, 2), "beta"), parsed("V1.2beta"))
    }

    @Test
    fun `不可信的串判为解析失败`() {
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("   "))
        assertNull(AppVersion.parse("abc"))
        assertNull(AppVersion.parse("1.x"))
        assertNull(AppVersion.parse("1.2."))
        assertNull(AppVersion.parse("1..2"))
    }

    @Test
    fun `次版本按数值比而非浮点`() {
        assertTrue(parsed("V1.10.0") > parsed("V1.9.0"))
        assertTrue(parsed("V1.2.10") > parsed("V1.2.9"))
        assertTrue(parsed("V2.0.0") > parsed("V1.9.9"))
    }

    @Test
    fun `段数不等时缺失段按零补齐`() {
        assertEquals(0, parsed("1.2").compareTo(parsed("1.2.0")))
        assertTrue(parsed("1.2.1") > parsed("1.2"))
    }

    @Test
    fun `四段及以上不丢段`() {
        // 写死三段的实现取「最后一段」当 patch：1.2.3.9 被读成 1.2.9，于是判成大于 1.2.4
        assertTrue(parsed("1.2.4") > parsed("1.2.3.9"))
        assertFalse(parsed("1.2.3.9") > parsed("1.2.4"))
        assertEquals(listOf(1, 2, 3, 4), parsed("1.2.3.4").numbers)
    }

    @Test
    fun `数字段全等时才比尾缀，空尾缀按字典序更小`() {
        assertTrue(parsed("V1.2.0b") > parsed("V1.2.0a"))
        // 已记录的取舍：latest 端点不含 prerelease，带尾缀 tag 视为同版本号的后一轮发布
        assertTrue(parsed("V1.2.0alpha") > parsed("V1.2.0"))
    }

    @Test
    fun `isOlderThan 只在严格落后时为真`() {
        assertTrue(parsed("V1.2.0").isOlderThan(parsed("V1.3.0")))
        assertFalse(parsed("V1.3.0").isOlderThan(parsed("V1.2.0")))
        // 相等不算落后——否则同版本重复检查会一直挂「新版」角标
        assertFalse(parsed("V1.2.0").isOlderThan(parsed("1.2.0")))
    }

    @Test
    fun `tag 归一化只去掉前置的 V 或 v`() {
        assertEquals("1.2.0", normalizeVersionTag("V1.2.0"))
        assertEquals("1.2.0", normalizeVersionTag("v1.2.0"))
        assertEquals("1.2.0", normalizeVersionTag("1.2.0"))
        assertEquals("1.1.7alpha", normalizeVersionTag("V1.1.7alpha"))
        assertEquals("", normalizeVersionTag(""))
    }
}
