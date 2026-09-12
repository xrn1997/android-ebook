package com.ebook.api.intercepter

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import java.io.IOException

/**
 * 编码拦截器：书源响应体的 Content-Type 由第三方站点给出，缺 charset 或声明错误时
 * 阅读端会按错误编码解码中文正文。本拦截器把响应体 contentType 强制改写为
 * application/rss+xml;charset=<encoding>，正文流式透传不缓冲。
 *
 * 历史实现经反射改写 OkHttp 私有字段 RealResponseBody.contentTypeString：该字段名
 * 不在任何兼容性承诺内，OkHttp 升级会改名、R8 混淆会重命名，任一发生都会让
 * getDeclaredField 抛 NoSuchFieldException → IOException → 全部书源请求失败。
 * 现改为 OkHttp 公开 API 等价实现（source 包装 + newBuilder），无需任何 keep 规则。
 */
class EncodingInterceptor(
    /**
     * 自定义编码
     */
    private val encoding: String
) : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val forcedType = "application/rss+xml;charset=$encoding".toMediaTypeOrNull()
        return response.withForcedContentType(forcedType)
    }
}

/**
 * 用公开 API 改写响应体的 Content-Type，不触碰 OkHttp 私有字段。
 *
 * 原理：`ResponseBody.source()` 返回原始 `BufferedSource`，用它配合新 contentType
 * 构造一个全新 ResponseBody（`asResponseBody(MediaType?)` 是 OkHttp 5 的公开扩展函数，
 * `@JvmName("create")`，contentLength 默认为 -1）。再用 `response.newBuilder()
 * .body(...).build()` 替换响应体，其余字段（code / headers / protocol）原样透传。
 *
 * contentLength 固定为 -1（未知），不传递原始值，避免下游因已知长度触发全量缓冲。
 * 书源响应经本拦截器后一律按流式读取处理。
 */
internal fun Response.withForcedContentType(contentType: MediaType?): Response {
    val body = body
    val newBody = body.source().asResponseBody(contentType)
    return newBuilder().body(newBody).build()
}
