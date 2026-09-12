package com.ebook.source.sandbox

import com.ebook.source.script.SandboxProtocolException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧协议的往返与**失败归类**。
 *
 * 归类表是本文件的真价值：桥接层回给 Kotlin 的只有 `kind`/`errorName`/`errorMessage`/`timedOut`
 * 四个字段，「超时」「内存超限」「栈溢出」三种失败在用户侧必须是三句不同的话——
 * 说错的那句会把用户支去做一件没用的事（重导一条源、或者关掉沙箱）。
 */
class JsProtocolTest {

    @Test
    fun `请求帧往返保留模式、脚本与绑定，null 绑定表示该量不可用`() {
        val inv = JsInvocation(
            mode = JsMode.SEGMENT,
            source = "result.replace(/\\s/g, '')",
            bindings = mapOf("result" to "正文", "baseUrl" to null, "page" to "3"),
        )
        val back = JsProtocol.decodeRequest(JsProtocol.encodeRequest(inv, deadlineMonoMs = 1234L))
        assertEquals(JsMode.SEGMENT, back.invocation.mode)
        assertEquals(inv.source, back.invocation.source)
        assertEquals("正文", back.invocation.bindings["result"])
        assertNull(back.invocation.bindings["baseUrl"])
        assertEquals("3", back.invocation.bindings["page"])
        assertEquals(1234L, back.deadlineMonoMs)
    }

    @Test
    fun `成功响应带 JSON 数据时原样解出，不带时为 null`() {
        val ok = JsProtocol.decodeOutcome(JsProtocol.encodeOutcome(JsOutcome(JsStatus.OK, stringJson("章节名"))))
        assertEquals(JsStatus.OK, ok.status)
        assertEquals("章节名", ok.text)

        val undefined = JsProtocol.decodeOutcome(
            JsProtocol.encodeOutcome(JsOutcome(JsStatus.OK, data = null)),
        )
        assertEquals(JsStatus.OK, undefined.status)
        assertNull(undefined.text)
    }

    @Test
    fun `脚本完成值是标量时 text 取字面量，是对象时按没有值处置`() {
        // `result = 2` 这类翻页规则在内核里就是 number；旧写法按 String 严格解码会静默给出 null
        val number = JsProtocol.decodeOutcome(JsProtocol.encodeOutcome(JsOutcome(JsStatus.OK, JsonPrimitive(42))))
        assertEquals("42", number.text)
        val boolean = JsProtocol.decodeOutcome(JsProtocol.encodeOutcome(JsOutcome(JsStatus.OK, JsonPrimitive(true))))
        assertEquals("true", boolean.text)

        // 计划里这一行写的是 `parseToJsonElement("""{"a":1}""")))),`：四个连续引号会被 Kotlin 词法器
        // 折进原始字符串的内容里、吃掉一个右括号（当场语法错）。改为先取出元素，引数与括号各就各位。
        val anObject = Json.parseToJsonElement("""{"a":1}""")
        val obj = JsProtocol.decodeOutcome(JsProtocol.encodeOutcome(JsOutcome(JsStatus.OK, anObject)))
        assertNull(obj.text)
        assertEquals(anObject, obj.data)
    }

    @Test
    fun `桥的 exception 帧按 errorName 与 errorMessage 归类为超时、内存、栈、语法、运行时`() {
        // 超时：中断器置位，与「脚本自己抛了个异常」是两件事
        assertEquals(
            JsStatus.TIMEOUT,
            JsProtocol.mapStatus(kind = "exception", errorName = "InternalError", errorMessage = "", timedOut = true),
        )
        // 内核 OOM 与栈溢出都是 InternalError，只有 message 能分开
        assertEquals(
            JsStatus.MEMORY,
            JsProtocol.mapStatus("exception", "InternalError", "out of memory", false),
        )
        // 栈溢出的两句文案**出自 vendored 的内核字面量**（third_party/quickjs/quickjs.c 的
        // `JS_ThrowInternalError(ctx, "stack overflow")`，另有带 op/pc 的变体同样含此前缀）。
        // 内核被 PIN.sha256 钉住，文案不会自行漂移，所以字面量匹配是稳的——但**升级内核时
        // 必须回来复核这两句**：换了措辞不会有任何测试变红，只会让深递归从 STACK 悄悄落进
        // RUNTIME（用户看到的话术就从「脚本递归太深」变成「脚本运行出错」）。本用例就是那道提醒。
        assertEquals(
            JsStatus.STACK,
            JsProtocol.mapStatus("exception", "InternalError", "stack overflow", false),
        )
        assertEquals(
            JsStatus.STACK,
            JsProtocol.mapStatus("exception", "InternalError", "maximum call stack too deep", false),
        )
        assertEquals(
            JsStatus.SYNTAX,
            JsProtocol.mapStatus("exception", "SyntaxError", "unexpected token", false),
        )
        assertEquals(
            JsStatus.RUNTIME,
            JsProtocol.mapStatus("exception", "TypeError", "not a function", false),
        )
    }

    @Test
    fun `未识别的 kind 一律按运行时失败，绝不折叠成成功`() {
        assertEquals(
            JsStatus.RUNTIME,
            JsProtocol.mapStatus(kind = "nonsense", errorName = "", errorMessage = "", timedOut = false),
        )
        assertFalse(JsOutcome(JsStatus.RUNTIME, error = "x").isSuccess)
    }

    @Test
    fun `响应帧里的状态名本侧不认识时归为运行时失败，绝不折叠成成功`() {
        // 两侧代码版本漂移（执行器侧新加了一个 JsStatus 名字）时，「读不懂的状态」若折叠成 OK，
        // 调用方就会拿一个 null data 当「脚本没值」——把失败说成没有结果，比报错了还难查。
        val frame = """{"status":"TIME_TRAVELLED","data":null,"error":"对面版本比我新"}"""
        val decoded = JsProtocol.decodeOutcome(frame)
        assertEquals(JsStatus.RUNTIME, decoded.status)
        assertFalse(decoded.isSuccess)
        assertEquals("对面版本比我新", decoded.error)
    }

    @Test
    fun `响应报文超上限时解码即判 TOO_LARGE，不把超限的原文交给调用方`() {
        // 上限用「调小限值」来测，不靠造几 MB 的字符串：造大帧只会让这条用例自己吃掉几十 MB
        val frame = JsProtocol.encodeOutcome(JsOutcome(JsStatus.OK, stringJson("x".repeat(100))))
        val decoded = JsProtocol.decodeOutcome(frame, JsLimits(maxOutcomeBytes = 40))
        assertEquals(JsStatus.TOO_LARGE, decoded.status)
        assertNull(decoded.data)
        assertTrue(decoded.error!!.contains("上限"))
    }

    @Test
    fun `报文长度按 UTF-8 字节数算而非 UTF-16 码元数`() {
        // String.length 是码元数，拿它当字节数会把汉字源少算三倍；代理对若按两码元各算三字节又会多算。
        // emoji 用转义写，用例判定不随源文件编码漂移。
        assertEquals(1L, utf8Length("a"))
        assertEquals(2L, utf8Length("é"))
        assertEquals(3L, utf8Length("字"))
        assertEquals(4L, utf8Length("\uD83D\uDE00"))
        assertEquals(8L, utf8Length("a字\uD83D\uDE00"))
        assertEquals("x字".toByteArray().size.toLong(), utf8Length("x字"))
    }

    @Test
    fun `上限判的是整帧字节数：等于帧长放行、小一字节即拒`() {
        val frame = JsProtocol.encodeOutcome(JsOutcome(JsStatus.OK, stringJson("汉字汉字")))
        val size = utf8Length(frame)
        assertEquals(JsStatus.OK, JsProtocol.decodeOutcome(frame, JsLimits(maxOutcomeBytes = size.toInt())).status)
        assertEquals(
            JsStatus.TOO_LARGE,
            JsProtocol.decodeOutcome(frame, JsLimits(maxOutcomeBytes = (size - 1).toInt())).status,
        )
    }

    @Test
    fun `host 回调帧往返保留 api 与参数，回复帧的 ok=false 带得出错误`() {
        val (api, args) = JsProtocol.decodeHostCall(JsProtocol.encodeHostCall("ajax", """{"url":"https://x"}"""))
        assertEquals("ajax", api)
        assertTrue(args.contains("https://x"))

        val reply = JsProtocol.decodeHostReply(JsProtocol.encodeHostReply(ok = false, data = null, error = "私网地址"))
        assertFalse(reply.ok)
        assertEquals("私网地址", reply.error)
    }

    @Test
    fun `绑定值不是合法 JSON 文本时按字符串兜住，绝不让解码抛出去`() {
        // 桥接层解码抛出去 = .so 里拿到一个未定义形态，症状是崩进程而不是报错
        val frame = """{"mode":"SEGMENT","source":"x","bindings":{"result":不是JSON}}"""
        val decoded = runCatching { JsProtocol.decodeRequest(frame) }
        assertTrue("解码失败必须返回类型化失败而不是抛：${decoded.exceptionOrNull()}", decoded.isFailure)
        assertTrue(decoded.exceptionOrNull() is SandboxProtocolException)
    }

    @Test
    fun `未知执行模式名按类型化协议失败拒绝，不漏出 JDK 异常`() {
        // 模式名没有可信默认值：兜成任一模式都会拿这段脚本来执行另一件事。
        // 但 KDoc 承诺「解码不往外抛未类型化异常」，所以必须换成 SandboxProtocolException 再抛
        val frame = """{"mode":"NOT_A_MODE","source":"x","bindings":{},"deadline":1}"""
        val decoded = runCatching { JsProtocol.decodeRequest(frame) }
        val error = decoded.exceptionOrNull()
        assertTrue("未知模式名必须失败而不是解出一个模式", decoded.isFailure)
        assertEquals("未知模式名漏出的异常类型", SandboxProtocolException::class.java, error?.javaClass)
    }

    private fun stringJson(s: String): JsonElement =
        Json.parseToJsonElement(Json.encodeToString(String.serializer(), s))
}