package com.ebook.source.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 「桥接层不可用」必须是一个有名字的结果，而不是一次崩溃。
 *
 * 这条只能在 JVM 单测里锁：开发机没跑 `buildDesktopJsLib` 时，单元测试的虚拟机里没有
 * `libebook_js.so`，`QuickJsNative.loaded` 因此货真价实地是 false——不是伪造出来的替身。
 * 真机上 `.so` 随包存在，这一支永远走不到。
 */
class JsRuntimeBridgeTest {

    @Test
    fun `桥未装载时回不可用而不是抛出 UnsatisfiedLinkError`() {
        // 桌面库上了 java.library.path 之后 loaded 可能是 true——那个世界由 DesktopJsHostTest
        // 覆盖，本用例只负责「缺席」分支，缺席时才跑。
        assumeTrue("libebook_js 已加载，缺席分支无从验起", !QuickJsNative.loaded)
        assertFalse(QuickJsNative.loaded)
        val outcome = JsRuntimeBridge().evaluate(
            JsInvocation(JsMode.SEGMENT, "result = 1", emptyMap()),
            deadlineMonoMs = 1_000L,
        )
        assertEquals(JsStatus.UNAVAILABLE, outcome.status)
        // 错误文案要能指到根因（缺 ABI / .so 与 Kotlin 不同步），只写「不可用」等于把
        // 「用户被支去重导一条本来好的源」的下一步省给了读日志的人
        assertTrue(outcome.error!!.contains("libebook_js.so"))
    }
}
