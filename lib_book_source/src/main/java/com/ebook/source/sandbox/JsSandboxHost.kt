package com.ebook.source.sandbox

import com.ebook.source.script.EvalContext
import com.ebook.source.script.SandboxScriptJs
import com.ebook.source.script.ScriptJsBridge
import com.ebook.source.script.ScriptTransport
import okhttp3.OkHttpClient

/**
 * 主进程侧的沙箱装配面：一条进程级长命的客户端 + 每任务短命的回调代理，两者的持有关系在这里收口。
 *
 * 为什么要这么一层（而不是让解析器直接持有 `JsSandboxClient`）：客户端的 `hostHandler` 是**构造期
 * 定死**的（Task 6），而代理的状态是任务级的，两者之间必须有一个可换的转子（[HostCallbackRouter]）。
 * 转子藏在构造期，客户端与它就不会被装配代码各配一次——配错的形态是「代理挂上了但客户端读的是
 * 另一个转子」，症状是每一次 `java.ajax` 都回「主进程没有正在进行的脚本任务」，在设备上极难查。
 *
 * public：出现在 `ScriptBookParser` 与 `BookSourceManagerImpl` 的公开构造签名上。
 * 里面**不预建任何连接**——第一次 `call` 才 bind（Task 6 的策略），所以本类可以在任何进程、
 * 任何线程上安全构造，包括没有 `.so` 的 JVM 单测。
 */
class JsSandboxHost(
    private val client: JsSandboxClient,
    /** `@Named("source")` 纯净客户端：沙箱发起的请求由它派生出守门客户端后代发，永远不带用户 token */
    val baseClient: OkHttpClient,
    internal val limits: JsLimits = JsLimits(),
    private val router: HostCallbackRouter = HostCallbackRouter(),
) {

    /** 挂载本任务的回调代理并执行一次。代理与 `client` 的时序由 [router] 保证，返回即摘除 */
    fun call(proxy: HostHandler, invocation: JsInvocation): JsOutcome =
        router.withProxy(proxy) { client.execute(invocation) }

    /**
     * 为一次解析任务造桥（规则层唯一的入口）。
     *
     * internal 成员可以收 internal 类型：`EvalContext`/`ScriptJsBridge` 都不出模块，
     * `lib_book_common` 只负责把本类的实例递给 `ScriptBookParser`。
     * [transport] 必须是**过守门的**传输（`GuardedNetwork.clientFor(baseClient, guard)` 派生出的那条），
     * 否则脚本拼出来的 URL 会绕过 DNS 重绑防护；调用点只有 `ScriptBookParser.sandboxGateway` 一处。
     */
    internal fun bridgeFor(
        ctx: EvalContext,
        guard: JsNetworkGuard,
        transport: ScriptTransport,
        bindings: Map<String, String?> = emptyMap(),
        /** 源级会话罐：与 [transport] 那条客户端挂的同一份；null = 本次装配不带 cookie 面 */
        cookies: SourceCookieJar? = null,
    ): ScriptJsBridge = SandboxScriptJs(
        executeTask = { proxy, invocation -> call(proxy, invocation) },
        ctx = ctx,
        guard = guard,
        transport = transport,
        staticBindings = bindings,
        limits = limits,
        // 探测与取体共用一只罐：只在一侧带 cookie 会让「HEAD 探得到、取体拿不到会话」这种
        // 半截会话状态出现在同一轮任务里
        cookies = cookies,
        statusProbe = HeadStatusProbe(GuardedNetwork.clientFor(baseClient, guard, cookies))::status,
    )
}
