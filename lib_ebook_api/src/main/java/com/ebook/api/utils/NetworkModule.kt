package com.ebook.api.utils

import android.content.Context
import com.ebook.api.BuildConfig
import com.ebook.api.intercepter.EncodingInterceptor
import com.xrn1997.common.di.AuthAllowedHosts
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun providesNetworkJson(): Json = Json {
        prettyPrint = true       // 美化 JSON 输出格式
        ignoreUnknownKeys = true // 忽略 JSON 中的未知字段
    }

    @Provides
    @Singleton
    fun providesTestAssetManager(
        @ApplicationContext context: Context,
    ): TestAssetManager = TestAssetManager(context.assets::open)

    /**
     * 认证客户端不再自建：统一注入 lib_common 共享 Call.Factory（AuthInterceptor + 白名单 +
     * debug 脱敏日志，30s 超时），见 ADR-0014。此处只保留白名单绑定与书源纯净客户端。
     */
    @Provides
    @Singleton
    @AuthAllowedHosts
    fun provideAuthAllowedHosts(): Set<String> = setOf(BuildConfig.EBOOK_SERVER_HOST)

    /**
     * 书源请求客户端：纯净 OkHttpClient，不携带登录凭证（Authorization 头），
     * 避免 token 泄漏给第三方书源网站；超时 10s（第三方站点需快速失败）。
     */
    @Provides
    @Singleton
    @Named("source")
    fun provideSourceOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .addInterceptor(EncodingInterceptor("UTF-8"))
        .build()

    /**
     * 发布检查客户端：GitHub / Gitcode 公开 Releases API 的纯净 OkHttpClient。
     *
     * 不复用 [Named] "source" 那条：书源客户端按 AGENTS.md 专属「第三方书源抓取」，且带
     * 面向中文 URL 的 EncodingInterceptor；发布 API 的端点全是 ASCII JSON，两条链路的超时
     * 与拦截器演化方向不同，混用会让命名失真。与书源客户端一致的**唯一不变量是不带 token**。
     */
    @Provides
    @Singleton
    @Named("release")
    fun provideReleaseOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
}

fun interface TestAssetManager {
    fun open(fileName: String): InputStream
}