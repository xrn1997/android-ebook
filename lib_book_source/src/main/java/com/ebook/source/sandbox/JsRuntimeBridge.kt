package com.ebook.source.sandbox

import com.ebook.source.script.SandboxProtocolException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 内核的执行入口：句柄生命周期、描述符解码、失败之后的重置。
 *
 * 与 [JsSandboxClient] 的分工是**一进程一侧**：客户端管跨进程（连接、串行化、重放、断连），
 * 这一层管进程内（runtime、context、四项限值、描述符）。两者都不认识规则语法——
 * 那是 `lib_book_source` 解释器的事。
 *
 * 不是线程安全的，也不需要是：同一时刻只有一帧在飞，而这个保证唯一的来源是主进程
 * `JsSandboxClient` 的 `inFlight` 锁——`:js` 侧没有工作线程，binder 线程池至多 15 条
 * （见 [SandboxService] 的线程模型说明）。哪天放开那把锁，这里的 `handle` 就得改成锁保护。
 */
internal class JsRuntimeBridge(private val limits: JsLimits = JsLimits()) {

    /**
     * 桥接层描述符（Task 7 开头）。字段全有默认值，解码不开 `ignoreUnknownKeys`：
     * 生产者与消费者出自同一次构建，这里出现未知键就是两侧不同步，那种错位必须当场响。
     */
    @Serializable
    private data class NativeDescriptor(
        val kind: String,
        val timedOut: Boolean = false,
        val errorName: String = "",
        val errorMessage: String = "",
        val data: JsonElement? = null,
    )

    private var handle = 0L

    /** 供 Task 8 的 `PING` 用：不建 runtime，只回答「.so 在不在」 */
    val isReady: Boolean get() = QuickJsNative.loaded

    fun evaluate(invocation: JsInvocation, deadlineMonoMs: Long): JsOutcome {
        if (!QuickJsNative.loaded) {
            return JsOutcome(
                JsStatus.UNAVAILABLE,
                error = "libebook_js.so 未加载（缺本设备的 ABI，或 .so 与 Kotlin 不同步）",
            )
        }
        if (!ensureRuntime()) {
            return JsOutcome(JsStatus.UNAVAILABLE, error = "沙箱 runtime 创建失败（堆上限 ${limits.heapBytes} 字节）")
        }
        val descriptor = QuickJsNative.nativeEval(
            handle,
            bindingsJson(invocation.bindings),
            // 垫片与绑定落在全局量上之后才是用户脚本；两段同一份文本一次求值，省一次 context 往返
            QuickJsPrelude.BOOTSTRAP + "\n" + invocation.source,
            deadlineMonoMs,
        )
        val parsed = runCatching { Json.decodeFromString(NativeDescriptor.serializer(), descriptor) }
            .getOrElse { throw SandboxProtocolException("桥接层描述符形态不符：${descriptor.take(120)}", it) }
        val status = JsProtocol.mapStatus(parsed.kind, parsed.errorName, parsed.errorMessage, parsed.timedOut)
        if (status == JsStatus.TIMEOUT || status == JsStatus.MEMORY || status == JsStatus.STACK) reset()
        return JsOutcome(
            status,
            parsed.data.takeIf { status == JsStatus.OK },
            if (status == JsStatus.OK) null else parsed.errorMessage.ifBlank { "脚本以 ${parsed.errorName} 结束" },
        )
    }

    /**
     * 任务边界的强制重置（ADR-0028 决策 5）。
     *
     * 超时、堆耗尽、栈耗尽之后，context 自身的状态内核都不再保证：复用它可能让下一条规则拿到
     * 上一条的残留，或者在「栈已经耗到边界」的姿势上以同样的方式失败——那种失败看起来完全像
     * 「这条规则自己有问题」，而真话是它借用了别人的残局。重建的代价是一次 context 分配，
     * 摊在一次的正文请求旁边不构成瓶颈。
     */
    fun reset() {
        if (handle == 0L) return
        if (!QuickJsNative.nativeReset(handle, QuickJsPrelude.SOURCE)) {
            // 重建失败就是整个 runtime 不能用了：销毁并在下次调用时新建，不带着坏 context 继续跑
            QuickJsNative.nativeDestroy(handle)
            handle = 0L
        }
    }

    fun close() {
        if (handle != 0L) {
            QuickJsNative.nativeDestroy(handle)
            handle = 0L
        }
    }

    private fun ensureRuntime(): Boolean {
        if (handle != 0L) return true
        handle = QuickJsNative.nativeCreate(limits.heapBytes, limits.stackBytes)
        if (handle == 0L) return false
        if (QuickJsNative.nativeReset(handle, QuickJsPrelude.SOURCE)) return true
        QuickJsNative.nativeDestroy(handle)
        handle = 0L
        // 垫片随包冻结、永不来自书源：装不上就是本仓的缺陷，抛出去，不伪装成「这条规则解不开」
        throw SandboxProtocolException("沙箱垫片装载失败：QuickJsPrelude 与 js_bridge 不匹配")
    }

    /**
     * 绑定以 **JSON 值**过边界，脚本侧由 `__bind` 落成全局量。
     *
     * 值为 null 的绑定直接不放进对象：垫片的 `__bind` 把「属性不存在」与「属性是 null」都落成
     * `undefined`，这样脚本里的 `typeof result` 判空才真的生效。
     */
    private fun bindingsJson(bindings: Map<String, String?>): String = buildJsonObject {
        bindings.forEach { (key, value) -> if (value != null) put(key, JsonPrimitive(value)) }
    }.toString()
}
