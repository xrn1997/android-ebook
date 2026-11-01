package com.ebook.me.mvvm.viewmodel

import android.app.Application
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.ebook.api.config.ServerAddress
import com.ebook.api.config.ServerAddressStore
import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.ReleaseAsset
import com.ebook.api.entity.ReleaseResponse
import com.ebook.api.service.release.ReleaseDataSource
import com.ebook.api.testfixture.FakeSpContext
import com.ebook.api.testfixture.FakeSharedPreferences
import com.ebook.common.domain.ThemeModeManager
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
import kotlinx.coroutines.launch
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
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
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
     * [endSession] 内联跑 [clearSession]：线程/取消契约归 `AndroidUserSessionManagerTest`，
     * 假件只保证「登出调的是清会话」这条编排链可断言。
     * [clearThread] 供换址路径的线程断言使用——那条守的是 SettingViewModel 自己包的
     * IO 段（直调 [clearSession] 拿布尔），不是 [endSession] 的契约。
     */
    private class RecordingSessionManager(
        private val calls: MutableList<String> = mutableListOf(),
    ) : UserSessionManager {
        var clearCount = 0
            private set

        /** [clearSession] 的调用线程：换址路径自带 IO 包装的线程断言靠它观测。 */
        @Volatile
        var clearThread: Thread? = null
            private set

        /** 模拟两处 SP 的 commit 结果；false 时调用方必须保留启动兜底标记。 */
        @Volatile
        var clearSucceeds = true

        override val isLoggedIn: StateFlow<Boolean> = MutableStateFlow(true)
        override val currentUser: StateFlow<UserSession?> = MutableStateFlow(null)
        override suspend fun saveSession(session: UserSession, refreshToken: String) = Unit
        override suspend fun rotateCredentials(accessToken: String, refreshToken: String) = Unit
        override fun clearSession(): Boolean {
            clearCount++
            clearThread = Thread.currentThread()
            calls += "clearSession"
            return clearSucceeds
        }
        override suspend fun endSession() {
            clearSession()
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
        sourceCatalog: FakeSourceCatalog = FakeSourceCatalog(
            initial = listOf(
                BookSourceRule(name = "甲站", url = "https://a.example"),
                BookSourceRule(name = "乙站", url = "https://b.example"),
            )
        ),
        serverAddressStore: ServerAddressStore? = null,
    ): SettingViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return SettingViewModel(
            context = app,
            cacheModel = CacheModel(app.cacheDir),
            userSessionManager = sessionManager,
            releaseRepository = ReleaseRepository(stub),
            releaseStateStore = ReleaseStateStore(app),
            themeModeManager = ThemeModeManager(app),
            sourceCatalog = sourceCatalog,
            serverAddressStore = serverAddressStore ?: ServerAddressStore(app),
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
            themeModeManager = ThemeModeManager(app),
            sourceCatalog = FakeSourceCatalog(),
            serverAddressStore = ServerAddressStore(app),
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
        // 清会话在 IO 线程收尾（同步 commit 不落主线程），等它真正完成再断言顺序
        awaitUntil("清会话应完成") { sessionManager.clearCount == 1 }

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
        awaitUntil("清会话应完成") { sessionManager.clearCount == 1 }

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
        awaitUntil("清会话应完成") { sessionManager.clearCount == 1 }

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
        awaitUntil("登出收尾应完成") { sessionManager.clearCount == 1 }

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
        awaitUntil("收尾后覆盖层必须复位") { viewModel.uiState.value.overlay == Overlay.None }
    }

    @Test
    fun `登出入口经TheRouter解析provider且缺席时不闪退`(): Unit = runTest(mainDispatcher) {
        val sessionManager = RecordingSessionManager()
        val viewModel = newViewModel(GatedStub { releaseResponse() }, sessionManager)

        // 公共入口走真解析路径：本模块依赖图里没有 ILoginProvider 实现，
        // 解析结果必须安全落到「只清本地」，而不是抛异常
        viewModel.logout()
        advanceUntilIdle()
        awaitUntil("清会话应完成") { sessionManager.clearCount == 1 }

        assertEquals(1, sessionManager.clearCount)
    }

    // ==================== 书源入口计数 ====================
    //
    // 「通用」组那行「书源管理」的副标题只有一个数字可看，它来自本类的 sourcesCount。
    // 数错（只数启用、或把清单长度当成页数）在这一行上用户无从发现，只能钉住：
    // 数字 = 清单条数，含禁用项（禁用项仍在清单里、仍可在书源页打开）。

    @Test
    fun `书源入口行显示清单条数含禁用项`(): Unit = runTest(mainDispatcher) {
        val viewModel = newViewModel(
            stub = GatedStub { releaseResponse() },
            sourceCatalog = FakeSourceCatalog(
                initial = listOf(
                    BookSourceRule(name = "甲站", url = "https://a.example"),
                    // 禁用项也要数进去：这一行回答的是「手上有几个源」
                    BookSourceRule(name = "乙站", url = "https://b.example", enabled = false),
                )
            ),
        )

        // sourcesCount 是 WhileSubscribed(5s) 的热流：无订阅者时不发射，这里挂一个收集者代表页面
        backgroundScope.launch { viewModel.sourcesCount.collect { } }
        advanceUntilIdle()

        assertEquals(2, viewModel.sourcesCount.value)
    }

    // ==================== 服务器地址：保存 / 清除配置 ====================
    //
    // 换址必须清会话：旧服务器的 refresh token 不得随新地址发出（ADR-0052）。
    // Invalid 与 Unchanged 两档是「用户以为改了、实际没改」的路径，绝不能不必要地清会话。

    private fun newServerAddressStore() =
        ServerAddressStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `非法服务器地址不保存也不清会话`() = runTest(mainDispatcher) {
        val session = RecordingSessionManager()
        val viewModel = newViewModel(GatedStub { releaseResponse() }, sessionManager = session)
        var outcome: ServerAddressSaveOutcome? = null

        viewModel.saveServerAddress("http://a.com/api") { outcome = it }
        advanceUntilIdle()

        assertEquals(ServerAddressSaveOutcome.Invalid, outcome)
        assertEquals(0, session.clearCount)
        assertFalse(newServerAddressStore().isConfigured)
        assertFalse("非法输入不得留下待清标记", newServerAddressStore().hasSessionResetPending())
    }

    /**
     * 未配置页的空输入点保存 = 非法（没有「不变」可存：应用不带默认地址，`current()` 是 null，
     * 空串不是地址）。原「存当前默认值 = Unchanged」的语义随默认值概念一起退场，
     * 「同一地址重复保存 = Unchanged」由下方 `重复保存同一个自定义地址不再次清会话` 锁住。
     */
    @Test
    fun `未配置时保存空输入按非法处理不写盘不清会话`() = runTest(mainDispatcher) {
        val session = RecordingSessionManager()
        val viewModel = newViewModel(GatedStub { releaseResponse() }, sessionManager = session)
        var outcome: ServerAddressSaveOutcome? = null

        viewModel.saveServerAddress("") { outcome = it }
        advanceUntilIdle()

        assertEquals(ServerAddressSaveOutcome.Invalid, outcome)
        assertEquals(0, session.clearCount)
        assertFalse(newServerAddressStore().isConfigured)
        assertFalse("非法输入不得留下待清标记", newServerAddressStore().hasSessionResetPending())
    }

    @Test
    fun `地址变更后写盘清会话并推进状态`() = runTest(mainDispatcher) {
        val session = RecordingSessionManager()
        val viewModel = newViewModel(GatedStub { releaseResponse() }, sessionManager = session)
        var outcome: ServerAddressSaveOutcome? = null

        viewModel.saveServerAddress("192.168.9.9:8080") { outcome = it }
        awaitUntil("保存应完成") { outcome != null }

        assertEquals(ServerAddressSaveOutcome.Saved, outcome)
        assertEquals(1, session.clearCount)
        assertEquals("http://192.168.9.9:8080", viewModel.serverAddressState.value.display)
        assertTrue(viewModel.serverAddressState.value.isConfigured)
        // 重启提示由 store 的进程级快照派生：状态流里必须反映「待重启」，
        // 否则转屏/离页丢掉一次性回调后就没人再弹强制重启了
        assertTrue("已改地址、本进程尚未重启", viewModel.serverAddressState.value.pendingRestart)
        assertEquals("http://192.168.9.9:8080/", newServerAddressStore().current()!!.baseUrl)
    }

    @Test
    fun `重复保存同一个自定义地址不再次清会话`() = runTest(mainDispatcher) {
        val session = RecordingSessionManager()
        val viewModel = newViewModel(GatedStub { releaseResponse() }, sessionManager = session)
        var outcome: ServerAddressSaveOutcome? = null

        viewModel.saveServerAddress("192.168.9.9:8080") { outcome = it }
        awaitUntil("首次保存应完成") { outcome == ServerAddressSaveOutcome.Saved }

        // 用户在待重启期间再次打开编辑框并原样保存：不是换址，不该再清一次会话
        outcome = null
        viewModel.saveServerAddress("192.168.9.9:8080") { outcome = it }
        advanceUntilIdle()

        assertEquals(ServerAddressSaveOutcome.Unchanged, outcome)
        assertEquals("同一地址重复保存不得再清会话", 1, session.clearCount)
        assertTrue("已改地址仍待重启", viewModel.serverAddressState.value.pendingRestart)
    }

    @Test
    fun `清除配置清掉自定义值并再次清会话`() = runTest(mainDispatcher) {
        val session = RecordingSessionManager()
        val viewModel = newViewModel(GatedStub { releaseResponse() }, sessionManager = session)
        var outcome: ServerAddressSaveOutcome? = null

        // 无自定义时清除配置是空操作：不清会话、不弹重启
        viewModel.resetServerAddress { outcome = it }
        advanceUntilIdle()
        assertEquals(ServerAddressSaveOutcome.Unchanged, outcome)
        assertEquals(0, session.clearCount)

        viewModel.saveServerAddress("192.168.9.9:8080") { outcome = it }
        awaitUntil("先保存一个自定义地址") { outcome == ServerAddressSaveOutcome.Saved }

        outcome = null
        viewModel.resetServerAddress { outcome = it }
        awaitUntil("清除配置应完成") { outcome != null }

        assertEquals(ServerAddressSaveOutcome.Saved, outcome)
        assertEquals(2, session.clearCount)
        assertFalse(viewModel.serverAddressState.value.isConfigured)
        assertFalse(newServerAddressStore().isConfigured)
        assertNull("清除配置后回到未配置（应用不带默认地址）", viewModel.serverAddressState.value.display)
    }

    /**
     * 清除配置也是换址：若本进程是**带着自定义地址**启动的（store 的进程快照 = 自定义地址），
     * 清除配置后 Retrofit 与白名单仍指向旧自定义地址，重启提示必须出现——不能只在
     * 「自定义地址是在本进程内保存的」这一条路径上催重启。
     */
    @Test
    fun `以自定义地址启动后清除配置同样待重启`() = runTest(mainDispatcher) {
        // 先落盘、再建 VM：新 store 的启动快照就是自定义地址，模拟重启后生效的那一份
        newServerAddressStore().save(ServerAddress.parse("192.168.9.9:8080")!!)
        val session = RecordingSessionManager()
        val viewModel = newViewModel(GatedStub { releaseResponse() }, sessionManager = session)
        var outcome: ServerAddressSaveOutcome? = null

        viewModel.resetServerAddress { outcome = it }
        awaitUntil("清除配置应完成") { outcome != null }

        assertEquals(ServerAddressSaveOutcome.Saved, outcome)
        assertEquals(1, session.clearCount)
        assertFalse(viewModel.serverAddressState.value.isConfigured)
        assertTrue("清除配置后本进程尚未重启", viewModel.serverAddressState.value.pendingRestart)
    }

    /**
     * commit 失败必须如实回 [ServerAddressSaveOutcome.Failed]：不能清会话、不能催重启，
     * 也不能把界面推进到「新地址已生效」——磁盘上还是旧地址。
     */
    @Test
    fun `保存落盘失败时不报告已保存也不清会话`() = runTest(mainDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val fake = FakeSharedPreferences().apply { commitSucceeds = false }
        val store = ServerAddressStore(FakeSpContext(app, fake))
        val session = RecordingSessionManager()
        val viewModel = newViewModel(
            stub = GatedStub { releaseResponse() },
            sessionManager = session,
            serverAddressStore = store,
        )
        var outcome: ServerAddressSaveOutcome? = null

        viewModel.saveServerAddress("192.168.9.9:8080") { outcome = it }
        awaitUntil("保存应结束") { outcome != null }

        assertEquals(ServerAddressSaveOutcome.Failed, outcome)
        assertEquals("落盘失败不得清会话", 0, session.clearCount)
        assertNull("配置面必须停在未配置（落盘失败不得推进）", viewModel.serverAddressState.value.display)
        assertFalse("落盘失败不得催重启", viewModel.serverAddressState.value.pendingRestart)
        assertFalse("落盘失败不得留下待清标记", store.hasSessionResetPending())
    }

    @Test
    fun `清除配置落盘失败时保留原自定义地址且不清会话`() = runTest(mainDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val fake = FakeSharedPreferences()
        val store = ServerAddressStore(FakeSpContext(app, fake))
        val custom = ServerAddress.parse("192.168.9.9:8080")!!
        store.save(custom)
        fake.commitSucceeds = false
        val session = RecordingSessionManager()
        val viewModel = newViewModel(
            stub = GatedStub { releaseResponse() },
            sessionManager = session,
            serverAddressStore = store,
        )
        var outcome: ServerAddressSaveOutcome? = null

        viewModel.resetServerAddress { outcome = it }
        awaitUntil("清除配置应结束") { outcome != null }

        assertEquals(ServerAddressSaveOutcome.Failed, outcome)
        assertEquals("落盘失败不得清会话", 0, session.clearCount)
        assertTrue(viewModel.serverAddressState.value.isConfigured)
        assertEquals(custom.display, viewModel.serverAddressState.value.display)
    }

    /**
     * 地址落盘成功、但会话清理的 SP commit 失败：地址已经是新的，重启提示照常出现；
     * 启动兜底标记必须还在，下次启动补清旧 refresh token。
     */
    @Test
    fun `会话清理落盘失败时保留启动兜底标记`() = runTest(mainDispatcher) {
        val session = RecordingSessionManager().apply { clearSucceeds = false }
        val viewModel = newViewModel(GatedStub { releaseResponse() }, sessionManager = session)
        var outcome: ServerAddressSaveOutcome? = null

        viewModel.saveServerAddress("192.168.9.9:8080") { outcome = it }
        awaitUntil("保存应结束") { outcome != null }

        assertEquals(ServerAddressSaveOutcome.Saved, outcome)
        assertEquals(1, session.clearCount)
        assertTrue("会话未确认清干净时必须保留标记", newServerAddressStore().hasSessionResetPending())
        assertTrue("地址已换，仍应催重启", viewModel.serverAddressState.value.pendingRestart)
    }

    /** 在途闸门不能静默丢回调：重复触发要回 InProgress，UI 才能保持对话框并禁用按钮。 */
    @Test
    fun `保存在途时重复触发返回 InProgress`() = runTest(mainDispatcher) {
        val session = RecordingSessionManager()
        val viewModel = newViewModel(GatedStub { releaseResponse() }, sessionManager = session)
        val outcomes = mutableListOf<ServerAddressSaveOutcome>()

        viewModel.saveServerAddress("192.168.9.9:8080") { outcomes += it }
        assertTrue("首笔在途状态必须可观察", viewModel.addressChangeInProgress.value)
        viewModel.saveServerAddress("192.168.9.10:8080") { outcomes += it }

        assertTrue("重复触发必须回 InProgress", outcomes.contains(ServerAddressSaveOutcome.InProgress))
        awaitUntil("首笔保存应完成") {
            outcomes.contains(ServerAddressSaveOutcome.Saved)
        }
        assertEquals("清会话只走一次", 1, session.clearCount)
        assertFalse("收尾后闸门必须复位", viewModel.addressChangeInProgress.value)
    }

    // ==================== 清会话的线程契约 ====================
    //
    // endSession 的线程契约归 `AndroidUserSessionManagerTest` 一处；这里只剩一条断言，
    // 守的是**换址路径自己**的 IO 包装：它为拿布尔门控直调 clearSession（ADR-0052），
    // `withContext(Dispatchers.IO + NonCancellable)` 留在 SettingViewModel 里是有意的、
    // 非重复样板——删掉这层包装本用例必须红。

    /**
     * 换址保存流程的清会话不落主线程——守的是调用方自带的 IO 包装，不是 [UserSessionManager.endSession]。
     *
     * `launchAddressChange` 把「写盘 → 清会话」整段包进 `withContext(Dispatchers.IO + NonCancellable)`：
     * 清会话的两处 SP 写是同步 commit（改址后可能立刻强制退出进程，异步写会丢），同步磁盘写
     * 不得落在主线程。这条包装不走 endSession（要拿布尔门控失败分支），因此线程纪律必须由
     * 本用例单独钉住。保存与清除配置共用 [SettingViewModel.launchAddressChange]，一条断言覆盖两条入口。
     */
    @Test
    fun `换址保存流程的清会话不落在主线程`() = runTest(mainDispatcher) {
        val mainThread = Thread.currentThread()
        val session = RecordingSessionManager()
        val viewModel = newViewModel(GatedStub { releaseResponse() }, sessionManager = session)
        var outcome: ServerAddressSaveOutcome? = null

        viewModel.saveServerAddress("192.168.9.9:8080") { outcome = it }
        awaitUntil("保存应完成") { outcome != null }

        assertEquals(1, session.clearCount)
        assertNotSame(
            "换址路径自带的 IO 包装被删——clearSession 的同步 commit 落回了主线程",
            mainThread,
            session.clearThread,
        )
    }

    // ==================== 地址写入在途去重 ====================
    //
    // 保存/清除配置都在「写盘 → 清会话」这一笔里换址，连点或交叉点击并发两笔
    // 只会双份落盘、双份清会话。在途期间的重复触发必须被闸门挡掉（同 runLogout 的形态）。

    @Test
    fun `保存在途时连点只走一次保存流程`() = runTest(mainDispatcher) {
        val session = RecordingSessionManager()
        val viewModel = newViewModel(GatedStub { releaseResponse() }, sessionManager = session)
        val outcomes = mutableListOf<ServerAddressSaveOutcome>()

        viewModel.saveServerAddress("192.168.9.9:8080") { outcomes += it }
        viewModel.saveServerAddress("192.168.9.9:8080") { outcomes += it }

        awaitUntil("保存应完成") { outcomes.contains(ServerAddressSaveOutcome.Saved) }
        advanceUntilIdle()

        assertEquals("只允许一次真正的保存", 1, outcomes.count { it == ServerAddressSaveOutcome.Saved })
        assertTrue("重复触发应回 InProgress", outcomes.contains(ServerAddressSaveOutcome.InProgress))
        assertEquals("清会话只走一次", 1, session.clearCount)
    }

    @Test
    fun `保存在途时清除配置不并发第二笔写入`() = runTest(mainDispatcher) {
        // 先落一个自定义地址、再建 VM：store 在构造时冻结配置面，模拟「以自定义地址启动」。
        // 若在 VM 构造后再用另一个 store 写盘，VM 的配置面不会看到这次外部写入。
        newServerAddressStore().save(ServerAddress.parse("192.168.9.9:8080")!!)
        val session = RecordingSessionManager()
        val viewModel = newViewModel(GatedStub { releaseResponse() }, sessionManager = session)
        val outcomes = mutableListOf<ServerAddressSaveOutcome>()

        viewModel.saveServerAddress("192.168.9.10:8080") { outcomes += it }
        viewModel.resetServerAddress { outcomes += it }

        awaitUntil("保存应完成") { outcomes.contains(ServerAddressSaveOutcome.Saved) }
        advanceUntilIdle()

        assertEquals("地址写入在途期间不得并发第二笔", 1, outcomes.count { it == ServerAddressSaveOutcome.Saved })
        assertTrue("被挡下的第二笔应回 InProgress", outcomes.contains(ServerAddressSaveOutcome.InProgress))
        assertEquals(1, session.clearCount)
    }
}
