package com.ebook.api.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SourceFormat] 与 `book_source.format` 列值的双向对应关系。
 *
 * 与 [SourceFormatDetectorTest] 分开成两个测试类：那边钉的是「导入时按 JSON 顶层键判别格式」，
 * 本类钉的是「已落库的列值如何还原成枚举」——前者看键集、后者看字符串，两件事的失效形态不同。
 *
 * 本类是「静默错路由」的防线。列值口径由 `lib_ebook_db` 落定（实体默认值 `"native"`、
 * `MIGRATION_5_6` 的 `DEFAULT 'native'` 均小写，见 ADR-0029 决策 1），读取侧比对同一个 `raw`。
 * 任一侧改了大小写都不会报错、也不会崩溃，只会让脚本书源被当成原生格式按原生规则解析
 * （内容错乱比崩溃更难查），所以既钉逐个字面量、也钉「一律小写」这条形态不变式。
 */
class SourceFormatTest {

    @Test
    fun `列值 script 还原为脚本书源格式`() {
        assertEquals(SourceFormat.SCRIPT, SourceFormat.fromRaw("script"))
    }

    @Test
    fun `列值 native 还原为原生规则书源格式`() {
        assertEquals(SourceFormat.NATIVE, SourceFormat.fromRaw("native"))
    }

    @Test
    fun `未知列值回落 native——存量行与脏数据都走原生既有校验`() {
        assertEquals(SourceFormat.NATIVE, SourceFormat.fromRaw("garbage"))
    }

    @Test
    fun `每个枚举的 raw 都能原样还原为自身`() {
        // 写入侧（落库时的 format 字段）与读取侧（fromRaw）共用 raw，这条往返断言保证两侧不会再分叉
        SourceFormat.entries.forEach { assertEquals(it, SourceFormat.fromRaw(it.raw)) }
    }

    @Test
    fun `列值一律小写`() {
        // 列值与 MIGRATION_5_6 的 DEFAULT 'native' 和实体默认值同字，
        // 改成大小写混合就会静默错路由：fromRaw 比不中，脚本书源被读成原生格式。
        assertTrue(
            "format 列值必须保持小写，否则与 lib_ebook_db 落定的 'native' / 'script' 不同字",
            SourceFormat.entries.all { it.raw == it.raw.lowercase() },
        )
    }
}
