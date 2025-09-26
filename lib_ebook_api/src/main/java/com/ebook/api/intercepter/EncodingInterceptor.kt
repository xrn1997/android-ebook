package com.ebook.api.intercepter

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException


/**
 * 编码拦截器
 */
class EncodingInterceptor(
    /**
     * 自定义编码
     */
    private val encoding: String
) : Interceptor {
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request: Request = chain.request()

        val response: Response = chain.proceed(request)
        settingClientCustomEncoding(response)
        return response
    }

    /**
     * setting client custom encoding when server not return encoding
     */
    @Throws(IOException::class)
    private fun settingClientCustomEncoding(response: Response) {
        setBodyContentType(response)
    }


    /**
     * set body contentType
     */
    @Throws(IOException::class)
    private fun setBodyContentType(response: Response) {
        val body = response.body ?: return
        // setting body contentTypeString using reflect
        val aClass: Class<out ResponseBody> = body.javaClass
        try {
            val field = aClass.getDeclaredField("contentTypeString")
            field.isAccessible = true
            field[body] = "application/rss+xml;charset=$encoding"
        } catch (e: NoSuchFieldException) {
            throw IOException("use reflect to setting header occurred an error", e)
        } catch (e: IllegalAccessException) {
            throw IOException("use reflect to setting header occurred an error", e)
        }
    }
}