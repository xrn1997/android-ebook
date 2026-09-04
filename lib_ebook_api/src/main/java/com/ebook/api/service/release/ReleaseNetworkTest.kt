package com.ebook.api.service.release

import com.ebook.api.entity.ReleaseResponse
import com.ebook.api.utils.TestAssetManager
import com.xrn1997.common.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 发布检查的 mock 数据源：固定回一份 latest Release 资产。
 *
 * 价值在于「不碰外网也能演练检查更新」：mock flavor 与独立运行的 module_me 能打开
 * 发现新版本弹窗、看角标、走下载入口，不必真去请求 api.github.com（CI 与离线开发都打不通）。
 *
 * 与 UserNetworkTest/CommentNetworkTest 的**关键差异**：那两个经 `getDataFromJsonFile<T>`
 * 解的是 ebook-server 的 `RespDTO<T>` 信封，而本数据源的返回形态是平台**原始 JSON**（无信封），
 * 所以资产 `release_latest.json` 整体就是 [ReleaseResponse] 的形状、解码类型也必须是它。
 * 形态与解码类型错配会抛 SerializationException——按 AGENTS.md「资产形态与解码类型同步」，
 * 这类错配不会闪退，只会让功能永远「检查失败」，故资产与实体的对应关系要一起改。
 *
 * 资产刻意保留了源码归档（zip/tar.gz）与 APK 两种附件：这样 `.apk` 过滤逻辑在 mock 下
 * 也真的被走到（若只放一个 apk，过滤写错也不会暴露）。
 *
 * 入参 [getLatest] 的 endpoint 被忽略：mock 不区分两源，因此**验不出 failover**，
 * failover 由 module_me 的 `ReleaseRepositoryTest` 用假数据源锁住。
 */
@Singleton
class ReleaseNetworkTest @Inject constructor(
    private val networkJson: Json,
    private val assets: TestAssetManager,
) : ReleaseDataSource {

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun getLatest(endpoint: String): ReleaseResponse =
        withContext(Dispatchers.IO) {
            Logger.d(TAG, "mock 发布数据源回固定资产（endpoint 被忽略）：$endpoint")
            assets.open(ASSET_LATEST_RELEASE).use { networkJson.decodeFromStream(it) }
        }

    private companion object {
        const val TAG = "ReleaseNetworkTest"
        const val ASSET_LATEST_RELEASE = "release_latest.json"
    }
}
