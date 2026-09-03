package com.ebook.api

import dagger.Lazy
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Retrofit 构建器
 *
 * 设计原则：
 * - 使用 lib_common 共享的 `Call.Factory`（AuthInterceptor + 白名单 + debug 脱敏日志），
 *   不再自建 OkHttpClient（见 ADR-0014）；`dagger.Lazy` 防 Hilt 循环依赖
 * - 每个 Network 类可以创建自己的 Retrofit 实例
 * - JSON 解析显式注册 kotlinx 转换器（复用 Hilt 的 [Json] 配置，与 mock 链路同一套），
 *   Retrofit 3.x 不内置 JSON 转换器，只挂 Scalars 时 @Serializable 返回类型无法解析，
 *   真实后端一接即崩（此前仅靠 mock 链路开发未暴露）
 */
@Singleton
class RetrofitBuilder @Inject constructor(
    private val okhttpCallFactory: Lazy<Call.Factory>,
    private val networkJson: Json
) {
    /**
     * 获取 Retrofit 对象
     *
     * @param url 基础 URL
     */
    fun getRetrofitObject(url: String): Retrofit {
        return Retrofit.Builder()
            .baseUrl(url)
            // kotlinx 转换器优先：复用注入的 Json（ignoreUnknownKeys 等），与 mock 链路配置一致；
            // Scalars 殿后处理 String 返回类型（如书源 HTML）
            .addConverterFactory(networkJson.asConverterFactory("application/json".toMediaType()))
            .addConverterFactory(ScalarsConverterFactory.create())
            // 共享客户端：debug 脱敏日志由 common NetworkModule 提供（ADR-0014）
            .callFactory { okhttpCallFactory.get().newCall(it) }
            .build()
    }
}
