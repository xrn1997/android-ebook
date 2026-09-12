package com.ebook.source.sandbox

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 桥接层的真机用例：这一层是本计划唯一「JVM 上跑不了」的部分，所以它锁住的全是**只能在设备上暴露**的坑——
 * JNI 边界的编码方向、内核中断器与栈/堆限值的真实行为、`__host_call` 的往返通路、context 重置的隔离性。
 *
 * 协议、限值、白名单表、纯计算实现都不在这里测（Task 3/4 已用 JVM 单测锁住，那里能测得更好、更快）。
 */
@RunWith(AndroidJUnit4::class)
class QuickJsBridgeTest {

    private fun bridge() = JsRuntimeBridge()

    private fun seg(source: String, vararg bindings: Pair<String, String?>) =
        JsInvocation(JsMode.SEGMENT, source, mapOf(*bindings))

    private fun deadlineAfter(ms: Long) = SystemClock.elapsedRealtime() + ms

    /**
     * 原生库缺席时跳过，但把原因带进跳过信息。
     *
     * 本机 ABI 未随 androidTest 打包是合法的跳过来由，所以不能改成硬失败；但直接
     * `assumeTrue(QuickJsNative.loaded)` 会让「.so 缺失」与「原生层真跑过并通过」在报告里
     * 长得一模一样（整层静默全绿）。带上加载失败原因，至少一眼能分清是没打包还是真跑过了。
     */
    private fun assumeNative() =
        assumeTrue("ebook_js 未加载：${QuickJsNative.loadError ?: "原因未知"}", QuickJsNative.loaded)

    @Test
    fun 字符串字面量能原样回来() {
        assumeNative()
        val outcome = bridge().evaluate(seg("result = '第一章 风起了'"), deadlineAfter(5_000))
        assertEquals(JsStatus.OK, outcome.status)
        assertEquals("第一章 风起了", outcome.text)
    }

    @Test
    fun 增补平面字符进出都要原样回来() {
        // 锁住 JNI 字符串的双向编码：入向走 getBytes("UTF-8")、出向走 cesu8 + NewStringUTF。
        // 任一方向走错，𠀋 与 emoji 都会变成 ? 或替换字符，而这是「正文偶尔乱码」级别、
        // 只在真机上出现的症状，JVM 单测永远抓不到
        assumeNative()
        val text = "生僻字 𠀋 与 emoji 😀 混排"
        val outcome = bridge().evaluate(seg("result = '$text'"), deadlineAfter(5_000))
        assertEquals(JsStatus.OK, outcome.status)
        assertEquals(text, outcome.text)

        val bound = bridge().evaluate(seg("result = result", "result" to text), deadlineAfter(5_000))
        assertEquals(text, bound.text)
    }

    @Test
    fun 垫片里的纯计算能力走通一次进程内回调() {
        assumeNative()
        val outcome = bridge().evaluate(seg("result = java.md5('abc')"), deadlineAfter(5_000))
        assertEquals(JsStatus.OK, outcome.status)
        assertEquals("900150983cd24fb0d6963f7d28e17f72", outcome.text)
    }

    // 用例名不带空格：D8 在 DEX 040 之前不允许 SimpleName 里有空格。
    // 带空格的中文名在 JVM 单测里没事，走到 androidTest 就在 dexBuilder 上炸
    // （`Space characters in SimpleName ... are not allowed prior to DEX version 040`）
    @Test
    fun 忘给result赋值时拿到的是没有值而不是词undefined() {
        assumeNative()
        val outcome = bridge().evaluate(seg("var ignored = 1 + 1"), deadlineAfter(5_000))
        assertEquals(JsStatus.OK, outcome.status)
        assertNull(outcome.data)
        assertNull(outcome.text)
    }

    @Test
    fun 死循环在挂钟期限内被打断成超时且下一个任务仍然可用() {
        assumeNative()
        val started = SystemClock.elapsedRealtime()
        val bridge = bridge()
        val outcome = bridge.evaluate(seg("while (true) {}"), deadlineAfter(400))
        assertEquals(JsStatus.TIMEOUT, outcome.status)
        // 中断发生在解释器轮询点：400 毫秒的期限不该跑成 5 秒；下界锁的是「没等到期就返回」，
        // 那是期限算错（比如两边时钟口径不同）的症状
        val elapsed = SystemClock.elapsedRealtime() - started
        assertTrue("实际耗时 ${elapsed}ms", elapsed in 300..3_000)
        // 同一个实例续跑：超时后 evaluate 内部会重置 context，下一条规则拿到的必须是新值。
        // 换成新建 bridge 就测不到这件事——那是一个干净的 runtime，本来就一定能跑
        val next = bridge.evaluate(seg("result = 'ok'"), deadlineAfter(5_000))
        assertEquals(JsStatus.OK, next.status)
        assertEquals("ok", next.text)
    }

    @Test
    fun 深递归以栈耗尽结束而不是撞掉进程() {
        assumeNative()
        val bridge = bridge()
        val outcome = bridge.evaluate(seg("function f(n) { return f(n + 1) + 1 } result = f(1)"), deadlineAfter(5_000))
        assertEquals("内核回的错因：${outcome.error}", JsStatus.STACK, outcome.status)
        // 光判 status 锁不住「没撞进程」：栈预算大于本线程剩余栈时，症状不是这条用例失败，
        // 而是整个 instrumentation 进程消失、后面的用例一起没了（设备首跑就是这样）。
        // 与上面那条死循环用例同一个口径——再跑一次真的，进程还在才叫还在。
        val next = bridge.evaluate(seg("result = 'ok'"), deadlineAfter(5_000))
        assertEquals("栈耗尽之后同一个桥接层必须还能跑", JsStatus.OK, next.status)
    }

    @Test
    fun 堆超限按内存失败而不是把进程撞没() {
        assumeNative()
        val bridge = bridge()
        val outcome = bridge.evaluate(
            seg("var s = ''; while (true) { s += new Array(1024).join('x'); if (s.length > 1e12) break } result = s"),
            deadlineAfter(5_000),
        )
        assertEquals("内核回的错因：${outcome.error}", JsStatus.MEMORY, outcome.status)
        // 堆到限那一刻内核连错误对象都建不出来，这一条判的就是「那种说不出话的失败有没有被认出来」。
        // 认不出来的形态是 RUNTIME（设备首跑实测），它同样会让下一条规则拿到一个满血的堆，
        // 所以复位是否发生也要用第二次执行来验。
        val next = bridge.evaluate(seg("result = 'ok'"), deadlineAfter(5_000))
        assertEquals("堆耗尽之后同一个桥接层必须还能跑", JsStatus.OK, next.status)
    }

    @Test
    fun 语法错误归语法而不是运行时() {
        assumeNative()
        val outcome = bridge().evaluate(seg("result = "), deadlineAfter(5_000))
        assertEquals(JsStatus.SYNTAX, outcome.status)
    }

    @Test
    fun 绕过垫片直呼白名单外的能力会被就地拒绝() {
        assumeNative()
        // __host_call 是脚本可见的全局属性，任何 js 源都能直呼它——所以白名单必须在 Kotlin 再判一次
        val outcome = bridge().evaluate(seg("result = JSON.stringify(__host_call('cache', '[]'))"), deadlineAfter(5_000))
        assertEquals(JsStatus.OK, outcome.status)
        assertTrue(outcome.text!!.contains("\"ok\":false"))
        assertTrue(outcome.text!!.contains("cache"))
    }

    @Test
    fun 明确拒绝的能力归不支持而不是运行时错误() {
        assumeNative()
        val outcome = bridge().evaluate(seg("java.cookieManager('a')"), deadlineAfter(5_000))
        assertEquals(JsStatus.UNSUPPORTED_API, outcome.status)
        assertTrue(outcome.error!!.contains("cookieManager"))
    }

    @Test
    fun 返回Promise的脚本按不支持处置而不是折叠成空值() {
        assumeNative()
        val outcome = bridge().evaluate(seg("result = Promise.resolve('x')"), deadlineAfter(5_000))
        assertEquals(JsStatus.UNSUPPORTED_API, outcome.status)
    }

    @Test
    fun 任务边界的重置清掉上一个任务留下的全局量() {
        assumeNative()
        val bridge = bridge()
        val first = bridge.evaluate(seg("globalThis.leftover = 1"), deadlineAfter(5_000))
        assertEquals(JsStatus.OK, first.status)
        // 成功结束不触发自动重置，所以这里显式走一次任务边界——Task 8 的执行器在每次
        // execute 的开头做的就是这个动作
        bridge.reset()
        val second = bridge.evaluate(seg("result = typeof leftover"), deadlineAfter(5_000))
        assertEquals(JsStatus.OK, second.status)
        assertEquals("undefined", second.text)
        // 同一个实例还能跑：reset 换的是 context，runtime 与它的四项限值都留着
        assertTrue(bridge.isReady)
    }
}
