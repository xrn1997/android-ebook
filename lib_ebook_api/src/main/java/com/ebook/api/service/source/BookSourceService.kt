package com.ebook.api.service.source

import com.ebook.api.entity.BookSourceRule
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * 书源 HTTP 服务接口
 * 使用协程，支持动态 URL
 */
interface BookSourceService {
    @GET
    suspend fun getPage(
        @Url url: String,
        @HeaderMap headers: Map<String, String>
    ): String

    @POST
    suspend fun postPage(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @Body body: okhttp3.RequestBody
    ): String

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /**
         * 创建 BookSourceService 实例
         * 使用传入的 OkHttpClient 支持动态 URL
         */
        fun create(rule: BookSourceRule, okHttpClient: OkHttpClient): BookSourceService {
            return Retrofit.Builder()
                .baseUrl(rule.url)
                .addConverterFactory(ScalarsConverterFactory.create())
                .client(okHttpClient)
                .build()
                .create(BookSourceService::class.java)
        }

        /**
         * 构建请求头
         */
        fun buildHeaders(rule: BookSourceRule): Map<String, String> {
            val headers = mutableMapOf<String, String>()
            headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            headers["Accept-Language"] = "zh-CN,zh;q=0.9,en;q=0.8"
            headers["Cache-Control"] = "no-cache"

            // 添加自定义请求头
            rule.headers.forEach { (key, value) ->
                headers[key] = value
            }

            // 默认 User-Agent
            if (!headers.containsKey("User-Agent")) {
                headers["User-Agent"] = DEFAULT_USER_AGENT
            }

            return headers
        }

        /**
         * 构建请求体
         */
        fun buildRequestBody(rule: BookSourceRule, body: String): okhttp3.RequestBody {
            val mediaType = "application/x-www-form-urlencoded; charset=${rule.charset}".toMediaType()
            return body.toRequestBody(mediaType)
        }

        /**
         * 处理字符编码
         */
        fun handleCharset(responseBody: String, charset: String): String {
            return if (charset.lowercase() != "utf-8") {
                val bytes = responseBody.toByteArray(Charsets.ISO_8859_1)
                String(bytes, charset(charset))
            } else {
                responseBody
            }
        }
    }
}
