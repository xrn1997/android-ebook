package com.ebook.me.mvvm.viewmodel

import android.app.Application
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.ebook.api.entity.ReleaseAsset
import com.ebook.api.entity.ReleaseResponse
import com.ebook.api.service.release.ReleaseDataSource
import com.ebook.common.domain.UserSession
import com.ebook.common.domain.UserSessionManager
import com.ebook.common.provider.ILoginProvider
import com.ebook.me.repository.CacheModel
import com.ebook.me.repository.ReleaseCheckResult
import com.ebook.me.repository.ReleaseRepository
import com.ebook.me.repository.ReleaseStateStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import com.xrn1997.common.mvvm.viewmodel.Overlay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * [SettingViewModel] 版本检查编排的回归测试（Robolectric JVM，无设备依赖）。
 *
 * 锁定的是「一次检查的结果如何被用户看到」的编排契约（failover/.apk 过滤等策略归
 * [com.ebook.me.repository.ReleaseRepositoryTest]，此处一律用可门控的假数据源绕开）：
 * - **静默检查在途时点「版本」行不得被吞**：修复前主动与静默共用一条重入锁，
 *   静默在途时 `checkUpdate()` 直接 return——不进 Checking、结果也永不进弹窗，
 *   用户在慢网下点版本行毫无反馈。修复后把在途静默检查**升级**为用户可见的一次检查，
 *   结论回来时进弹窗，且仍不并发第二个请求（单飞不破坏）。
 * - 静默检查自身**不弹窗**、只落盘 tag（ADR-0021 的角标派生输入）。
 * - 限频时间戳**双写**：成功检查同时写时间戳（窗口内不再判「该刷新」）；失败检查两样都不写
 *   （「判不出结论就不算检查成功」，限频窗口不被失败复位）。
 * - 升级后的检查两源均失败 → 弹 CheckError（「判不出结论就不算检查成功」）。
 *
 * 时序控制：假数据源进入 [GatedStub.getLatest] 即发 [GatedStub.entered]（真实挂起），
 * 测试放行 [GatedStub.letThrough] 后用 [awaitUntil] 轮询观察条件——Main 段挂在
 * [StandardTestDispatcher] 上由 `advanceUntilIdle` 驱动，而生产代码
 * `withContext(Dispatchers.IO)` 跑在真实 IO 线程上、不受虚拟时钟控制，
 * 固定等待会与之竞态（flush 的收尾、failover 的下一源都可能晚一拍）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * 轮询等到 [cond] 成立：交替推进测试调度器与真实时间（IO 线程需要后者）。
     * 只等待「可观察状态」而不是固定时长，避免与真实 IO 线程竞态。
     */
    private suspend fun TestScope.awaitUntil(message: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!cond()) {
            advanceUntilIdle()
            if (cond()) break
            assertTrue("$message（5s 内未达成）", System.currentTimeMillis() < deadline)
            withContext(Dispatchers.IO) { Thread.sleep(10) }
        }
        advanceUntilIdle()
    }

    /**
     * 可门控的假发布源：进入 [getLatest] 即报「已在途」，等 [letThrough] 放行后按 [outcome] 收场。
     * [outcome] 在放行后求值，故同一根桩既能给成功响应也能在放行后抛异常模拟源故障。
     */
    private class GatedStub(
        private val outcome: () -> ReleaseResponse,
    ) : ReleaseDataSource {
        val requested = mutableListOf<String>()
        val entered = CompletableDeferred<Unit>()
        private val gate = CompletableDeferred<Unit>()

        fun letThrough() {
            gate.complete(Unit)
        }

        override suspend fun getLatest(endpoint: String): ReleaseResponse {
            requested += endpoint
            entered.complete(Unit)
            gate.await()
            return outcome()
        }
    }

    /**
     * 记录调用的假会话管理器。
     *
     * 版本检查的编排不触碰会话（登录态只决定页面显隐），但**登出的本地那一半必须可断言**——
     * [clearSession] 是「用户是否真的退出去了」的唯一落点，故记次数并写调用日志。
     */
    private class RecordingSessionManager(
        private val calls: MutableList<String> = mutableListOf(),
    ) : UserSessionManager {
        var clearCount = 0
            private set
        override val isLoggedIn: StateFlow<Boolean> = MutableStateFlow(true)
        override val currentUser: StateFlow<UserSession?> = MutableStateFlow(null)
        override suspend fun saveSession(session: UserSession, refreshToken: String) = Unit
        override suspend fun rotateCredentials(accessToken: String, refreshToken: String) = Unit
        override fun clearSession() {
            clearCount++
            calls += "clearSession"
        }
        override fun getRefreshToken(): String? = null
    }

    /**
     * 假登录 provider：记调用次数、写调用日志，可选挂起门（模拟服务端请求在途）。
     *
     * 生产路径的 provider 由 TheRouter 解析，测试路径直接注入本假件——同一接缝两个 adapter。
     */
    private class FakeLoginProvider(
        private val calls: MutableList<String> = mutableListOf(),
        private val outcome: Result<Unit> = Result.success(Unit),
        private val gate: CompletableDeferred<Unit>? = null,
    ) : ILoginProvider {
        var logoutCount = 0
            private set

        override suspend fun logout(): Result<Unit> {
            logoutCount++
            calls += "provider.logout"
            gate?.await()
            return outcome
        }
    }

    /** 版本号装到 Robolectric 的 PackageManager 上：`ReleaseStateStore.currentVersion` 的比较基准。 */
    private fun installCurrentVersion(versionName: String) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val info = PackageInfo().apply {
            packageName = app.packageName
            this.versionName = versionName
        }
        Shadows.shadowOf(app.packageManager).installPackage(info)
    }

    private fun releaseResponse() = ReleaseResponse(
        tagName = "V1.2.0",
        name = "V1.2.0",
        body = "说明",
        assets = listOf(
            ReleaseAsset(name = "android-ebook-1.2.0.apk", browserDownloadUrl = "https://d/x.apk")
        ),
    )

    private fun newViewModel(
        stub: GatedStub,
        sessionManager: UserSessionManager = RecordingSessionManager(),
    ): SettingViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return SettingViewModel(
            context = app,
            cacheModel = CacheModel(app.cacheDir),
            userSessionManager = sessionManager,
            releaseRepository = ReleaseRepository(stub),
            releaseStateStore = ReleaseStateStore(app),
        )
    }

    @Test
    fun `静默检查在途时点版本行升级为可见检查且复用在途请求`() = runTest(mainDispatcher) {
        installCurrentVersion("1.0.0")
        val stub = GatedStub { releaseResponse() }
        val viewModel = newViewModel(stub)

        // 静默检查在途（从未检查过 → 限频窗口必命中 → init 发起），不弹窗
        advanceUntilIdle()
        stub.entered.await()
        advanceUntilIdle()
        assertEquals(UpdateState.Idle, viewModel.updateState.value)

        // 用户此刻点「版本」行：修复前这里保持 Idle（点击被吞、永不弹窗）
        viewModel.checkUpdate()
        assertEquals("在途的静默检查应升级为可见检查", UpdateState.Checking, viewModel.updateState.value)

        stub.letThrough()
        awaitUntil("请求回来的结论应进弹窗") {
            viewModel.updateState.value is UpdateState.HasUpdate
        }

        // 结论进弹窗，且复用同一个在途请求（单飞，没有并发第二个）
        assertEquals(1, stub.requested.size)
        assertEquals(
            UpdateState.HasUpdate(
                ReleaseCheckResult("V1.2.0", "说明", "https://d/x.apk")
            ),
            viewModel.updateState.value,
        )
    }

    @Test
    fun `无在途检查时点版本行走完整检查并弹结果`() = runTest(mainDispatcher) {
        installCurrentVersion("1.0.0")
        // 预置一次近期成功检查：压掉 init 的静默刷新，隔离出「纯主动检查」场景
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ReleaseStateStore(app).also { it.markCheckSuccess("V0.9.0") }
        val stub = GatedStub { releaseResponse() }
        val viewModel = SettingViewModel(
            context = app,
            cacheModel = CacheModel(app.cacheDir),
            userSessionManager = RecordingSessionManager(),
            releaseRepository = ReleaseRepository(stub),
            releaseStateStore = store,
        )
        advanceUntilIdle()
        assertEquals(0, stub.requested.size)

        viewModel.checkUpdate()
        advanceUntilIdle()
        assertEquals(UpdateState.Checking, viewModel.updateState.value)

        stub.letThrough()
        awaitUntil("请求回来的结论应进弹窗") {
            viewModel.updateState.value is UpdateState.HasUpdate
        }

        assertEquals(1, stub.requested.size)
        assertEquals(
            UpdateState.HasUpdate(
                ReleaseCheckResult("V1.2.0", "说明", "https://d/x.apk")
            ),
            viewModel.updateState.value,
        )
    }

    @Test
    fun `静默检查完成只落盘结论不弹窗`() = runTest(mainDispatcher) {
        installCurrentVersion("1.0.0")
        val stub = GatedStub { releaseResponse() }
        val viewModel = newViewModel(stub)

        advanceUntilIdle()
        stub.entered.await()
        stub.letThrough()
        // 新建一份 store 读同一 SP 文件：验证结论确实落盘（角标派生的输入）
        val freshStore = ReleaseStateStore(ApplicationProvider.getApplicationContext())
        awaitUntil("检查到的 tag 应落盘") { freshStore.lastCheckedTag == "V1.2.0" }

        // 成功检查同时写限频时间戳：刚成功过，7 天窗口内不应再判「该刷新」
        assertFalse("成功检查应写限频时间戳", freshStore.shouldAutoRefresh())

        assertEquals("静默检查不改变弹窗状态", UpdateState.Idle, viewModel.updateState.value)
    }

    @Test
    fun `升级后的检查两源均失败时弹检查失败`() = runTest(mainDispatcher) {
        installCurrentVersion("1.0.0")
        val stub = GatedStub { throw java.io.IOException("两源均不可达") }
        val viewModel = newViewModel(stub)

        advanceUntilIdle()
        stub.entered.await()
        advanceUntilIdle()

        viewModel.checkUpdate()
        assertEquals(UpdateState.Checking, viewModel.updateState.value)

        stub.letThrough()
        awaitUntil("判不出结论应按检查失败处置") {
            viewModel.updateState.value is UpdateState.CheckError
        }

        // failover 打满两源才放弃
        assertEquals(2, stub.requested.size)

        // 「判不出结论就不算检查成功」：失败不写 tag、不写限频时间戳（窗口不被失败复位）
        val freshStore = ReleaseStateStore(ApplicationProvider.getApplicationContext())
        assertEquals("", freshStore.lastCheckedTag)
        assertTrue("失败不得写限频时间戳", freshStore.shouldAutoRefresh())
    }

    // ==================== 登出编排：收尾归 ViewModel ====================
    //
    // 修复前登出由 SettingActivity 在 lifecycleScope 里串起来（logout → Toast → finish）：
    // 网络挂起期间旋转重建 Activity，协程被取消，`clearSession()` 永不执行——用户点了
    // 「退出登录」却仍是登录态，且没有任何提示。改后整条链在 viewModelScope 内跑完，
    // 提示与关闭经基类命令通道下发（uiAction 是 lib_common 的 internal，测试观测不到，
    // 故此处锁的是可观察的那三面：调用顺序、闸门、覆盖层）。

    @Test
    fun `退出登录先作废服务端会话再清本地会话`() = runTest(mainDispatcher) {
        val calls = mutableListOf<String>()
        val sessionManager = RecordingSessionManager(calls)
        val viewModel = newViewModel(GatedStub { releaseResponse() }, sessionManager)

        viewModel.runLogout(FakeLoginProvider(calls))
        advanceUntilIdle()

        // 顺序即语义：本地清掉后就再拿不到 refresh token，服务端那一半必须先发
        assertEquals(listOf("provider.logout", "clearSession"), calls)
    }

    @Test
    fun `服务端登出失败时仍然清本地会话`() = runTest(mainDispatcher) {
        val calls = mutableListOf<String>()
        val sessionManager = RecordingSessionManager(calls)
        val provider = FakeLoginProvider(calls, outcome = Result.failure(java.io.IOException("网络不可达")))
        val viewModel = newViewModel(GatedStub { releaseResponse() }, sessionManager)

        viewModel.runLogout(provider)
        advanceUntilIdle()

        // 救不回的凭证不该把用户锁在一个他已认为退出的会话里
        assertEquals(1, provider.logoutCount)
        assertEquals("服务端失败不得阻塞本地清理", 1, sessionManager.clearCount)
    }

    @Test
    fun `独立模式取不到provider时只清本地会话`() = runTest(mainDispatcher) {
        val calls = mutableListOf<String>()
        val sessionManager = RecordingSessionManager(calls)
        val viewModel = newViewModel(GatedStub { releaseResponse() }, sessionManager)

        viewModel.runLogout(null)
        advanceUntilIdle()

        // isModule=true 时 module_login 不在依赖图内：provider 缺席只影响服务端那一半
        assertEquals(listOf("clearSession"), calls)
    }

    @Test
    fun `连点两次退出登录只作废一次服务端`() = runTest(mainDispatcher) {
        val gate = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val sessionManager = RecordingSessionManager(calls)
        val provider = FakeLoginProvider(calls, gate = gate)
        val viewModel = newViewModel(GatedStub { releaseResponse() }, sessionManager)

        viewModel.runLogout(provider)
        advanceUntilIdle()                             // 第一次挂在服务端请求上
        viewModel.runLogout(provider)                  // 连点：第二次必须被闸门挡掉
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("在途期间重复触发不得重发登出", 1, provider.logoutCount)
        assertEquals("本地会话只清一次", 1, sessionManager.clearCount)
    }

    @Test
    fun `登出在途时覆盖层为Loading并在结束后复位`() = runTest(mainDispatcher) {
        val gate = CompletableDeferred<Unit>()
        val viewModel = newViewModel(GatedStub { releaseResponse() }, RecordingSessionManager())

        viewModel.runLogout(FakeLoginProvider(gate = gate))
        advanceUntilIdle()
        assertEquals("登出在途要给可见的等待态", Overlay.Loading, viewModel.uiState.value.overlay)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("收尾后覆盖层必须复位", Overlay.None, viewModel.uiState.value.overlay)
    }

    @Test
    fun `登出入口经TheRouter解析provider且缺席时不闪退`() = runTest(mainDispatcher) {
        val sessionManager = RecordingSessionManager()
        val viewModel = newViewModel(GatedStub { releaseResponse() }, sessionManager)

        // 公共入口走真解析路径：本模块依赖图里没有 ILoginProvider 实现，
        // 解析结果必须安全落到「只清本地」，而不是抛异常
        viewModel.logout()
        advanceUntilIdle()

        assertEquals(1, sessionManager.clearCount)
    }
}
