package com.ebook.source.script

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/** 一次取文请求的纯数据形态：与传输实现解耦，测试与 2d 的假件都只打它 */
internal data class ScriptRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val charset: String = "UTF-8",
    val timeoutMs: Long? = null,
    val retry: Int = 0,
)

/**
 * 传输接缝：执行请求并返回**已按选项 charset 解码**的响应文本。
 *
 * 独立成接口的理由：`mockwebserver` 不在版本目录，OkHttp 侧行为留给 2d 金标准
 * fixtures + 装机验证；单测（本段）与 2d 的解析器测试都用假件打这个接缝，
 * 断言「请求长什么样」而不是「HTTP 栈怎么发」。
 */
internal fun interface ScriptTransport {
    suspend fun execute(request: ScriptRequest): String
}

/**
 * OkHttp 实现。三个刻意的细节：
 * 1. **显式字节解码**：响应按 `bytes()` 取回再 `String(bytes, charset)`——绝不走
 *    `body.string()`。`@Named("source")` 客户端挂着 `EncodingInterceptor("UTF-8")`
 *    把 Content-Type 强改成 UTF-8，`string()` 会照它解码，gbk 站点全部乱码（§6.2/§10）。
 * 2. **timeout 是每请求派生**：`newBuilder()` 共享连接池，只有配了 `timeout` 的请求
 *    才付出派生客户端的代价。
 * 3. **非 2xx 按 IOException 参与重试**（§6.2 `retry` 是重试次数，总尝试 = retry + 1）。
 */
internal class OkHttpScriptTransport(private val client: OkHttpClient) : ScriptTransport {

    override suspend fun execute(request: ScriptRequest): String = withContext(Dispatchers.IO) {
        val target = request.timeoutMs
            ?.let { client.newBuilder().readTimeout(it, TimeUnit.MILLISECONDS).build() }
            ?: client
        var lastError: IOException? = null
        repeat(request.retry.coerceAtLeast(0) + 1) {
            try {
                return@withContext executeOnce(target, request)
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("请求失败：${request.url}")
    }

    private fun executeOnce(client: OkHttpClient, request: ScriptRequest): String {
        val charset = charsetOf(request.charset)
        val http = try {
            Request.Builder()
                .url(request.url)
                .apply { request.headers.forEach { (k, v) -> header(k, v) } }
                // body 的 Content-Type 由 headers 显式给出（§6.3：文档未规定默认头），
                // 这里不传 MediaType，避免与 headers 里的声明打架
                .method(request.method.uppercase(), request.body?.toRequestBody())
                .build()
        } catch (e: IllegalArgumentException) {
            // 非法 URL、GET+body 一类写坏：OkHttp 抛未类型化的 IllegalArgumentException，
            // 换成类型化错误如实报（§12「失败要如实报」）
            throw RuleSyntaxException("${request.method} ${request.url}")
        }
        // 显式 return：块体函数声明了返回类型时须显式 return（标准 Kotlin 语义，
        // 尾表达式不做隐式返回），原写法编译不过
        return client.newCall(http).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}：${request.url}")
            String(response.body.bytes(), charset)
        }
    }

    private fun charsetOf(name: String): Charset =
        runCatching { Charset.forName(name) }.getOrElse {
            // 作者写错的 charset 是规则值错误，不是「本仓不支持」——按语法错误报
            throw RuleSyntaxException("URL 选项 charset 不可识别：$name")
        }
}

/** 一次取文的产物：请求落点的绝对地址 + 已按选项 charset 解码的响应文本（2d 加，供字段相对落位与翻页推进） */
internal data class ScriptPage(val url: String, val text: String)

/**
 * 取文门面：URL 规则串 → 解析（插值/页码/落位/选项）→ 请求 → 响应文本。
 *
 * 2d 的 `BookParser` 实现按字段调它；`evaluateInner` 把 `@@规则` 插值接到求值器
 * （与 [ScriptRuleEvaluator] 的递归口同形）。`okHttpClient` 允许 null 仅因为
 * 测试总以假 transport 构造——生产接线（2d 的 DI）必须传真客户端。
 * 两者同时给出时 [transport] 优先生效，`okHttpClient` 被忽略。
 */
internal class ScriptPageFetcher(
    okHttpClient: OkHttpClient?,
    private val sourceRoot: String,
    private val transport: ScriptTransport = OkHttpScriptTransport(okHttpClient ?: error("okHttpClient 未注入")),
) {

    /** 2c 形状的薄壳：只回响应文本；需要请求落点地址的调用方（2d 装配层）走 [fetchPage] */
    suspend fun fetch(
        ruleUrl: String,
        ctx: EvalContext,
        evaluateInner: (String) -> RuleResult,
    ): String = fetchPage(ruleUrl, ctx, evaluateInner).text

    /**
     * 取文并回传**请求落点地址**：调用方需要它做两件事——字段结果的相对落位基准（§6.5）与
     * 翻页循环的 `ctx.baseUrl` 推进。`fetch` 不回传地址是 2c 的形状（当时没有消费者），
     * 2d 起装配层一律走本方法。
     *
     * URL 选项 `js`/`bodyJs` 在这里执行（而不是在 [ScriptUrlOption.parse] 里）：解析期只识别不执行，
     * 于是 `a||b` 的兜底支不会被前置副作用污染，「本机没沙箱」也报在同一处。
     */
    suspend fun fetchPage(
        ruleUrl: String,
        ctx: EvalContext,
        evaluateInner: (String) -> RuleResult,
    ): ScriptPage {
        val bridge = ctx.js
        var resolved = ScriptUrlResolver.resolve(ruleUrl, ctx, sourceRoot, evaluateInner)
        resolved.options.js?.let { script ->
            // 没沙箱时在这里抛待执行：选项解析只识别不执行（见 ScriptUrlOption），
            // 于是「跑 js」与「报没沙箱」都收在这一处，且发生在发出请求之前——不会有半个请求的副作用
            val rewritten = (bridge ?: throw JsEvaluationPendingException(script))
                .runUrlJs(script, resolved.url, resolved.options.headers)
            resolved = resolved.copy(
                url = rewritten.url,
                options = resolved.options.copy(headers = rewritten.headers),
            )
        }
        val text = transport.execute(
            ScriptRequest(
                url = resolved.url,
                method = resolved.options.method,
                headers = resolved.options.headers,
                body = resolved.options.body,
                charset = resolved.options.charset,
                timeoutMs = resolved.options.timeoutMs,
                retry = resolved.options.retry,
            ),
        )
        val bodyJs = resolved.options.bodyJs
        return ScriptPage(
            url = resolved.url,
            text = bodyJs?.let { script -> (bridge ?: throw JsEvaluationPendingException(script)).runBodyJs(script, resolved.url, text) }
                ?: text,
        )
    }
}
