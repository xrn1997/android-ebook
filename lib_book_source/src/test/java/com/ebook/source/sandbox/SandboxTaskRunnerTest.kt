package com.ebook.source.sandbox

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行器侧「一帧进、一帧出」的裁决。
 *
 * 这一层独立存在的唯一理由：`Parcel` 与 `Binder` 在 JVM 单测里是「Method ... not mocked」的桩，
 * 于是真正会咬人的三件事——期限已过还进不进内核、回帧超限时给不给原文、任务边界是不是每次都过——
 * 若写在 `onTransact` 里就只有连上设备才看得见。分出来之后它们各有名字、各自可测。
 */
class SandboxTaskRunnerTest {

    private var now = 10_000L
    private val events = mutableListOf<String>()
    private var outcome = JsOutcome(JsStatus.OK, stringJson("第一章"))

    private fun stringJson(s: String): JsonElement =
        Json.parseToJsonElement(Json.encodeToString(String.serializer(), s))

    private fun request(deadline: Long) =
        JsProtocol.encodeRequest(JsInvocation(JsMode.SEGMENT, "result = 1", emptyMap()), deadline)

    private fun run(
        frame: String?,
        limits: JsLimits = JsLimits(),
        eval: (JsInvocation, Long) -> JsOutcome = { _, _ -> events += "eval"; outcome },
    ): String = runSandboxTask(
        requestFrame = frame,
        limits = limits,
        now = { now },
        eval = eval,
        onTaskBoundary = { events += "boundary" },
    )

    @Test
    fun `期限已过时根本不进内核`() {
        val decoded = JsProtocol.decodeOutcome(run(request(deadline = now - 1)))
        assertEquals(JsStatus.TIMEOUT, decoded.status)
        assertTrue("排队到期限之后的任务再进内核，抢的是下一条规则的时限", events.isEmpty())
        assertTrue(decoded.error!!.contains("未进内核"))
    }

    @Test
    fun `请求帧解不出来时回一帧读得出来的失败而不是把异常穿过 binder`() {
        // 少字段的帧：decodeRequest 会抛 SandboxProtocolException
        val frame = run("""{"mode":"SEGMENT","source":"x"}""")
        val decoded = JsProtocol.decodeOutcome(frame)
        assertEquals(JsStatus.UNAVAILABLE, decoded.status)
        assertTrue("穿过 binder 的异常到对面只剩一句 DeadObjectException", decoded.error!!.contains("协议不合"))
    }

    @Test
    fun `事务里没有请求帧时回不可用而不是空指针`() {
        val decoded = JsProtocol.decodeOutcome(run(null))
        assertEquals(JsStatus.UNAVAILABLE, decoded.status)
        assertTrue(decoded.error!!.contains("请求帧"))
    }

    @Test
    fun `回帧超上限时只回一句太大，不带超限原文`() {
        outcome = JsOutcome(JsStatus.OK, stringJson("字".repeat(4_000)))
        val limits = JsLimits(maxOutcomeBytes = 1_000)
        val frame = run(request(now + 5_000), limits)
        val decoded = JsProtocol.decodeOutcome(frame, limits)
        assertEquals(JsStatus.TOO_LARGE, decoded.status)
        assertFalse("原文一旦进了回帧，主进程就得先解它一遍才知道要丢", frame.contains("字字字"))
        assertTrue(frame.contains("字节"))
    }

    @Test
    fun `回帧在上限内时状态与文本原样带回`() {
        val decoded = JsProtocol.decodeOutcome(run(request(now + 5_000)))
        assertEquals(JsStatus.OK, decoded.status)
        assertEquals("第一章", decoded.text)
    }

    @Test
    fun `每个任务的最开头都过一次任务边界`() {
        run(request(now + 5_000))
        run(request(now + 5_000))
        assertEquals(listOf("boundary", "eval", "boundary", "eval"), events)
    }

    @Test
    fun `内核抛出未类型化异常时换成一帧不可用，绝不让它穿过 binder`() {
        val frame = run(request(now + 5_000), eval = { _, _ -> throw IllegalStateException("描述符坏掉") })
        val decoded = JsProtocol.decodeOutcome(frame)
        assertEquals(JsStatus.UNAVAILABLE, decoded.status)
        assertTrue(decoded.error!!.contains("描述符坏掉"))
    }

    @Test
    fun `内核回的失败状态原样编码，不折叠成 RUNTIME`() {
        for (status in listOf(JsStatus.SYNTAX, JsStatus.MEMORY, JsStatus.STACK, JsStatus.UNSUPPORTED_API)) {
            outcome = JsOutcome(status, error = "细节")
            val decoded = JsProtocol.decodeOutcome(run(request(now + 5_000)))
            assertEquals(status, decoded.status)
            assertEquals("细节", decoded.error)
        }
    }
}
