package com.ebook.api.service.release

import com.ebook.api.entity.ReleaseResponse
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * 发布检查的网络接口：拉取指定发布源仓库的 `latest` Release。
 *
 * 使用动态 [@Url]（而非相对路径 + 固定 baseUrl），因为 GitHub 与 Gitcode 两源的
 * 完整端点是绝对地址（`https://api.github.com/repos/.../releases/latest` 与
 * `https://api.gitcode.com/api/v5/repos/.../releases/latest`），路径结构不同，
 * 无法收敛到单一 baseUrl。这与书源动态 URL 的既有模式（BookSourceService）一致。
 */
interface ReleaseService {

    /**
     * 拉取指定地址的 latest Release（匿名访问，无需 token）。
     *
     * @param url 完整端点地址（含 host + 路径）
     * @return 两平台共用的统一 Release JSON 投影
     */
    @GET
    suspend fun getLatest(@Url url: String): ReleaseResponse
}