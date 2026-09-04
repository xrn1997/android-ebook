package com.ebook.me.repository

import com.ebook.api.entity.ReleaseResponse
import com.ebook.api.service.release.ReleaseDataSource
import com.xrn1997.common.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 版本更新检查的**策略层**：发布源清单、failover、APK 过滤与结果投影。
 *
 * 分层位置：本类只做决策，HTTP 细节经 [ReleaseDataSource] 接缝（真实实现
 * `ReleaseNetwork` 在 lib_ebook_api，mock 实现 `ReleaseNetworkTest` 供 mock flavor
 * 与独立 module_me 使用）。放 module_me 而非 lib_ebook_api，是因为「先打哪个源、
 * 什么算有效结果、要不要接受无 APK 的发布」是本项目的应用策略，不是网络契约；
 * lib_ebook_api 按 AGENTS.md 只承担 Retrofit 服务 / 实体 / 拦截器。
 * 与同目录 [ReleaseStateStore] 是一个域的两半：这里管「远端有什么」，那里管「上次检查到什么」。
 *
 * 关键取舍：
 * - **源顺序即优先级**：[GITHUB_LATEST] 在前、[GITCODE_LATEST] 作国内兜底，
 *   返回第一个成功源的结果；两源都失败才返回 null（调用方据此弹「检查失败」）。
 * - **空白 tag 视为该源无效**并继续下一个：latest 端点必有 tag，取不到即响应形态不对。
 * - **只认 `.apk` 附件**：平台会自动附带源码归档（zip/tar.gz/bz2），按扩展名过滤才不会
 *   把归档当安装包。无 APK **不算源失败**——仍返回结果（`apkDownloadUrl = null`），
 *   因为「有新版可升」与「能否一键下载安装包」是两件事，前者该提示、后者由 UI 降级处理。
 * - **取消 ≠ 源失败**：[CancellationException] 必须原样抛出，否则页面销毁后还会白打一次备用源。
 *
 * 发布位置硬编码为本仓库 owner/repo（`xrn1997/android-ebook`），不做可配置：
 * 检查更新的对象就是这一个 App，配置项只会多出一个能配错的入口。
 */
@Singleton
class ReleaseRepository @Inject constructor(
    private val releaseDataSource: ReleaseDataSource,
) {

    /**
     * 检查最新版本：按 [RELEASE_ENDPOINTS] 顺序请求，返回第一个成功源的结果。
     *
     * @return [ReleaseCheckResult]（最新 tag、发布说明与可选的 APK 入口）；
     *   null 表示全部源均失败（含解析失败），调用方按「检查失败」处置
     */
    suspend fun checkLatestRelease(): ReleaseCheckResult? = withContext(Dispatchers.IO) {
        for (endpoint in RELEASE_ENDPOINTS) {
            val response = try {
                releaseDataSource.getLatest(endpoint)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SerializationException) {
                // 与传输失败分开记：形态不对多半是平台改了契约或端点串了，排查方向完全不同
                Logger.w(TAG, "发布源响应解析失败，改用备用源：$endpoint，${e.message}")
                null
            } catch (e: Exception) {
                Logger.w(TAG, "发布源请求失败，改用备用源：$endpoint，${e.message}")
                null
            } ?: continue

            val result = project(response)
            if (result == null) {
                Logger.w(TAG, "发布源未返回有效 tag，改用备用源：$endpoint")
                continue
            }
            return@withContext result
        }
        Logger.w(TAG, "全部发布源均失败，本次更新检查不可用")
        null
    }

    /**
     * 把平台响应投影成本功能的结论：判 tag 有效性、挑出 APK 附件。
     *
     * @return null 表示该源响应不可用（tag 空白），调用方应换下一个源
     */
    private fun project(response: ReleaseResponse): ReleaseCheckResult? {
        val remoteTag = response.tagName?.trim().orEmpty()
        if (remoteTag.isEmpty()) return null
        val apk = response.assets.orEmpty().firstOrNull { asset ->
            asset.name?.endsWith(APK_SUFFIX, ignoreCase = true) == true
        }
        return ReleaseCheckResult(
            remoteTag = remoteTag,
            body = response.body.orEmpty(),
            apkDownloadUrl = apk?.browserDownloadUrl,
        )
    }

    internal companion object {
        private const val TAG = "ReleaseRepository"

        /** APK 附件扩展名；平台归档一律 `.zip` / `.tar.gz`，故按后缀即可精准命中安装包。 */
        private const val APK_SUFFIX = ".apk"

        /**
         * 两个端点与 [RELEASE_ENDPOINTS] 为 internal 只为让 `ReleaseRepositoryTest` 锁住
         * 「先 GitHub 后 Gitcode」的 failover 契约；仍不对业务代码开放配置入口。
         */
        internal const val GITHUB_LATEST =
            "https://api.github.com/repos/xrn1997/android-ebook/releases/latest"
        internal const val GITCODE_LATEST =
            "https://api.gitcode.com/api/v5/repos/xrn1997/android-ebook/releases/latest"

        /**
         * 发布源清单（顺序即优先级）：两源路径结构不同，故存完整端点而非 host + 路径模板，
         * 由 `ReleaseService` 的动态 `@Url` 直接使用。
         */
        internal val RELEASE_ENDPOINTS = listOf(GITHUB_LATEST, GITCODE_LATEST)
    }
}

/**
 * 更新检查成功后的结果投影：版本信息 + 下载入口。
 *
 * @property remoteTag 远端 tag（如 "V1.2.0"），版本比较的基准；展示前需经
 *   [com.ebook.me.util.normalizeVersionTag] 归一化（资源文案自带 `v` 前缀）
 * @property body 发布说明（Markdown 纯文本展示，不做富文本渲染）；平台无说明时为空串
 * @property apkDownloadUrl APK 下载链接（跳系统浏览器/下载器直接访问）；
 *   null 表示该次发布未带安装包附件，UI 据此收起下载按钮
 */
data class ReleaseCheckResult(
    val remoteTag: String,
    val body: String,
    val apkDownloadUrl: String?,
)
