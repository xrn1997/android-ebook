package com.ebook.source.sandbox

import com.ebook.source.script.JsApiRejectedException
import com.ebook.source.script.JsApiTarget
import com.ebook.source.script.JsHostApi
import kotlinx.serialization.json.Json

/**
 * 脚本能力的唯一入口，跑在 `:js` 进程里。
 *
 * **白名单在这里判第二次不是重复劳动**：垫片里的 `java.md5(...)` 只是给人看的表面，
 * `__host_call` 本身就是 globalThis 上的一个属性，脚本可以 `__host_call('ajax','["http://…"]')`
 * 直接绕过去。C++ 侧刻意不认识能力表（加一个能力不碰原生代码），所以边界必须落在 Kotlin、
 * 且必须落在这一层——判表改表都不需要重编 `.so`。
 *
 * 名字必须是 `handle` 且带 `@JvmStatic`：`js_bridge.cpp` 以字符串常量写死
 * `GetStaticMethodID("handle", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")`，
 * R8 看不见这种引用（本模块 `consumer-rules.pro` 里对 `HostDispatcher` 的 `-keep` 就是为它写的），
 * 改签名两侧必须同步。
 * `handle` 本身**不得**标 internal：Kotlin 会给 internal 成员的 JVM 名字加上模块后缀
 * （`handle$lib_book_source_debug`），那样 `GetStaticMethodID` 就找不到了——声明在 internal
 * object 里的 public 成员不改名，这正是当前写法的原因。
 */
internal object HostDispatcher {

    /** HOST 类能力的落点：由 [SandboxService] 在 onCreate 时装进来（要回主进程的那一路只有它认识） */
    @Volatile
    private var relay: ((api: String, argsJson: String) -> HostReply)? = null

    fun installRelay(handler: (api: String, argsJson: String) -> HostReply) {
        // 装两次意味着两个服务在抢同一个执行器进程：那会让前一半请求走进已死的通道，静默失败
        check(relay == null) { "host 中继只能安装一次" }
        relay = handler
    }

    /** 由 [SandboxService.onDestroy] 摘除：执行器进程重建时要把中继装到新服务的回调通道上 */
    fun clearRelay() {
        relay = null
    }

    /**
     * JNI 入口。异常一律就地兜住。
     *
     * 这条边界上没有「让它抛出去」这个选项：`handle` 是被 C++ 调的，异常从 JNI 冒出去最坏的情况
     * 是带走整个 `:js` 进程，而脚本作者看到的会是「这条源解不开」。
     */
    @JvmStatic
    fun handle(api: String, argsJson: String): String = handleReply(api, argsJson).let {
        JsProtocol.encodeHostReply(it.ok, it.data, it.error)
    }

    private fun handleReply(api: String, argsJson: String): HostReply = runCatching {
        // 按 jsName 查而不是按枚举常量名：过边界的是脚本里写的那个名字（`base64Encode`），
        // 而 `entries.firstOrNull { it.name == api }` 比的是常量名（`BASE64_ENCODE`），
        // 那样每一能力都会被判成「沙箱里没有」，且只在设备上暴露（JVM 侧根本不调到这里）。
        val capability = JsHostApi.byJsName(api)
            ?: return@runCatching HostReply(false, null, "沙箱里没有「$api」这个能力")
        when (capability.target) {
            JsApiTarget.COMPUTE -> HostReply(true, HostCompute.invoke(api, Json.parseToJsonElement(argsJson)), null)
            JsApiTarget.HOST -> relay?.invoke(api, argsJson)
                ?: HostReply(false, null, "执行器尚未接上主进程，$api 这类能力暂时不可用")
        }
    }.getOrElse {
        if (it is JsApiRejectedException) {
            // HostCompute 的拒绝消息已经带齐了 api 名与根因（它的 KDoc 就是这么定的），原样给脚本。
            // 套一层前缀会把「密文根本不是 base64」说成「沙箱侧处理失败」，把人支去换密钥。
            HostReply(false, null, it.message ?: "$api 调用失败")
        } else {
            // 走到这里只剩两种可能：argsJson 不是合法 JSON、内核 OOM。两种都值得让脚本看见原文，
            // 因为它们的处方完全不同（分别是「规则写坏了」「本仓有缺陷」）
            HostReply(false, null, "沙箱侧处理失败：${it.message?.take(120) ?: it.javaClass.simpleName}")
        }
    }
}
