package com.ebook.source.sandbox

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 客户端的四种「不体面」路径都在这张表里：断连重放、拒绝重放、重入自锁、主线程阻塞。
 *
 * 真通道在 JVM 上不可用，因此全部用例用 [FakeChannel] 顶替——被测的是策略不是传输，
 * 这正是把 [JsChannel] 定成零 Android 依赖的理由。
 */
class JsSandboxClientTest {

    /** 记录每一帧；行为由构造它的 lambda 脚本化（可以回调 handler、可以抛死、可以回指定帧） */
    private class FakeChannel(
        private val behaviour: (String, (String) -> String) -> String,
    ) : JsChannel {
        val frames = mutableListOf<String>()
        var closed = false
        override val isOpen: Boolean get() = !closed
        override fun execute(requestFrame: String, hostCallback: (String) -> String): String {
            frames += requestFrame
            return behaviour(requestFrame, hostCallback)
        }

        override fun close() {
            closed = true
        }
    }

    /** 造客户端：第 n 次连接取 [behaviours] 的第 n 个行为，用尽后复用最后一个（重连用例不必铺满） */
    private fun clientWith(
        vararg behaviours: (String, (String) -> String) -> String,
        limits: JsLimits = JsLimits(),
        handler: HostHandler = HostHandler { _, _ -> HostReply(ok = true, data = null, error = null) },
        isMainThread: () -> Boolean = { false },
    ): Pair<JsSandboxClient, MutableList<FakeChannel>> {
        val opened = mutableListOf<FakeChannel>()
        val client = JsSandboxClient(
            openChannel = {
                val behaviour = behaviours[opened.size.coerceAtMost(behaviours.lastIndex)]
                FakeChannel(behaviour).also { opened += it }
            },
            limits = limits,
            clock = { 5_000L },
            hostHandler = handler,
            isMainThread = isMainThread,
        )
        return client to opened
    }

    private fun inv() = JsInvocation(JsMode.SEGMENT, "result", mapOf("result" to "正文"))

    private fun okFrame(text: String) = JsProtocol.encodeOutcome(JsOutcome(JsStatus.OK, stringJson(text)))

    private fun stringJson(s: String): JsonElement =
        Json.parseToJsonElement(Json.encodeToString(String.serializer(), s))

    @Test
    fun `成功执行：请求帧带得出模式与截止日期，回帧解成 OK 与文本`() {
        val (client, opened) = clientWith({ _, _ -> okFrame("第三章") })
        val outcome = client.execute(inv())
        assertEquals(JsStatus.OK, outcome.status)
        assertEquals("第三章", outcome.text)
        val sent = JsProtocol.decodeRequest(opened.single().frames.single())
        assertEquals(JsMode.SEGMENT, sent.invocation.mode)
        // 截止日期由主进程算好随帧带出：执行器不猜「什么时候该停」，两侧共用一个机器级单调钟
        assertEquals(5_000L + JsLimits().wallClockMs, sent.deadlineMonoMs)
    }

    @Test
    fun `通道复用：连续两次成功执行只建一条通道`() {
        val (client, opened) = clientWith({ _, _ -> okFrame("同一页") })
        repeat(2) { assertEquals("同一页", client.execute(inv()).text) }
        // 活着的通道必须复用。每次都重连等于每次付一次 binder 建连 + 执行器侧 runtime 冷启动，
        // 读一章要跑几十段脚本时这就是几十次白给的往返；而 isOpen 只看本地状态，不会漏判成「要重连」。
        assertEquals(1, opened.size)
        assertEquals(2, opened.single().frames.size)
    }

    @Test
    fun `并发执行时同一时刻只有一帧在飞`() {
        // 断言的是不变量（峰值并发恒为 1），不是任何时序：正确实现下这条永远不会假失败，
        // 所以不需要 sleep 或闩来「猜」另一个线程跑到哪儿了。
        val inFlightNow = AtomicInteger()
        val peak = AtomicInteger()
        val (client, opened) = clientWith({ _, _ ->
            peak.accumulateAndGet(inFlightNow.incrementAndGet()) { a, b -> maxOf(a, b) }
            val frame = okFrame("x")
            inFlightNow.decrementAndGet()
            frame
        })
        val threads = (1..4).map { Thread { repeat(20) { client.execute(inv()) } } }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        assertEquals("多源并行解析时沙箱是串行点（ADR-0028 偏差 6），一帧在飞的前提才有意义", 1, peak.get())
        assertEquals("排队不能把请求弄丢：4 线程 × 20 次必须一落地全投出去", 80, opened.sumOf { it.frames.size })
    }

    @Test
    fun `请求帧超上限：不发往沙箱，直接判 TOO_LARGE`() {
        val (client, opened) = clientWith({ _, _ -> error("不该发出") }, limits = JsLimits(maxRequestBytes = 64))
        val outcome = client.execute(JsInvocation(JsMode.SEGMENT, "字".repeat(200), emptyMap()))
        assertEquals(JsStatus.TOO_LARGE, outcome.status)
        assertTrue("超限的帧根本不该占用一次跨进程调用", opened.isEmpty())
    }

    @Test
    fun `响应帧超上限：在解析前就判 TOO_LARGE，不把超限原文交给调用方`() {
        val huge = """{"status":"OK","data":"${"x".repeat(500)}"}"""
        val (client, _) = clientWith({ _, _ -> huge }, limits = JsLimits(maxOutcomeBytes = 120))
        assertEquals(JsStatus.TOO_LARGE, client.execute(inv()).status)
    }

    @Test
    fun `通道死亡且本次未回调过：重连一次并重放同一帧`() {
        var first = true
        val (client, opened) = clientWith({ _, _ ->
            if (first) {
                first = false
                throw ChannelDeadException("DeadObjectException")
            }
            okFrame("重放成功")
        })
        val outcome = client.execute(inv())
        assertEquals(JsStatus.OK, outcome.status)
        assertEquals("重放成功", outcome.text)
        assertEquals(2, opened.size)
        assertTrue("旧通道必须被关闭，否则泄漏一条 Binder 连接", opened[0].closed)
        assertEquals(opened[0].frames, opened[1].frames)
    }

    @Test
    fun `重连后仍死亡：报 UNAVAILABLE 且不再试第三次`() {
        val (client, opened) = clientWith({ _, _ -> throw ChannelDeadException("一直死") })
        val outcome = client.execute(inv())
        assertEquals(JsStatus.UNAVAILABLE, outcome.status)
        assertTrue(outcome.error!!.contains("不可用"))
        assertEquals("只重连一次：第三次连接多半是环境问题，重试只会把解析拖长", 2, opened.size)
    }

    @Test
    fun `本次已转发过 host 回调时断连不重放`() {
        var relayed = 0
        val (client, opened) = clientWith({ _, relay ->
            relay(JsProtocol.encodeHostCall("ajax", """{"url":"https://a"}"""))
            relayed++
            throw ChannelDeadException("回调之后断连")
        })
        assertEquals(JsStatus.UNAVAILABLE, client.execute(inv()).status)
        assertEquals(1, relayed)
        assertEquals("已经替脚本发过一次网络请求，重放就是同一页请求两次", 1, opened.size)
    }

    @Test
    fun `执行器回的失败状态原样透传，既不折叠成 RUNTIME 也不折叠成 OK`() {
        for (status in listOf(JsStatus.TIMEOUT, JsStatus.MEMORY, JsStatus.STACK, JsStatus.UNSUPPORTED_API, JsStatus.SYNTAX)) {
            val (client, _) = clientWith({ _, _ -> JsProtocol.encodeOutcome(JsOutcome(status, error = "细节")) })
            val outcome = client.execute(inv())
            assertEquals(status, outcome.status)
            assertEquals("细节", outcome.error)
        }
    }

    @Test
    fun `host 调用帧解出 api 与 args 交给 handler，其回复编成帧带回通道`() {
        var relayedBack = ""
        val seen = mutableListOf<Pair<String, String>>()
        val (client, _) = clientWith(
            { _, relay ->
                relayedBack = relay(JsProtocol.encodeHostCall("ajax", """{"url":"https://a"}"""))
                okFrame("done")
            },
            handler = HostHandler { api, args ->
                seen += api to args
                HostReply(ok = true, data = stringJson("<html>正文</html>"), error = null)
            },
        )
        assertEquals(JsStatus.OK, client.execute(inv()).status)
        assertEquals("ajax", seen.single().first)
        assertTrue(seen.single().second.contains("https://a"))
        val reply = JsProtocol.decodeHostReply(relayedBack)
        assertTrue(reply.ok)
        assertEquals("<html>正文</html>", reply.data?.jsonPrimitive?.content)
    }

    @Test
    fun `handler 自己抛出时回 ok=false，绝不让异常穿进执行器`() {
        var relayedBack = ""
        val (client, _) = clientWith(
            { _, relay ->
                relayedBack = relay(JsProtocol.encodeHostCall("ajax", "{}"))
                okFrame("done")
            },
            handler = HostHandler { _, _ -> throw IllegalStateException("代理内部炸了") },
        )
        assertEquals("脚本自身成功与否由它自己决定，主进程代理坏了不算脚本失败", JsStatus.OK, client.execute(inv()).status)
        val reply = JsProtocol.decodeHostReply(relayedBack)
        assertFalse(reply.ok)
        assertTrue(reply.error!!.contains("代理内部炸了"))
    }

    @Test
    fun `host 回调里再发起 execute 立刻失败，不去等一把永远还不来的锁`() {
        val opened = mutableListOf<FakeChannel>()
        var nested: JsOutcome? = null
        lateinit var client: JsSandboxClient
        client = JsSandboxClient(
            openChannel = {
                FakeChannel { _, relay ->
                    relay(JsProtocol.encodeHostCall("evaluateRule", "{}"))
                    okFrame("外层完成")
                }.also { opened += it }
            },
            limits = JsLimits(),
            clock = { 5_000L },
            hostHandler = HostHandler { _, _ ->
                nested = client.execute(inv())
                HostReply(ok = false, data = null, error = "嵌套脚本不支持")
            },
        )
        assertEquals(JsStatus.OK, client.execute(inv()).status)
        assertEquals(JsStatus.UNAVAILABLE, nested?.status)
        assertTrue(nested!!.error!!.contains("嵌套"))
        assertEquals("嵌套请求不该再去开一条通道", 1, opened.size)
    }

    @Test
    fun `重放预算按本次调用算，上一次的回调不污染这一次`() {
        var relayThenDie = true
        val (client, opened) = clientWith({ _, relay ->
            if (relayThenDie) {
                relay(JsProtocol.encodeHostCall("ajax", "{}"))
                relayThenDie = false
            }
            throw ChannelDeadException("每次都死")
        })
        // 第一次：回调过 → 不重放，只连了一条通道
        assertEquals(JsStatus.UNAVAILABLE, client.execute(inv()).status)
        assertEquals(1, opened.size)
        // 第二次：本次没回调 → 重连并重放一次，共三条通道，然后仍报不可用
        assertEquals(JsStatus.UNAVAILABLE, client.execute(inv()).status)
        assertEquals(3, opened.size)
    }

    @Test
    fun `主线程调用直接抛，不进入最长 wallClockMs 的跨进程阻塞`() {
        val (client, opened) = clientWith({ _, _ -> okFrame("x") }, isMainThread = { true })
        val thrown = runCatching { client.execute(inv()) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertTrue("守卫必须在连接之前生效，否则连主线程都在等的通道已经建起来了", opened.isEmpty())
    }
}
