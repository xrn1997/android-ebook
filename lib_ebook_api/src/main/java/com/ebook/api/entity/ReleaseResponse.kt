package com.ebook.api.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GitHub / Gitcode Releases API 的 `latest` 响应 DTO。
 *
 * 两平台响应结构高度同构（均为 Git 托管平台的 Releases 契约），本实体是二者的统一投影。
 * 仅声明本功能实际消费的字段，未知字段由全局 Json 配置（ignoreUnknownKeys）忽略：
 * - [tagName]：版本 tag，如 "V1.2.0"。本地展示与版本比较都以它为基准。
 * - [name]：发布名（常与 tag 同），当前无消费方，保留以贴合平台契约。
 * - [body]：Release 发布说明（Markdown 文本），弹窗中作为纯文本展示。
 * - [assets]：发布附件清单，下载入口经它按 `.apk` 扩展名过滤得到 APK。
 *
 * **字段一律可空**：两平台契约允许 `name`/`body`/`assets` 为 `null`（无说明的 Release 很常见）。
 * 本模块的全局 `Json` 刻意不开 `coerceInputValues`——开了会把 null 降级成默认值，同时波及
 * ebook-server 全部 DTO 的契约严格性。所以「可能为 null」必须在 DTO 上如实声明，
 * 回落形态交给取用方（`module_me` 的 `ReleaseRepository`）决定。
 *
 * 注意：GitHub 用 `api.github.com`，Gitcode 用 `api.gitcode.com/api/v5`，但返回的
 * Release 字段（tag_name/name/body/assets[].browser_download_url）一致，故共用此实体。
 */
@Serializable
data class ReleaseResponse(
    @SerialName("tag_name")
    val tagName: String? = null,
    val name: String? = null,
    val body: String? = null,
    val assets: List<ReleaseAsset>? = null,
)

/**
 * Release 附件（asset）。
 *
 * 我们只关心分发用的 APK 附件；平台会自动附带源码归档（zip/tar.gz 等），
 * 取用时必须按 [name] 扩展名 `.apk` 过滤，避免把归档当安装包。
 * 两字段按平台契约可空（归档条目可能缺名或缺 URL），过滤方需自行判空。
 */
@Serializable
data class ReleaseAsset(
    val name: String? = null,
    @SerialName("browser_download_url")
    val browserDownloadUrl: String? = null,
)
