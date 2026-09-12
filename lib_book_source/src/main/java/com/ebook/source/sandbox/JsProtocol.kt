package com.ebook.source.sandbox

import com.ebook.source.script.SandboxProtocolException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** 执行哪种 JS 载荷：决定桥接层如何取「结果值」，以及主进程侧把哪些绑定喂进去 */
enum class JsMode {
    /** 整条规则段（`@js:` / `<js></js>`）：程序的完成值 */
    SEGMENT,

    /** `{{…}}` 里的表达式：包成表达式取单个字符串 */
    EXPRESSION,

    /** URL 选项 `js`：脚本改写 `url`/`headers`，回传对象 */
    URL_OPTION,

    /** URL 选项 `bodyJs`：对响应体做二次处理，回传新文本 */
    BODY_JS,

    /** `init` 的 JS 分支：必须回传一个对象，各字段按对象键取值（规格 §1.4） */
    INIT,
}

/** 执行器回给调用方的失败/成功归类。枚举值的顺序无意义，但**每个值都对应一句不同的用户话术** */
enum class JsStatus {
    OK,
    TIMEOUT,
    MEMORY,
    STACK,
    SYNTAX,
    RUNTIME,

    /** 脚本调了白名单外的能力（`cache`、`cookie`、Java 类桥等） */
    UNSUPPORTED_API,

    /** 响应报文超过 [JsLimits.maxOutcomeBytes] */
    TOO_LARGE,

    /** 执行器没连上 / 中途断掉 / 加载失败 */
    UNAVAILABLE,
}

/** 一次求值的输入。[bindings] 值为 null 表示「该量本场景不可用」，垫片会把它显式设成 `undefined` */
data class JsInvocation(
    val mode: JsMode,
    val source: String,
    val bindings: Map<String, String?> = emptyMap(),
)

/** 一次求值的输出。[data] 是脚本完成值的 JSON 形态，[error] 只在与 [JsStatus.OK] 同时出现时为 null */
data class JsOutcome(
    val status: JsStatus,
    val data: JsonElement? = null,
    val error: String? = null,
) {
    val isSuccess: Boolean get() = status == JsStatus.OK

    /**
     * 完成值按字符串取（`@js:` 产文本、`bodyJs` 产新页面文本都走这里）。
     *
     * 判据是 **JSON 标量**：数字与布尔也取得出文本（`result = 2` 这类翻页规则在内核里就是
     * number），只有对象与数组取不出——那是「规则要字符串而脚本给了个结构」，按没有值处置。
     * 不用「先按 String 严格解码、解不动就 null」：number 那一支会静默失败，症状正是
     * 「这条源解不出正文」而一行报错都没有，是本项目最忌讳的一类坏消息。
     */
    val text: String? get() = (data as? JsonPrimitive)?.takeIf { it != JsonNull }?.content
}

/**
 * 主进程回给脚本的一次 host call 结果。
 *
 * public 而非 internal：Task 6 的 `HostHandler`（公开签名，`lib_book_common` 的 Hilt 装配要构造它）
 * 以此为返回类型，公开签名上不许出现 internal 类型。
 */
data class HostReply(val ok: Boolean, val data: JsonElement?, val error: String?)

/**
 * 请求帧（主进程 → `:js` 执行器）。
 *
 * 为什么单独一个私有 DTO 而不直接序列化 [JsInvocation]：`mode` 过边界时以**枚举名**出现，
 * 而 [JsInvocation] 的契约是强类型枚举；中间加一层，跨进程契约就只由本表的字段与名字决定，
 * 不会因为领域模型加个派生属性就悄悄改了线上形态。`deadline` 是单调时钟毫秒（`BOOTTIME`），
 * 不是墙钟——墙钟会被用户改系统时间推进或回拨，死线必须走不受其影响的时基。
 */
@Serializable
private data class RequestFrame(
    val mode: String,
    val source: String,
    val bindings: Map<String, String?>,
    @SerialName("deadline") val deadlineMonoMs: Long,
)

/**
 * 响应帧（`:js` 执行器 → 主进程）。
 *
 * 字段可空性即协议语义：成功帧的 `data` 是完成值的 JSON 形态、`error` 为 null；
 * 失败帧恰好相反。两侧都不填默认值之外的字段，故 `ignoreUnknownKeys = false` 能把
 * 「两侧不是同一次构建」当场变成解码失败，而不是静默丢字段。
 */
@Serializable
private data class OutcomeFrame(
    val status: String,
    val data: JsonElement? = null,
    val error: String? = null,
)

/**
 * 控制面的帧编解码 + 失败归类。
 *
 * 为什么单独一层（而不是让桥接层直接回 Kotlin 对象）：协议是**跨进程边界上的唯一契约**，
 * 它的兼容性只由这里的 JSON 形态决定。两侧共用这一个文件，就不会出现「改了字段只改了一侧」
 * 这种当场看不出来、跑起来才静默丢值的错。
 *
 * 解码一律不往外抛未类型化异常：坏帧在设备上的另一条路径是在 `.so` 的调用栈里炸出去
 * （整个 `:js` 进程随之没），所以「读不出来」必须是一种有名字的失败。
 */
internal object JsProtocol {

    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    fun encodeRequest(invocation: JsInvocation, deadlineMonoMs: Long): String =
        json.encodeToString(
            RequestFrame.serializer(),
            RequestFrame(invocation.mode.name, invocation.source, invocation.bindings, deadlineMonoMs),
        )

    fun decodeRequest(frame: String): DecodedRequest =
        parse(frame, RequestFrame.serializer()).let {
            DecodedRequest(
                JsInvocation(parseMode(it.mode), it.source, it.bindings),
                it.deadlineMonoMs,
            )
        }

    /**
     * 请求帧解码后的领域形态。
     *
     * 单独一个返回类型（而不是让 [decodeRequest] 回 `Pair`）是为了让死线不被当成
     * 「第二个值」随手丢掉：调用方必须一次拿到「要执行什么」与「什么时候必须停」，
     * 少了后者就会退化成没有时限的执行。
     */
    data class DecodedRequest(val invocation: JsInvocation, val deadlineMonoMs: Long)

    fun encodeOutcome(outcome: JsOutcome): String =
        json.encodeToString(OutcomeFrame.serializer(), OutcomeFrame(outcome.status.name, outcome.data, outcome.error))

    /**
     * 解码响应帧。
     *
     * 大小检查**先于** JSON 解析：一个 200 MB 的响应如果先进了 `String`，报文上限就只是在
     * 保护「解析之后的内存」，而攻击要的正是「解析这一步」。
     */
    fun decodeOutcome(frame: String, limits: JsLimits = JsLimits()): JsOutcome {
        if (utf8Length(frame) > limits.maxOutcomeBytes) {
            return JsOutcome(
                JsStatus.TOO_LARGE,
                error = "沙箱响应超出 ${limits.maxOutcomeBytes} 字节上限",
            )
        }
        return parse(frame, OutcomeFrame.serializer()).let {
            JsOutcome(mapStatus(it.status), it.data, it.error)
        }
    }

    /**
     * 归类桥接层的一条异常。
     *
     * 判定次序是刻意的：**先看中断标志，再看 message 关键字**。内核在超时后会抛
     * `InternalError("interrupted")`，此时 message 里什么都可能带上（脚本正好在分配大对象时被打断），
     * 按 message 归类就会把「超时」误报成「内存超限」，用户会去关一条根本没那么吃内存的源。
     *
     * 关键字匹配是**能有的最好办法**：本内核的异常对象不带稳定的错误码字段
     * （`JS_ThrowOutOfMemory` 与 `JS_ThrowStackOverflow` 抛的都是 `InternalError` + 固定 message）。
     *
     * 一个例外由桥接层兜着，不在这张表里：堆真的到限时，那句 `out of memory` 本身就要一次分配
     * （先建错误对象、再建 message），可能被当场拒掉，落到这里的是一条**没有 message** 的失败。
     * 桥接层按分配器自己的证据（本次执行拒过分配）把那句 message 补出来，用的就是这张表已有的词汇，
     * 所以「内存超限」不需要在这里长出第二种认法。
     */
    fun mapStatus(kind: String, errorName: String, errorMessage: String, timedOut: Boolean): JsStatus = when {
        // 完成值拿到了但已超时：仍然算超时，半截值不可信
        kind == "ok" && timedOut -> JsStatus.TIMEOUT
        kind == "ok" -> JsStatus.OK
        timedOut -> JsStatus.TIMEOUT
        kind == "unsupported" -> JsStatus.UNSUPPORTED_API
        kind == "exception" -> when {
            errorName == "SyntaxError" -> JsStatus.SYNTAX
            errorMessage.contains("out of memory", ignoreCase = true) -> JsStatus.MEMORY
            errorMessage.contains("stack overflow", ignoreCase = true) ||
                    errorMessage.contains("too deep", ignoreCase = true) -> JsStatus.STACK

            else -> JsStatus.RUNTIME
        }

        else -> JsStatus.RUNTIME
    }

    /**
     * 模式名查表。过边界的是枚举名——请求帧由本文件 [encodeRequest] 自己写入 `mode.name`，
     * 两侧同源同词表，故按名比即可（host 回调那一侧过界的是脚本里写的 JS 名，不能照此办理）。
     *
     * 未知模式名**不**折叠成任一默认模式：那等于拿一段 `@js:` 按 `init` 的取法去执行，
     * 产出的是一个看似合理的答案而不是任何报错。这种帧唯一的真意是「执行器与主进程不是同一次
     * 构建」，必须说成协议失败——与 [parse]、[mapStatus] 同守本类 KDoc 那条「解码不外抛未类型化异常」。
     */
    private fun parseMode(modeName: String): JsMode = JsMode.entries.firstOrNull { it.name == modeName }
        ?: throw SandboxProtocolException("未知的执行模式名：${modeName.take(40)}")

    // 未知状态名不能折叠成 OK——那是「把失败说成没有结果」
    private fun mapStatus(statusName: String): JsStatus =
        runCatching { JsStatus.valueOf(statusName) }.getOrDefault(JsStatus.RUNTIME)

    fun encodeHostCall(api: String, argsJson: String): String =
        json.encodeToString(HostCallFrame.serializer(), HostCallFrame(api, argsJson))

    fun decodeHostCall(frame: String): Pair<String, String> =
        parse(frame, HostCallFrame.serializer()).let { it.api to it.args }

    fun encodeHostReply(ok: Boolean, data: JsonElement?, error: String?): String =
        json.encodeToString(HostReplyFrame.serializer(), HostReplyFrame(ok, data, error))

    fun decodeHostReply(frame: String): HostReply = parse(frame, HostReplyFrame.serializer())
        .let { HostReply(it.ok, it.data, it.error) }

    /**
     * 脚本发出的 host call 请求帧。
     *
     * `args` 刻意保持**未解析的 JSON 串**：本层只负责把帧完整搬过进程边界，参数该长什么样
     * 由 host 侧的 `HostDispatcher` 按 `api` 分派后再解——在这里先解一次会让「不是本层的
     * 职责」变成两处解析、两处报错口径。
     */
    @Serializable
    private data class HostCallFrame(val api: String, val args: String)

    /**
     * 主进程回给脚本的 host call 结果帧。
     *
     * 与 [HostReply] 同形但**必须分开**：这一个的字段可空性参与 `ignoreUnknownKeys = false`
     * 的严格解码（跨进程契约），[HostReply] 是给宿主代码用的强类型领域值。合成一个会让
     * 「线上形态」与「领域模型」的改动互相牵动。
     */
    @Serializable
    private data class HostReplyFrame(val ok: Boolean, val data: JsonElement? = null, val error: String? = null)

    /** 所有解码的唯一入口：把 kotlinx 的 `SerializationException` 换成 [SandboxProtocolException] */
    private fun <T> parse(frame: String, serializer: KSerializer<T>): T =
        runCatching { json.decodeFromString(serializer, frame) }.getOrElse {
            throw SandboxProtocolException("帧形态不符：${frame.take(60)}", it)
        }
}

/**
 * UTF-8 编码后的字节数，**不分配中间数组**。
 *
 * 写成 `text.toByteArray().size` 也能得到正确答案，但「检查大小」这一步自己就要先把整帧复制一遍：
 * 一个 200 MB 的响应会当场多出 200 MB，而报文上限想防的正是这一笔。纯算术零分配，
 * 代价是必须自己认代理对（增补平面字符 4 字节、在 Java 里占 2 个 UTF-16 码元）。
 *
 * 三个上限（请求 / 响应 / host 回复）都取这一个口径，否则同一帧会在两侧算出两个长度。
 */
internal fun utf8Length(text: String): Long {
    var i = 0
    var total = 0L
    while (i < text.length) {
        val cp = text.codePointAt(i)
        total += when {
            cp < 0x80 -> 1L
            cp < 0x800 -> 2L
            cp < 0x10000 -> 3L
            else -> 4L
        }
        i += Character.charCount(cp)
    }
    return total
}
