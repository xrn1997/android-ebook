package com.ebook.api.service.source

import com.ebook.api.entity.BookSourceRule
import okhttp3.OkHttpClient

/**
 * 书源网络实现
 * 使用 BookSourceService 进行 HTTP 请求
 */
class BookSourceNetwork(
    private val rule: BookSourceRule,
    okHttpClient: OkHttpClient
) : BookSourceDataSource {

    private val service = BookSourceService.create(rule, okHttpClient)
    private val headers = BookSourceService.buildHeaders(rule)

    override suspend fun getPage(url: String, method: String, body: String): String {
        val fullUrl = if (url.startsWith("http")) url else "${rule.url.trimEnd('/')}/${url.trimStart('/')}"

        val requestMethod = method.ifEmpty { rule.method }
        val requestBody = body.ifEmpty { rule.body }

        val responseBody = if (requestMethod.uppercase() == "POST" && requestBody.isNotEmpty()) {
            service.postPage(fullUrl, headers, BookSourceService.buildRequestBody(rule, requestBody))
        } else {
            service.getPage(fullUrl, headers)
        }

        return BookSourceService.handleCharset(responseBody, rule.charset)
    }

    override fun getCurrentRule(): BookSourceRule = rule
}
