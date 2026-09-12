package com.ebook.source.script

import com.ebook.source.sandbox.HostHandler
import com.ebook.source.sandbox.JsCallbackProxy
import com.ebook.source.sandbox.JsInvocation
import com.ebook.source.sandbox.JsLimits
import com.ebook.source.sandbox.JsMode
import com.ebook.source.sandbox.JsNetworkGuard
import com.ebook.source.sandbox.JsOutcome
import com.ebook.source.sandbox.JsStatus
import com.ebook.source.sandbox.SourceCookieJar
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * [ScriptJsBridge] 的真实现：把规则层的五种载荷拼成能跑的脚本、送进沙箱、把回帧解成规则层的值。
 *
 * 三个入口参数各管一件事，缺一不可：
 * - [executeTask] 是唯一的外呼口（真接线 = `JsSandboxHost::call`：挂代理 + 走客户端）。
 *   收函数而不是收 `JsSandboxHost` 对象，是为了让这层最要紧的**包装与解包口径**能在 JVM 上测透
 *   （跨进程那部分已经由 Task 6 的假通道测过，这里再测一遍是重复）。
 * - [ctx] 现取绑定：`baseUrl`/`page` 会随翻页推进，构造时快照会在第二页拿到第一页的基准。
 * - [guard]/[transport] 交给每次调用新建的代理——沙箱里的 `java.ajax` 走的是**主进程代发**，
 *   必须过同一套白名单与限流，而不是执行器自己发（隔离进程根本没有网络）。
 *
 * 模式与 wrapper 的关系见计划开头第 3 条关键事实：`mode` 只是帧上的诊断字段，真正决定
 * 「脚本看见哪些变量、结果怎么取」的是下面拼出的文本。`staticBindings` 里的 `null` 值原样过边界，
 * 垫片把缺失与 null 都落成 `undefined`。
 */
internal class SandboxScriptJs(
    private val executeTask: (HostHandler, JsInvocation) -> JsOutcome,
    private val ctx: EvalContext,
    private val guard: JsNetworkGuard,
    private val transport: ScriptTransport,
    private val staticBindings: Map<String, String?> = emptyMap(),
    private val limits: JsLimits = JsLimits(),
    /** `responseCode` 的探测口。默认值只在未接线的构造里存在，被调到就说明装配漏传了 */
    private val statusProbe: suspend (String) -> Int = {
        throw JsApiRejectedException("responseCode 探测未接线（装配时未提供守门客户端）")
    },
    /**
     * 源级会话罐：`cookie.getCookie/setCookie/removeCookie` 的落点。
     *
     * 由装配方（`ScriptBookParser`）持有，且与 [transport] 那条客户端挂的必须是同一份——两份罐的形态是
     * 「脚本外呼攒下了会话，`cookie.getCookie` 却读到空串」。默认 null 只存在于未接线的构造（单测、
     * 没装沙箱的路径），被 cookie 族调到会报一句「cookie 面未装配」。
     */
    private val cookies: SourceCookieJar? = null,
) : ScriptJsBridge {

    private companion object {
        /**
         * `js` 选项：上游给脚本的是两个可写变量，成品从 `url`/`headers` 取（不是完成值）。
         *
         * 它与 `@js:` 段（[runSegment]）的**变量集差别**就在这段包装里，而绑定表是同一份
         * （见 [bindingsOf]）：两者都拿到 `baseUrl`/`key`/`page` 与静态的 `bookJson`/`chapterJson`
         * （null 值由垫片落成 `undefined`）；`js` 选项额外多出 `url` 与 `headersJson` 并被提升成
         * 可写的 `url`/`headers`，代价是它的 `result` 落成 `undefined`（选项改写的是地址，
         * 手边没有页面正文）；`@js:` 段反过来有 `result`（= 当前输入页面）而没有 `url`——
         * 那一刻还没有「要去请求的那个地址」。所以「脚本在两种位置读到同一个名字」并不总成立。
         */
        const val URL_JS_HEAD =
            "var url = __bindings.url;\nvar headers = JSON.parse(__bindings.headersJson);\n"

        /** 括号是必须的：`{...}` 在语句位置会被读成代码块 */
        const val URL_JS_TAIL = "\nresult = ({url: url, headers: headers});"

        /**
         * `bodyJs` 取的是**变量**而不是最后一条语句的值：上游这类脚本的写法就是改 `result`，
         * 而它的最后一行常常是一个 `if` 或赋值语句，完成值口径会让人摸不到结果。
         */
        const val BODY_JS_TAIL = "\n;result"
    }

    override fun runSegment(source: String, input: RuleValue): RuleResult =
        when (val data = requireOk(call(JsMode.SEGMENT, source, input, null), "`@js:` 段").data) {
            null, is JsonNull -> RuleResult.Miss
            is JsonPrimitive -> RuleResult.Texts(listOf(data.content))
            // 数组是 `@js:` 常见的多值产出（一次切出好几段正文），逐标量展开；
            // 数组里裹对象则整段按没取到值处置——拼成 JSON 文本会得到一条「看着像正文的垃圾」
            is JsonArray -> {
                val texts = data.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p != JsonNull }?.content }
                if (texts.isEmpty()) RuleResult.Miss else RuleResult.Texts(texts)
            }
            // 单对象按 JSON 文本回填：`init` 之外的字段确实有作者这么写（把结构塞进一个字段），
            // 而 §6.3 已规定对象/数组以 JSON 文本形态使用，此处不另立口径
            is JsonObject -> RuleResult.Texts(listOf(data.toString()))
        }

    override fun runExpression(expr: String): String {
        val data = requireOk(call(JsMode.EXPRESSION, expr, null, null), "表达式「$expr」").data
        return when {
            // 「跑成了但没值」按空串展开（§9「未取到值 → 空串」，与 [runSegment] 的 undefined→Miss 同源）。
            // 语料里这类表达式的本职是**副作用**（`java.put('key',key)` 的完成值恒为 undefined），
            // 按失败报会让整条规则因为一个没打算回值的表达式而崩掉。
            data == null || data is JsonNull -> ""
            data is JsonPrimitive -> data.content
            // 对象/数组拼成文本会得到一段「看着像 URL 片段的 JSON」，比崩掉更难查，仍按失败报
            else -> throw JsExecutionFailedException(
                JsStatus.RUNTIME,
                "表达式「${expr.take(60)}」回的是${shapeOf(data)}，无法作为文本回填",
            )
        }
    }

    override fun runUrlJs(script: String, url: String, headers: Map<String, String>): JsRewrittenUrl {
        val data = requireOk(
            call(JsMode.URL_OPTION, URL_JS_HEAD + script + URL_JS_TAIL, null, url, headers),
            "URL 选项 js",
        ).data
        val obj = data as? JsonObject
            ?: throw JsExecutionFailedException(
                JsStatus.RUNTIME,
                "URL 选项 js 必须改写 url/headers 变量，实到 ${shapeOf(data)}",
            )
        val newUrl = (obj["url"] as? JsonPrimitive)
            ?.takeIf { it != JsonNull && it.content.isNotBlank() }
            ?.content
            ?: throw JsExecutionFailedException(
                JsStatus.RUNTIME,
                "URL 选项 js 没有给出 url（不回退原地址：那会发出一个作者不想要的请求）",
            )
        return JsRewrittenUrl(
            url = newUrl,
            // **合并**而不是整体替换：真运行时里 `headers` 就是从原请求头 parse 出来的全量对象，
            // 脚本改完回传时原有键天然还在，合并与替换同形；保护的是「只回传增量」那种形态
            // （手写包装、或内核把对象压小的边界）——替换会静默删掉 User-Agent/Content-Type 整族头，
            // 症状是「加了 js 选项之后站点回 403」而规则一个字没删。同名键以脚本侧为准
            headers = (obj["headers"] as? JsonObject)?.let { headers + jsonHeaders(it) } ?: headers,
        )
    }

    override fun runBodyJs(script: String, url: String, body: String): String {
        // 响应体同时是 `result` 绑定与代理的页面基准：脚本在这里 `java.getElements` 取的就是这份响应
        val data = requireOk(
            call(JsMode.BODY_JS, script + BODY_JS_TAIL, RuleValue.Texts(listOf(body)), url),
            "URL 选项 bodyJs",
        ).data
        return (data as? JsonPrimitive)
            ?.takeIf { it != JsonNull }
            ?.content
            ?: throw JsExecutionFailedException(
                JsStatus.RUNTIME,
                "bodyJs 必须把新正文写回 result 变量，实到 ${shapeOf(data)}",
            )
    }

    override fun runInit(script: String, input: RuleValue): Map<String, String> {
        val data = requireOk(call(JsMode.INIT, script, input, null), "init 的 JS").data
        val obj = data as? JsonObject
            ?: throw JsExecutionFailedException(
                JsStatus.RUNTIME,
                "init 的 JS 必须回传对象（字段名=键），实到 ${shapeOf(data)}",
            )
        return obj.entries.associate { (key, value) -> key to scalarText(value) }
    }

    /**
     * 一次调用 = 一个新代理（理由见计划开头第 4 条关键事实）。
     * [url] 非空时它既是绑定里的 `url`，也是代理推进 `baseUrl` 的初值——脚本在 `js` 选项里
     * 递归取元素时，相对地址应当相对**这次要请求的那个地址**，而不是页面基准。
     */
    private fun call(
        mode: JsMode,
        source: String,
        input: RuleValue?,
        url: String?,
        headers: Map<String, String> = emptyMap(),
    ): JsOutcome {
        val proxy = JsCallbackProxy(
            guard = guard,
            transport = transport,
            statusProbe = statusProbe,
            ctx = ctx,
            evaluateNested = { rule, nestedInput ->
                ScriptRuleEvaluator(ctx.withoutJs()).evaluate(rule, nestedInput)
            },
            limits = limits,
            initialPage = input?.let(::textOf).orEmpty(),
            initialUrl = url ?: ctx.baseUrl,
            cookies = cookies,
        )
        return executeTask(proxy, JsInvocation(mode, source, bindingsOf(input, url, headers)))
    }

    /**
     * 静态绑定在前、当下实现在后：`bookJson`/`chapterJson` 由解析层给出（本阶段只有 book 有值），
     * 五个提升名每次调用现取。值为 null 表示「本场景没有这个量」，垫片落成 `undefined`。
     */
    private fun bindingsOf(input: RuleValue?, url: String?, headers: Map<String, String>): Map<String, String?> =
        staticBindings + mapOf(
            "result" to input?.let(::textOf),
            "baseUrl" to ctx.baseUrl,
            "key" to ctx.key,
            "page" to ctx.page.toString(),
            "src" to null,
            "title" to null,
            "url" to url,
            "headersJson" to if (url == null) null else JsonObject(headers.entries.associate { (k, v) -> k to JsonPrimitive(v) }).toString(),
        )

    /** 把任意输入收敛成脚本侧的 `result` 文本。做不到的形态回 null（undefined），不硬凑空串 */
    private fun textOf(input: RuleValue): String? = when (input) {
        is RuleValue.Page -> input.source
        is RuleValue.Texts -> input.values.firstOrNull()
        is RuleValue.Nodes -> if (input.elements.isEmpty()) null else input.elements.joinToString("\n") { it.outerHtml() }
        is RuleValue.Json -> input.element.toString()
    }

    private fun requireOk(outcome: JsOutcome, what: String): JsOutcome {
        if (!outcome.isSuccess) {
            throw JsExecutionFailedException(outcome.status, outcome.error ?: "$what 未给出失败原因")
        }
        return outcome
    }

    private fun jsonHeaders(obj: JsonObject): Map<String, String> =
        obj.entries.mapNotNull { (key, value) ->
            if (value == JsonNull) null else key to scalarText(value)
        }.toMap()

    private fun scalarText(value: JsonElement): String = when {
        value == JsonNull -> ""
        value is JsonPrimitive -> value.content
        else -> value.toString()
    }

    private fun shapeOf(data: JsonElement?): String = when (data) {
        null -> "undefined"
        is JsonNull -> "null"
        is JsonPrimitive -> "标量"
        is JsonArray -> "数组"
        is JsonObject -> "对象"
    }
}
