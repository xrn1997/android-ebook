package com.ebook.api.service.release

import com.ebook.api.entity.ReleaseResponse
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * 发布检查的真实数据源：GitHub / Gitcode 的公开 Releases API。
 *
 * 独立建一套 Retrofit（不复用 ebook-server 的 [com.ebook.api.utils.RetrofitBuilder]）：
 * - **两个 host**：端点是绝对地址、路径结构不同，靠 [ReleaseService] 的动态 `@Url` 区分，
 *   `baseUrl` 只满足 Retrofit「必须以 `/` 结尾」的形式要求；
 * - **不进认证链路**：注入 `@Named("release")` 纯净客户端（无 token、无书源的
 *   EncodingInterceptor），公开数据匿名即可访问；
 * - **不套 CoroutineAdapter**：该适配器解的是 ebook-server 的 `RespDTO` 业务码信封，
 *   平台 Release 响应没有这层包裹，直接返回裸 [ReleaseResponse]。
 *
 * 源顺序与 failover 不在本类（属上层策略），本类只负责「按给定端点取一次 latest」。
 */
@Singleton
class ReleaseNetwork @Inject constructor(
    @Named("release") private val okHttpClient: OkHttpClient,
    private val networkJson: Json,
) : ReleaseDataSource {

    /**
     * 所有端点都是绝对 `@Url`，故只建一个 Retrofit 实例复用，按 endpoint 差异走动态 `@Url`。
     */
    private val service: ReleaseService by lazy {
        Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE_URL)
            .addConverterFactory(networkJson.asConverterFactory("application/json".toMediaType()))
            .client(okHttpClient)
            .build()
            .create(ReleaseService::class.java)
    }

    override suspend fun getLatest(endpoint: String): ReleaseResponse =
        service.getLatest(endpoint)

    private companion object {
        const val PLACEHOLDER_BASE_URL = "https://api.github.com/"
    }
}
