package com.ebook.source.sandbox

import com.ebook.source.script.AccessorKind
import com.ebook.source.script.EvalContext
import com.ebook.source.script.JsApiRejectedException
import com.ebook.source.script.JsApiTarget
import com.ebook.source.script.JsHostApi
import com.ebook.source.script.RuleResult
import com.ebook.source.script.RuleValue
import com.ebook.source.script.ScriptRequest
import com.ebook.source.script.ScriptTransport
import com.ebook.source.script.ScriptUrlOption
import com.ebook.source.script.ScriptUrlOptions
import com.ebook.source.script.jsonText
import com.ebook.source.script.toTextList
import com.xrn1997.common.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Locale

private const val TAG = "JsCallbackProxy"

/** `post(url, {对象})` 的编码口径；键序见 [JsCallbackProxy.formBody] 的注释 */
private const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded; charset=utf-8"

/** 必须有请求体的方法：OkHttp 对 `method("POST", null)` 抛未类型化的 IllegalArgumentException */
private val BODY_REQUIRED_METHODS = setOf("POST", "PUT", "PATCH")

/**
 * 长连接的客户端与「本次任务的代理」之间唯一的换挡处。
 *
 * 存在的理由是一条类型冲突：`JsSandboxClient` 的 `hostHandler` 在构造时定死（它是一条 binder
 * 连接上的策略，连接活多久它活多久），而回调代理的状态必须是任务级的。两者不能是同一个对象，
 * 又不能按线程找（回调跑在 binder 线程池线程上，与发起 `execute` 的协程线程不同），
 * 所以「现在该交给谁」只能显式挂着。
 *
 * public 而非 internal：`lib_book_common` 的 Hilt 模块要先造它、再把它塞进 `JsSandboxClient`
 * 的 `hostHandler`，而依赖方的 test source set 不是 friend module，internal 类型出不去。
 * 本类只搬运，不含策略——所有拒绝与判定在 [HostHandler] 的实现里。
 *
 * 「同一时刻只有一个任务在飞」由 `JsSandboxClient` 的串行锁保证，这里就不必 CAS。
 * 将来谁放开那把锁的并发，这里必须换成按调用身份分发的表——出错的症状不是变慢，
 * 是**两个源的脚本互相看到对方的变量与当前页面**。
 */
class HostCallbackRouter : HostHandler {

    @Volatile
    private var current: HostHandler? = null

    override fun handle(api: String, argsJson: String): HostReply =
        current?.handle(api, argsJson)
            ?: HostReply(false, null, "主进程没有正在进行的脚本任务，$api 无处受理")

    /** 在 [proxy] 任职期间执行 [block]（通常就是一次 `client.execute`），回块的结果 */
    fun <T> withProxy(proxy: HostHandler, block: () -> T): T {
        check(current == null) { "上一次任务的代理还没收尾：回调里再发起执行不受支持" }
        current = proxy
        return try {
            block()
        } finally {
            current = null
        }
    }
}

/**
 * 主进程侧受理 host 回调的代理：脚本能对外界做的所有事都收在这一个类里。
 *
 * **[HostHandler] 的契约是「不得抛出」**：调用栈的另一头是 `:js` 进程里 `.so` 的 JNI 帧，异常
 * 穿出去最坏是带走整个执行器进程（Task 6 的客户端外面还兜了一层，两层都要在——少任何一层，
 * 症状都只是「这一源解不开、沙箱进程没」）。所以本类的失败一律是 `ok=false` 加一句可诊断的话。
 *
 * **实例作用域 = 单次解析任务**（一本书的一轮求值）：外呼计数、回调深度、当前页面基准都活在这里。
 * 做成源级复用，症状是「读完第一本书之后所有书的外呼配额已被用光」；做成进程级单例更糟——
 * `java.put` / `@put:` 的作用域会静默从任务级变成全局级，而规格 §5.2 定的就是任务级。
 *
 * **嵌套求值的 JS 闸门在调用方身上**：[evaluateNested] 必须收到一个**关掉 JS** 的求值器
 * （Task 10 装配时给）。沙箱只有一个 runtime 且此刻正攥在外层 `execute` 手上，嵌套再要一次 JS
 * 就是双向死锁；这条要求编译期传不出来，所以 [limits.maxCallbackDepth] 是它的绊线（正常恒为 1）。
 *
 * 可变字段一律 `@Volatile`：初值写在调用 `execute` 的线程上（构造时），之后的读写发生在受理回调的
 * binder 线程，而 binder 线程池**不保证连续两次回调落在同一条线程**。跨过这条边界的是 binder
 * 事务而不是本进程的 monitor，JMM 的 happens-before 不在我们手里。不加原子性是因为
 * 「同一时刻只有一帧在飞」已经排除了并发读-改-写，而那个前提只来自客户端的 `inFlight` 锁。
 *
 * 可见性：`internal` 够用。装配点（Task 10 的 `SandboxModule`）确实落在 `lib_book_common`，
 * 但跨过模块边界的只有 public 的 [HostCallbackRouter] 与 `JsSandboxHost`；本类的实例由同模块的
 * `SandboxScriptJs` 造出并直接交给转子（`JsSandboxHost.bridgeFor` 是 internal 成员，签名上出现
 * internal 类型合法），因此 `JsCallbackProxy`、`HeadStatusProbe`、`GuardedNetwork`、
 * `SandboxScriptJs`、`JsNetworkGuard`、`SourceHostAllowlist` 一律留在 internal——
 * 上浮面越小，将来改回调协议时被牵住的调用点越少。
 */
internal class JsCallbackProxy(
    /** 源级复用件：无状态，只答「这个 URL / 这个地址能不能碰」 */
    private val guard: JsNetworkGuard,
    /** 取体接缝：2c 起就是假件接缝，所以本类能在 JVM 上测透；实现按选项 charset 显式字节解码 */
    private val transport: ScriptTransport,
    /** `responseCode` 的落点：生产接线给 [HeadStatusProbe.status]，测试给常量 */
    private val statusProbe: suspend (String) -> Int,
    /**
     * 本轮任务的可变状态：`java.put` 写的量 `{{k}}` 要立刻读得到，`source.setVariable` 写的整串
     * 要跨这一次调用活下来——两者都住在上下文对象身上，代理拿的是**同一个实例**而不是拷贝。
     * 除变量面（[EvalContext.variables] 与 [EvalContext.sourceVariable]）外本类不读它的其余字段：
     * 页面基准走 [initialPage]/[initialUrl]，嵌套求值的上下文由调用方在 [evaluateNested] 里备好。
     */
    private val ctx: EvalContext,
    /** 关掉 JS 的嵌套求值口：(规则串, 输入) → 结果，由 Task 10 提供实现 */
    private val evaluateNested: (String, RuleValue) -> RuleResult,
    private val limits: JsLimits = JsLimits(),
    /** 日志出口：测试注入收集器；真实现按级别走 Logger（toast 是 info、log 是 debug） */
    private val logSink: (channel: String, message: String) -> Unit = { channel, message ->
        if (channel == "toast") Logger.i(TAG, message) else Logger.d(TAG, message)
    },
    initialPage: String = "",
    initialUrl: String = "",
    /**
     * 源级会话罐：`cookie.getCookie/setCookie/removeCookie` 的落点，与脚本外呼那个客户端挂的
     * 必须是**同一个实例**（两份罐等于「外呼攒下的会话脚本读不到」）。
     * null 表示本次装配没有罐（未接线的构造、单测）——cookie 族按拒绝处置，不静默回空值：
     * 静默空值会让脚本以为「这一站没有会话」而去重登一遍，症状是无限循环登录。
     */
    private val cookies: SourceCookieJar? = null,
) : HostHandler {

    @Volatile
    private var requests = 0

    @Volatile
    private var depth = 0

    @Volatile
    private var currentText = initialPage

    @Volatile
    private var currentUrl = initialUrl

    /** 嵌套求值回传的形态：整份列表还是首个条目（单值时取文本还是外形态由 accessor 定） */
    private enum class NestedShape { LIST, SINGLE }

    override fun handle(api: String, argsJson: String): HostReply {
        // 按 jsName 查而不是按枚举常量名：过边界的是脚本里写的那个名字（`ajax`/`base64Encode`），
        // 常量名是大写的 `AJAX`/`BASE64_ENCODE`，拿 `it.name == api` 比会把每一条真能力都判成
        // 「沙箱里没有」。与 Task 7 的 HostDispatcher 同一口径（白名单第三判，关键事实 3）。
        val capability = JsHostApi.byJsName(api)
            ?: return HostReply(false, null, "沙箱里没有「$api」这个能力")
        if (capability.target == JsApiTarget.COMPUTE) {
            return refuse("「$api」是执行器进程内的纯计算能力，不经主进程")
        }
        // runBlocking：handle 是同步的（HostHandler 契约）而传输层是 suspend。
        // 阻塞的是受理回调的 binder 线程（onTransact 落进来的那一条，不是主线程），
        // IO 在 Dispatchers.IO 上——前提同样是「永不在主线程调沙箱」（Task 6 的主线程守卫）。
        return runCatching { runBlocking { dispatch(capability, argsOf(capability, argsJson)) } }
            .getOrElse { failure ->
                if (failure is JsApiRejectedException) {
                    // 自己写的拒绝话术已经说过人话，原样回，不再套一层类型名
                    refuse(failure.message ?: "能力调用被主进程拒绝")
                } else {
                    refuse(
                        failure.message?.takeIf { it.isNotBlank() }
                            ?.let { "${failure.javaClass.simpleName}：${it.take(120)}" }
                            ?: "主进程代理失败（${failure.javaClass.simpleName}）",
                    )
                }
            }
    }

    /**
     * 能力路由。`suspend` 是必须的：[send] / [probe] 走传输层，而 [handle] 侧的同步契约由
     * `runBlocking` 在这一处桥接（关键事实 1），而不是让 dispatch 自己假装不是挂起函数。
     */
    private suspend fun dispatch(capability: JsHostApi, args: JsonArray): HostReply = when (capability) {
        JsHostApi.AJAX, JsHostApi.LOAD -> {
            // 选项解析刻意在 send 之前完成：webView/proxy/POST-无-body 这类「根本不该发出去」的
            // 参数错误必须发生在准入与计数之前，否则一条写坏的规则就能白烧配额
            val url = args[0].jsonText()
            val options = optionsOf(capability, args.getOrNull(1))
            send(capability.jsName, url, options)
        }

        JsHostApi.POST -> send(capability.jsName, args[0].jsonText(), postOptions(args[1], args.getOrNull(2)))
        JsHostApi.RESPONSE_CODE -> probe(args[0].jsonText())
        JsHostApi.SET_CONTENT -> setContent(args[0].jsonText(), args.getOrNull(1))
        JsHostApi.COOKIE_GET -> HostReply(true, JsonPrimitive(cookieJar().headerFor(args[0].jsonText())), null)
        JsHostApi.COOKIE_SET -> {
            cookieJar().setHeader(args[0].jsonText(), args[1].jsonText())
            HostReply(true, JsonNull, null)
        }

        JsHostApi.COOKIE_REMOVE -> {
            cookieJar().removeFor(args[0].jsonText())
            HostReply(true, JsonNull, null)
        }

        JsHostApi.PUT_VAR -> writeVar(args[0].jsonText(), args[1].jsonText())
        JsHostApi.GET_VAR -> HostReply(
            true,
            ctx.variables[args[0].jsonText()]?.let { JsonPrimitive(it) } ?: JsonNull,
            null,
        )

        // 源级自定义变量：整串进出。空串是「还没写过」的合法答案，不能折成 JsonNull——
        // 脚本判的是 `if (v == "")`，拿到 null 时这一判为假，于是第一次运行就去 parse 一个 null。
        JsHostApi.GET_SOURCE_VARIABLE -> HostReply(true, JsonPrimitive(ctx.sourceVariable), null)
        JsHostApi.SET_SOURCE_VARIABLE -> {
            ctx.sourceVariable = args[0].jsonText()
            HostReply(true, JsonNull, null)
        }

        JsHostApi.REMOVE_VAR -> {
            ctx.variables.remove(args[0].jsonText())
            HostReply(true, JsonNull, null)
        }

        // 上游 putToPage 的落点是「本页缓存」，本仓没有页缓存持久层（装载时 `@cache:` 前缀
        // 记进 ScriptRuleSet.unsupported），故与 putVar 同落任务变量表。
        // 留着这个名字只为让抄来的脚本不撞 ReferenceError。
        JsHostApi.PUT_TO_PAGE -> writeVar(args[0].jsonText(), args[1].jsonText())
        JsHostApi.GET_ELEMENTS -> nested(args[0].jsonText(), NestedShape.LIST, AccessorKind.ALL)
        JsHostApi.GET_ELEMENT -> nested(args[0].jsonText(), NestedShape.SINGLE, AccessorKind.ALL)
        JsHostApi.QUERY_STRING -> nested(args[0].jsonText(), NestedShape.SINGLE, AccessorKind.TEXT)
        JsHostApi.TOAST, JsHostApi.LOG -> {
            logSink(capability.jsName, args[0].jsonText())
            HostReply(true, JsonNull, null)
        }

        else -> refuse("「${capability.jsName}」不由主进程受理")
    }

    // —— 网络：准入 → 限流 → 外呼 → 换基准 ——

    /**
     * 准入与限流都在真正外呼**之前**：被守门器拒掉的请求不该发出去（那是白名单的意义），
     * 也不该烧配额（否则一条写坏的规则会把这一轮的外呼机会吃光，用户看到的却是「请求太多」）。
     */
    private fun admit(api: String, url: String): String {
        val host = guard.check(url)
        if (requests >= limits.maxRequestsPerTask) {
            throw JsApiRejectedException(
                "本轮任务已用满 ${limits.maxRequestsPerTask} 次外呼上限（$api → $host），后续请求被拒",
            )
        }
        requests++
        return host
    }

    private suspend fun send(api: String, url: String, options: ScriptUrlOptions): HostReply {
        val request = requestOf(url, options)
        if (request.body == null && request.method.uppercase(Locale.US) in BODY_REQUIRED_METHODS) {
            // 交给传输层的话会撞 OkHttp 的未类型化 IllegalArgumentException（"method POST must
            // have a request body"），那句话在脚本看来跟「代理坏了」没区别
            throw JsApiRejectedException("「$api $url」声明了 ${request.method} 却没给 body，代理不代发")
        }
        val host = admit(api, url)
        val text = await(api, host) { transport.execute(request) }
        val bytes = utf8Length(text)
        if (bytes > limits.maxHostReplyBytes) {
            // 基准不推进：把「过大」的一页认作当前页，随后的 getElements 就会定位到脚本没见过的页面
            throw JsApiRejectedException(
                "「$api $host」的响应 $bytes 字节，超单次回调上限 ${limits.maxHostReplyBytes}",
            )
        }
        currentText = text
        currentUrl = url
        return HostReply(true, JsonPrimitive(text), null)
    }

    private suspend fun probe(url: String): HostReply {
        val host = admit("responseCode", url)
        val code = await("responseCode", host) { statusProbe(url) }
        return HostReply(true, JsonPrimitive(code), null)
    }

    /**
     * 一次外呼的等待。`withTimeout` 兜的是**没配 timeoutMs 的请求**：`ScriptTransport` 至今不响应
     * 协程取消（阻塞的 `Call.execute()` 没接 `call.cancel()`，取消后仍会跑满整轮重试；该缺口登记在
     * 2c 计划的收尾备注，测试台账里查不到），阻塞中的 OkHttp 调用不会被这里打断，真正的上限是
     * 客户端自己的 connect/read 超时。这条 await 保证的是**受理回调的 binder 线程不被无限期占住**
     * ——否则同一帧后续回调会一直排在这条线程上，后面排队的源跟着一起等。
     */
    private suspend fun <T> await(api: String, host: String, block: suspend () -> T): T =
        try {
            withTimeout(limits.wallClockMs) { block() }
        } catch (e: TimeoutCancellationException) {
            throw JsApiRejectedException(
                "「$api $host」在 ${limits.wallClockMs}ms 内没回结果（超时按请求失败处置）",
            )
        }

    private fun requestOf(url: String, options: ScriptUrlOptions) = ScriptRequest(
        url = url,
        method = options.method,
        headers = options.headers,
        body = options.body,
        charset = options.charset,
        timeoutMs = options.timeoutMs,
        retry = options.retry,
    )

    /**
     * `ajax`/`load` 的第 2 个参数。**裸字符串按 charset 处理**是本仓规定（上游文档没给这一项答案，
     * 而 `java.ajax(url, 'gbk')` 这种写法在语料里真实存在）；对象则整份交给 [ScriptUrlOption.parse]，
     * 与规则尾段 `{...}` **共用同一套键与同一份拒绝集**——两处各解一遍就会长出口径差，
     * 那类差别的症状是「URL 里不认的选项到脚本里就认了」。
     */
    private fun optionsOf(api: JsHostApi, arg: JsonElement?): ScriptUrlOptions = when (arg) {
        null, is JsonNull -> ScriptUrlOptions.DEFAULT
        is JsonPrimitive -> ScriptUrlOptions(charset = arg.jsonText())
        is JsonObject -> ScriptUrlOption.parse(arg, "${api.jsName} 的第 2 个参数")
        else -> throw JsApiRejectedException(
            "「${api.jsName}」的第 2 个参数只能是选项对象或 charset 字符串",
        )
    }

    private fun postOptions(body: JsonElement, headers: JsonElement?): ScriptUrlOptions {
        val extra = headerMap(headers)
        return when (body) {
            is JsonObject -> ScriptUrlOptions(
                method = "POST",
                body = formBody(body),
                // 脚本自己写的头覆盖兜底值：Content-Type 是「表单」这一分支的推定，源要发 JSON 体时
                // 就得连它一起改掉，否则站点按表单解一段 JSON、报一句与入参无关的 400
                headers = mapOf("Content-Type" to FORM_CONTENT_TYPE) + extra,
            )

            // 字符串体不猜 Content-Type：上游与语料在这一支上两种写法都有（JSON 与表单），
            // 猜错的表现是站点回 400 而不是本地报错，比让脚本自己写头更难查
            else -> ScriptUrlOptions(method = "POST", body = body.jsonText(), headers = extra)
        }
    }

    /**
     * `post` 的第 3 个参数（请求头）。对象直接取键值，字符串按 JSON 对象解——语料两种形态都有：
     * 字面量对象与 `JSON.stringify` 出来的文本。解不出对象一律按拒绝：静默丢头的症状是站点回
     * 400/401，而脚本那边看不出自己少发了什么，只会去换密钥。
     */
    private fun headerMap(arg: JsonElement?): Map<String, String> {
        if (arg == null || arg is JsonNull) return emptyMap()
        val fields = when (arg) {
            is JsonObject -> arg
            is JsonPrimitive -> arg.jsonText().trim().takeIf { it.isNotEmpty() }?.let { text ->
                runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            }

            else -> null
        } ?: throw JsApiRejectedException("post 的第 3 个参数只能是请求头对象或其 JSON 文本")
        return fields.mapValues { it.value.jsonText() }
    }

    /**
     * `java.setContent`：把脚本给的一页立为后续规则求值的基准。
     *
     * 存在的理由是 [send] 只把基准推进到「最近一次外呼」的响应，而语料里这一族 31 处调用的写法是
     * 「ajax 取一页 → 存进变量 → 再 setContent 指回去」：等它指回来时基准早已被后一次外呼换掉，
     * 症状是 `getElements` 定位到不相干的那一页、静默解出错内容。
     *
     * 第二参缺席时**不动 URL**——相对地址落位要靠它，清空会让下一步解出错误的绝对 URL。
     * 不查网络、不烧配额：它只是改写已经在手的文本。
     */
    private fun setContent(text: String, url: JsonElement?): HostReply {
        currentText = text
        url?.jsonText()?.takeIf { it.isNotBlank() }?.let { currentUrl = it }
        return HostReply(true, JsonNull, null)
    }

    /**
     * cookie 族的落点。没罐就是没装配（[cookies] 只由 `ScriptBookParser` 那一路给出），
     * 报一句话而不是回空串：空串在脚本看来是「这一站确实没有会话」，于是它会去重登/重取一遍，
     * 症状是登录循环，而根因是装配漏传。
     */
    private fun cookieJar(): SourceCookieJar =
        cookies ?: throw JsApiRejectedException("cookie 面未装配（本次任务没有源级会话罐）")

    /**
     * 表单体：键**按字典序**排，而不是保留 JSON 里的声明顺序。
     *
     * 排序换来的是「同一份脚本、同一次调用永远发同一份请求体」——服务端签名与缓存键常对请求体
     * 字节序敏感，不排序会让偶发的键序变化长成难以复现的「这一源时好时坏」。代价是极少数按键序
     * 区分语义的站点解不对，那种源要的是手写 body，本来就不走这一支。
     */
    private fun formBody(fields: JsonObject): String =
        fields.entries.sortedBy { it.key }.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value.jsonText())}"
        }

    /** 用 `encode(String, String)` 重载：`Charset` 版在低 API 上不保证可用 */
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    // —— 递归规则求值 ——

    /**
     * 把一条规则串交回解释器，输入是当前页面。
     *
     * [shape] 与 [accessor] 一起定形态：`getElements` 是「全部条目 × 外形态 HTML」——脚本拿到条目
     * 后最常见的动作是再定位一次，压成纯文本就选不中了；`getElement` 同样给外形态但只回首个；
     * `queryString` 要的是值，所以取纯文本。文本/JSON 形态的结果本身已是值，accessor 不去假装影响它们。
     *
     * 「解不动」（[evaluateNested] 抛类型化异常）与「没命中」（`Miss`）分开：前者回 `ok=false` 带原文，
     * 后者回空数组 / null。把前者折叠成空等于对脚本撒谎，也就让它那句 `if (res.length > 0)` 悄悄走了
     * 另一支——与 2b 定下的「未取到值必须是 Miss」是同一条口径的两端。
     */
    private fun nested(rule: String, shape: NestedShape, accessor: AccessorKind): HostReply {
        if (depth >= limits.maxCallbackDepth) {
            throw JsApiRejectedException(
                "嵌套规则求值已到深度上限 ${limits.maxCallbackDepth}：嵌套求值不得再要 JS（规格 §10 能力矩阵 `@js:` 行）",
            )
        }
        depth++
        val result = try {
            evaluateNested(rule, RuleValue.Page(currentText, currentUrl))
        } finally {
            depth--
        }
        val texts = result.toTextList(accessor)
        return when (shape) {
            NestedShape.LIST -> HostReply(true, JsonArray(texts.map { JsonPrimitive(it) }), null)
            NestedShape.SINGLE -> HostReply(true, texts.firstOrNull()?.let { JsonPrimitive(it) } ?: JsonNull, null)
        }
    }

    // —— 变量与兜底 ——

    private fun writeVar(key: String, value: String): HostReply {
        ctx.variables[key] = value
        return HostReply(true, JsonNull, null)
    }

    private fun refuse(message: String): HostReply = HostReply(false, null, message)

    /**
     * 实参解码 + 元数校验。
     *
     * 元数在这里**再判一次**不是重复垫片的活：垫片里的 arity 检查只在走 `java.xxx` 时生效，
     * 而 `__host_call` 是 globalThis 上的普通属性，脚本可以直接调它（Task 7 为此让 `HostDispatcher`
     * 也判一遍表名）。不判元数的后果是 `args[1]` 抛 `IndexOutOfBoundsException`，回给脚本的是
     * 「主进程代理失败」这种把作者引向错误方向的话。
     */
    private fun argsOf(api: JsHostApi, argsJson: String): JsonArray {
        val parsed = runCatching { Json.parseToJsonElement(argsJson) }.getOrNull()
        if (parsed !is JsonArray) {
            throw JsApiRejectedException("「${api.jsName}」的实参不是 JSON 数组：${argsJson.take(60)}")
        }
        if (parsed.size < api.minArgs || parsed.size > api.maxArgs) {
            throw JsApiRejectedException(
                "「${api.jsName}」参数个数不符（需要 ${api.minArgs} 到 ${api.maxArgs} 个，实到 ${parsed.size}）",
            )
        }
        return parsed
    }
}

/**
 * `responseCode` 的落点：一次 HEAD 探测，只看状态码。
 *
 * 与上游的差别（规格 §11-30，本仓规定）：上游读的是「上一次请求留在那儿的状态码」，那需要一个
 * 跨回调存活的状态槽。这里改成「对目标 URL 探一次」——脚本要状态码，问的就是「这个地址能不能访问」，
 * 而无状态意味着它不受回调顺序影响。准入与限流复用同一条 [admit]（在代理那一侧），所以探测既出不了
 * 白名单，也不比取页多花一次配额。
 *
 * 非 2xx 不是异常：状态码本身就是脚本要的答案。网络层真失败时 `IOException` 原样往上走，由代理
 * 换成 `ok=false`，与「真实网络失败」同一条路径。
 */
internal class HeadStatusProbe(private val client: OkHttpClient) {

    suspend fun status(url: String): Int = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).method("HEAD", null).build()
        client.newCall(request).execute().use { response -> response.code }
    }
}

/**
 * 主进程侧「带守门的网络」的唯一构造点。
 *
 * `newBuilder()` 共享连接池与线程池、只换 DNS 与 cookie 罐。取体与探测**必须用同一份派生客户端**：Task 5 把
 * 地址判定挂在 OkHttp 真正解析的那一次上（不留 DNS 重绑窗口），若两条路径各建各的客户端，
 * 就会出现「探测放行、取体被拒」这种半截防线。
 *
 * [cookies] 是同一份理由的 cookie 版本：脚本外呼要能自动攒下会话（否则 `cookie.getCookie` 永远读到空串），
 * 而攒下的那一份必须就是脚本显式读写的那一份——由调用方（`ScriptBookParser`）持罐、在这里挂进客户端。
 * 不传即维持「不带 cookie」的原状：调用点只有 `sandboxGateway` 与 `HeadStatusProbe` 两处，
 * 两处漏一处就长成「会话在取体那侧有、在探测那侧没有」。
 *
 * 传入的 `base` 必须是 `@Named("source")` 那个纯净客户端——第三方站点永远拿不到用户 token。
 */
internal object GuardedNetwork {

    fun clientFor(
        base: OkHttpClient,
        guard: JsNetworkGuard,
        cookies: SourceCookieJar? = null,
    ): OkHttpClient = base.newBuilder()
        .dns(GuardedDns(guard))
        .apply { cookies?.let { cookieJar(it) } }
        .build()
}
