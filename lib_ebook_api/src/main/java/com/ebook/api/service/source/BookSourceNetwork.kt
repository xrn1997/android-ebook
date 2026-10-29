package com.ebook.api.service.source

import com.ebook.api.entity.BookSourceRule
import okhttp3.OkHttpClient

/**
 * 书源网络实现
 *
 * 取回 **ResponseBody 原始字节**后按 `rule.charset` 显式解码，绝不能先经
 * `body.string()`：`@Named("source")` 客户端的 `EncodingInterceptor` 会把
 * Content-Type 强改成 UTF-8，先解一次再回解会让 gbk 站点全部乱码
 * （docs/architecture.md §6.3；脚本侧同口径见 `OkHttpScriptTransport`）。
 */
class BookSourceNetwork(
    private val rule: BookSourceRule,
    okHttpClient: OkHttpClient
) : BookSourceDataSource {

    private val service = BookSourceService.create(rule, okHttpClient)
    private val headers = BookSourceService.buildHeaders(rule)

    /**
     * 逻辑地址 → 本次请求的绝对地址。
     *
     * 搜索/分类页把它传给字段解析，作为「相对链接写在哪页」的基准——**必须与
     * [getPage] 真正发出的地址同源**，否则解析出的书目/封面会与站点实际响应页错位。
     * 判据与 `TocPageUrl.join` 对齐（docs/specs/book-source-rule-grammar.md §6.5）：
     * `http(s)://` 原样（大小写都算，`httpfoo/…` 这种路径不是协议）、`//host/path` 协议相对
     * 取**源根**的 scheme、其余按书源根拼接。本方法只负责「源根拼接」这一层——它是 URL
     * **规则**的落点，此刻还没有「当前页」，所以不存在按页目录相对那一支。
     */
    fun absoluteUrl(url: String): String {
        val root = rule.url.trimEnd('/')
        return when {
            url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) -> url
            // 源根没有 scheme 时按网页惯例回落 https（与 join 的协议相对分支同口径）
            url.startsWith("//") -> "${root.substringBefore("://", "").ifEmpty { "https" }}:$url"
            else -> "$root/${url.trimStart('/')}"
        }
    }

    override suspend fun getPage(url: String, method: String, body: String): String {
        val fullUrl = absoluteUrl(url)

        val requestMethod = method.ifEmpty { rule.method }
        val requestBody = body.ifEmpty { rule.body }

        val responseBody = if (requestMethod.uppercase() == "POST" && requestBody.isNotEmpty()) {
            service.postPage(fullUrl, headers, BookSourceService.buildRequestBody(rule, requestBody))
        } else {
            service.getPage(fullUrl, headers)
        }

        return responseBody.use { BookSourceService.decode(it.bytes(), rule.charset) }
    }

    override fun getCurrentRule(): BookSourceRule = rule
}
