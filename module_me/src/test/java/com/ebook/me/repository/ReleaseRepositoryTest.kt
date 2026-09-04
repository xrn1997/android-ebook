package com.ebook.me.repository

import com.ebook.api.entity.ReleaseAsset
import com.ebook.api.entity.ReleaseResponse
import com.ebook.api.service.release.ReleaseDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/**
 * [ReleaseRepository] 的更新策略单元测试：用假数据源锁住「发布检查」的决策契约。
 *
 * 这些契约在真机上难以稳定复现（要等一次外网故障或一轮没有 APK 的发布），
 * 却每一条都会直接改变用户看到的结果，故在此锁死：
 * - 源顺序即优先级：主源成功就不碰备用源；主源失败（传输/形态/tag 无效）才降级
 * - 全部源失败才返回 null（调用方据此弹「检查失败」）
 * - 只认 `.apk` 附件，平台自动附带的源码归档不当安装包；无 APK **不算源失败**
 * - 协程取消必须原样抛出，不能被当成「这个源挂了」而继续打下一个源
 */
class ReleaseRepositoryTest {

    private val github = ReleaseRepository.GITHUB_LATEST
    private val gitcode = ReleaseRepository.GITCODE_LATEST

    /** 按端点给出结果的假数据源；[requested] 记录调用顺序，用于断言 failover 走向。 */
    private class Stub(
        private val outcome: (String) -> ReleaseResponse,
    ) : ReleaseDataSource {
        val requested = mutableListOf<String>()

        override suspend fun getLatest(endpoint: String): ReleaseResponse {
            requested += endpoint
            return outcome(endpoint)
        }
    }

    /** 全部端点都抛同一个异常的桩（模拟两源同时不可用）。 */
    private class ThrowingStub(
        private val error: () -> Throwable,
    ) : ReleaseDataSource {
        val requested = mutableListOf<String>()

        override suspend fun getLatest(endpoint: String): ReleaseResponse {
            requested += endpoint
            throw error()
        }
    }

    private fun release(
        tag: String?,
        body: String? = "说明",
        assets: List<ReleaseAsset>? = listOf(asset("android-ebook-1.3.0.apk")),
    ) = ReleaseResponse(tagName = tag, name = tag, body = body, assets = assets)

    private fun asset(name: String?) = ReleaseAsset(
        name = name,
        browserDownloadUrl = name?.let { "https://download.example/$it" },
    )

    @Test
    fun `主源成功即返回结果且不再请求备用源`() = runBlocking {
        val stub = Stub { release(tag = "V1.3.0") }

        val result = ReleaseRepository(stub).checkLatestRelease()

        assertEquals(listOf(github), stub.requested)
        assertEquals("V1.3.0", result?.remoteTag)
        assertEquals("https://download.example/android-ebook-1.3.0.apk", result?.apkDownloadUrl)
    }

    @Test
    fun `主源请求失败时自动改用备用源`() = runBlocking {
        val stub = Stub { endpoint ->
            if (endpoint == github) throw IOException("连接被重置")
            release(tag = "V1.3.1")
        }

        val result = ReleaseRepository(stub).checkLatestRelease()

        assertEquals(listOf(github, gitcode), stub.requested)
        assertEquals("V1.3.1", result?.remoteTag)
    }

    @Test
    fun `响应形态不对也算源失败并改用备用源`() = runBlocking {
        val stub = Stub { endpoint ->
            if (endpoint == github) throw SerializationException("期望 JSON 对象")
            release(tag = "V1.3.2")
        }

        val result = ReleaseRepository(stub).checkLatestRelease()

        assertEquals(listOf(github, gitcode), stub.requested)
        assertEquals("V1.3.2", result?.remoteTag)
    }

    @Test
    fun `tag 空白的源视为无效并改用备用源`() = runBlocking {
        val stub = Stub { endpoint ->
            if (endpoint == github) release(tag = "   ") else release(tag = "V1.3.3")
        }

        val result = ReleaseRepository(stub).checkLatestRelease()

        assertEquals(listOf(github, gitcode), stub.requested)
        assertEquals("V1.3.3", result?.remoteTag)
    }

    @Test
    fun `全部源都失败时返回 null`() = runBlocking {
        val stub = ThrowingStub { IOException("两源均不可达") }

        assertNull(ReleaseRepository(stub).checkLatestRelease())
        // 两个源都试过才放弃：顺序即优先级，备用源是国内兜底
        assertEquals(listOf(github, gitcode), stub.requested)
    }

    @Test
    fun `只取 apk 附件，忽略平台自动附带的源码归档`() = runBlocking {
        val stub = Stub {
            release(
                tag = "V1.3.0",
                assets = listOf(
                    asset("source-code.zip"),
                    asset("android-ebook-1.3.0.apk"),
                    asset("source-code.tar.gz"),
                ),
            )
        }

        val result = ReleaseRepository(stub).checkLatestRelease()

        assertEquals("https://download.example/android-ebook-1.3.0.apk", result?.apkDownloadUrl)
    }

    @Test
    fun `apk 附件名大小写混写也能命中`() = runBlocking {
        val stub = Stub { release(tag = "V1.3.0", assets = listOf(asset("Ebook-1.3.0.APK"))) }

        val result = ReleaseRepository(stub).checkLatestRelease()

        assertEquals("https://download.example/Ebook-1.3.0.APK", result?.apkDownloadUrl)
    }

    @Test
    fun `发布未带 apk 时仍返回结果且下载入口为空`() = runBlocking {
        // 「有新版可升」与「能否一键下载安装包」是两件事：前者要提示，后者由 UI 收起下载按钮
        val stub = Stub { release(tag = "V1.3.0", assets = listOf(asset("source-code.zip"))) }

        val result = ReleaseRepository(stub).checkLatestRelease()

        assertEquals("V1.3.0", result?.remoteTag)
        assertNull(result?.apkDownloadUrl)
    }

    @Test
    fun `附件清单缺失或字段为 null 时不抛异常`() = runBlocking {
        // 两平台契约里 assets/body 都可能为 null（无说明、无附件的 Release 很常见）
        val stub = Stub { ReleaseResponse(tagName = "V1.3.0", name = null, body = null, assets = null) }

        val result = ReleaseRepository(stub).checkLatestRelease()

        assertEquals("V1.3.0", result?.remoteTag)
        assertEquals("", result?.body)
        assertNull(result?.apkDownloadUrl)
    }

    @Test
    fun `协程取消不被当成源失败而继续请求备用源`() = runBlocking {
        val stub = ThrowingStub { CancellationException("页面已销毁") }
        val repository = ReleaseRepository(stub)

        assertThrows(CancellationException::class.java) {
            runBlocking { repository.checkLatestRelease() }
        }
        // 只碰了主源：取消若被吞成「这个源失败」，销毁后的协程还会白打一次备用源
        assertEquals(listOf(github), stub.requested)
    }
}
