package com.ebook.api.service.source

import com.ebook.api.entity.BookSourceRule
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * 书源 HTTP 服务接口
 *
 * **返回 `ResponseBody` 而不是 `String`**：`@Named("source")` 客户端挂着
 * `EncodingInterceptor("UTF-8")`，`body.string()` 会按被强改的 Content-Type 解码，
 * gbk 站点在第一次解码时就已损坏。取文层必须拿原始字节、按 `rule.charset` 显式解码
 * （见 [BookSourceNetwork] 与 docs/architecture.md §6.3）。
 */
interface BookSourceService {
    @Streaming
    @GET
    suspend fun getPage(
        @Url url: String,
        @HeaderMap headers: Map<String, String>
    ): ResponseBody

    @Streaming
    @POST
    suspend fun postPage(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @Body body: okhttp3.RequestBody
    ): ResponseBody

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /**
         * 创建 BookSourceService 实例
         * 使用传入的 OkHttpClient 支持动态 URL
         *
         * **baseUrl 只用于 Retrofit 的构造校验**（真正的地址由 `@Url` 逐请求给出），
         * 但 Retrofit 3 要求它必须以 `/` 结尾。用户导入的源地址可以带路径且没有尾斜杠
         * （如 `https://host/books`），不归一化会在 parser 构造期抛未类型化的
         * `IllegalArgumentException`，而不是落到 `SourceBinding.Missing/Broken`。
         */
        fun create(rule: BookSourceRule, okHttpClient: OkHttpClient): BookSourceService {
            val baseUrl = rule.url.trimEnd('/') + "/"
            return Retrofit.Builder()
                .baseUrl(baseUrl)
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
         * 按书源声明显式解码原始字节。
         *
         * **不要改回 `String(bytes, ...)` 之前的任何 `body.string()` 二次解码**：
         * 客户端已经用 `EncodingInterceptor` 改写了 Content-Type，先解一次再回解救不回
         * 非 UTF-8 站点的原始字节。charset 名非法时让 `Charset.forName` 的原异常上抛，
         * 由解析链路按「这条规则解不动」处置；原生导入不预检 charset，脚本侧对不可识别的
         * charset 按规则语法错报。
         */
        fun decode(bytes: ByteArray, charset: String): String = String(bytes, charset(charset))
    }
}
