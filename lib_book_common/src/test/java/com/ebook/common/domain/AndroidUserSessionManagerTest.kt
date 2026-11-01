package com.ebook.common.domain

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.ebook.api.testfixture.FakeSharedPreferences
import com.ebook.common.event.KeyCode
import com.ebook.common.repository.ProfileRepository
import com.ebook.common.util.SPUtil
import com.xrn1997.common.BaseApplication
import com.xrn1997.common.di.TokenHolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [AndroidUserSessionManager]（真实现）的回归测试，Robolectric + 真实 SharedPreferences。
 *
 * 锁住的缺陷：`clearSession()` 此前只覆盖用户会话的镜像 ①（`user_session` SP + 本类内存态）
 * 与 ②（`spUtils` 兼容键），漏掉 ③——[ProfileRepository] 里进程内的昵称/头像 StateFlow。
 * 症状是会话已过期、token 已清，但「我的」页仍显示上一个身份的昵称与头像。
 * 现在 `clearSession()` 末尾调 `profileRepository.resetProfileState()` 收口。
 *
 * 为什么必须 Robolectric：本类构造要 [Application] 并直接用 SharedPreferences，
 * [ProfileRepository] 与 [SPUtil] 也静态读写 Android SP，纯 JVM 起不来。
 * 注意同目录下的 [UserSessionManagerTest] 测的是 `FakeUserSessionManager`（假件自洽，
 * 假件里根本没有第三处镜像），真实现此前零覆盖——这正是漏掉 ③ 长期没被发现的原因。
 *
 * 三处镜像的键名/字段均为生产实现里的私有常量，此处按字面钉死（改动实现需同步改这里）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidUserSessionManagerTest {

    private lateinit var application: Application

    /** 镜像①：`user_session` SP 文件（PREFS_NAME 为实现内私有常量，此处取同名字面量） */
    private lateinit var sessionSp: SharedPreferences

    private lateinit var tokenHolder: TokenHolder
    private lateinit var profileRepository: ProfileRepository
    private lateinit var manager: AndroidUserSessionManager

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        // SPUtil 走 lib_common 的静态 BaseApplication.context（生产由 BaseApplication.onCreate
        // 赋值）。Robolectric 装的是裸 Application，这里手动补齐，等价于「Application 已启动完成」。
        BaseApplication.context = application

        sessionSp = application.getSharedPreferences(PREFS_USER_SESSION, Context.MODE_PRIVATE)
        // Robolectric 的 sandbox ClassLoader 在同一 @Config 下被复用，SPUtil 是 Kotlin object、
        // 其 spMap 静态缓存可能跨用例存活；每个用例显式清空两处 SP，保证起点干净。
        sessionSp.edit().clear().commit()
        SPUtil.clear()
    }

    /**
     * 按生产装配顺序建出被测三件套。
     *
     * 必须在测试自己完成 SP 播种之后再调用：[ProfileRepository] 与本类都在构造期读一次 SP，
     * 且本类的 init 还负责抹除旧版残留的明文密码键（ADR-0008）。
     */
    private fun createSubjects() {
        tokenHolder = TokenHolder()
        profileRepository = ProfileRepository()
        manager = AndroidUserSessionManager(application, tokenHolder, profileRepository)
    }

    private fun session(token: String = ACCESS_TOKEN) = UserSession(
        userId = USER_ID,
        username = USERNAME,
        nickname = NICKNAME,
        avatar = AVATAR,
        token = token,
    )

    // ===== 镜像建立 =====

    @Test
    fun `saveSession 同时建立三处镜像与内存态`() = runBlocking {
        createSubjects()

        manager.saveSession(session(), REFRESH_TOKEN)

        // ① 本类内存态 + user_session SP
        assertTrue(manager.isLoggedIn.value)
        assertNotNull(manager.currentUser.value)
        assertEquals(session(), manager.currentUser.value)
        // access token 只驻内存（TokenHolder），不落盘
        assertEquals(ACCESS_TOKEN, tokenHolder.token)
        assertEquals(ACCESS_TOKEN, manager.getToken())
        assertEquals(REFRESH_TOKEN, manager.getRefreshToken())
        // ② spUtils 兼容键（LoginInterceptor 读这份）
        assertEquals(true, SPUtil.get(KeyCode.Login.SP_IS_LOGIN, false))
        assertEquals(USERNAME, SPUtil.get(KeyCode.Login.SP_USERNAME, ""))
        assertEquals(NICKNAME, SPUtil.get(KeyCode.Login.SP_NICKNAME, ""))
        assertEquals(USER_ID, SPUtil.get(KeyCode.Login.SP_USER_ID, 0L))
        assertEquals(AVATAR, SPUtil.get(KeyCode.Login.SP_IMAGE, ""))
    }

    // ===== 核心：镜像③（ProfileRepository 的进程内身份流） =====

    @Test
    fun `clearSession 复位 ProfileRepository 的昵称与头像流`() = runBlocking {
        // 关键顺序：ProfileRepository 必须在 clearSession 之前构造并建立好身份状态。
        // 它的两个 StateFlow 只在构造时读一次 SP——若等到 clearSession 之后再构造，
        // 读到的是已被清空的 SP，本用例的断言在 resetProfileState() 被删掉的情况下
        // 依然会通过（假绿），测试就完全失去价值。
        createSubjects()
        manager.saveSession(session(), REFRESH_TOKEN)
        // 模拟「我的」页拉到身份后的渲染路径：内存流与 SP 一起写
        profileRepository.updateNickname(NICKNAME)
        profileRepository.updatePicture(AVATAR)
        assertEquals(NICKNAME, profileRepository.nickname.value)
        assertEquals(AVATAR, profileRepository.pictureUrl.value)

        assertTrue("两处 SP 的 commit 成功时必须报告已清干净", manager.clearSession())

        // 镜像③：SP 已被清不代表单例实例上的流跟着变，必须显式复位
        assertEquals("", profileRepository.nickname.value)
        assertEquals("", profileRepository.pictureUrl.value)
    }

    // ===== 镜像①② 的清理 =====

    @Test
    fun `clearSession 清掉另两处镜像并回到未登录值`() = runBlocking {
        createSubjects()
        manager.saveSession(session(), REFRESH_TOKEN)

        manager.clearSession()

        // ① 内存态与 TokenHolder
        assertFalse(manager.isLoggedIn.value)
        assertNull(manager.currentUser.value)
        assertNull(tokenHolder.token)
        assertNull(manager.getToken())
        assertNull(manager.getRefreshToken())
        // refresh token 是真的从 user_session 里删掉了，而不是只被 isLoggedIn 闸门挡住
        assertFalse(sessionSp.contains(KEY_REFRESH_TOKEN))
        assertNull(sessionSp.getString(KEY_REFRESH_TOKEN, null))
        assertFalse(sessionSp.contains(KEY_IS_LOGGED_IN))
        // ② spUtils 回到未登录值
        assertEquals(false, SPUtil.get(KeyCode.Login.SP_IS_LOGIN, false))
        assertEquals("", SPUtil.get(KeyCode.Login.SP_USERNAME, ""))
        assertEquals("", SPUtil.get(KeyCode.Login.SP_NICKNAME, ""))
        assertEquals(0L, SPUtil.get(KeyCode.Login.SP_USER_ID, 0L))
        assertEquals("", SPUtil.get(KeyCode.Login.SP_IMAGE, ""))
    }

    @Test
    fun `clearSession 后重建实例仍为未登录`() = runBlocking {
        createSubjects()
        manager.saveSession(session(), REFRESH_TOKEN)
        manager.clearSession()

        // 模拟进程重启：重新启动恢复路径（构造期读 SP）
        val restarted = AndroidUserSessionManager(application, TokenHolder(), ProfileRepository())

        assertFalse(restarted.isLoggedIn.value)
        assertNull(restarted.currentUser.value)
        assertNull(restarted.getRefreshToken())
    }

    // ===== commit 失败面：返回值必须能被调用方看见 =====
    //
    // SharedPreferences 先改内存再写盘：commit 失败时读回仍是新值，调用方只能靠返回值判失败
    // （ADR-0052：换址路径据此保留启动补清标记、下次启动重试）。`sessionFlushed && compatFlushed`
    // 两个操作数各钉一例：只钉一侧的话，把 && 改成只看另一侧都不会红（假绿）。

    /**
     * 失败定位在**会话侧**：`user_session` 的 commit 必失败（假件），spUtils 侧仍走真实 SP、
     * 五个兼容键照常清掉（右操作数为 true）——返回的 false 只可能来自会话侧那笔失败的 commit。
     */
    @Test
    fun `user_session 的 commit 失败时清会话返回 false`() = runBlocking {
        val failing = FakeSharedPreferences().apply { commitSucceeds = false }
        val managerOverFailingSp = AndroidUserSessionManager(
            SessionSpApplication(application, PREFS_USER_SESSION, failing),
            TokenHolder(),
            ProfileRepository(),
        )
        managerOverFailingSp.saveSession(session(), REFRESH_TOKEN)

        assertFalse("会话侧写盘失败必须报 false", managerOverFailingSp.clearSession())

        assertEquals("清会话只在 user_session 上写一笔 commit", 1, failing.commitCount)
        assertEquals(EXPECTED_SESSION_KEYS + KEY_TOKEN, failing.lastCommittedKeys)
        assertEquals(
            "spUtils 侧照常清干净，false 只可能来自会话侧",
            false,
            SPUtil.get(KeyCode.Login.SP_IS_LOGIN, false),
        )
    }

    /**
     * 失败定位在 **spUtils 侧**：`user_session` 走真实 SP（refresh token 真的删掉了），
     * spUtils 的 commit 必失败——返回的 false 只可能来自兼容侧。
     *
     * SPUtil 的 `spMap` 是 object 级静态缓存、生产没有重置入口（不为测试加参数），
     * 这里经反射把 `spUtils` 一项换成假件、`finally` 原样换回，不污染其他用例；
     * 缓存字段名与 SP 文件名同按字面钉死（改实现需同步改这里）。
     */
    @Test
    @Suppress("UNCHECKED_CAST")
    fun `spUtils 的 commit 失败时清会话返回 false`() = runBlocking {
        createSubjects()
        manager.saveSession(session(), REFRESH_TOKEN)

        val failing = FakeSharedPreferences().apply { commitSucceeds = false }
        val spMap = SPUtil::class.java.getDeclaredField("spMap")
            .apply { isAccessible = true }
            .get(SPUtil) as MutableMap<String, SharedPreferences>
        val previous = spMap.put(SP_UTILS_NAME, failing)
        try {
            assertFalse("spUtils 侧写盘失败必须报 false", manager.clearSession())
        } finally {
            if (previous != null) spMap[SP_UTILS_NAME] = previous else spMap.remove(SP_UTILS_NAME)
        }

        assertEquals("clearAuthData 只在 spUtils 上写一笔 commit", 1, failing.commitCount)
        assertEquals(EXPECTED_SP_UTILS_KEYS, failing.lastCommittedKeys)
        assertFalse("会话侧是真实 SP，refresh token 已真正删掉", sessionSp.contains(KEY_REFRESH_TOKEN))
    }

    // ===== 密码不落盘（ADR-0008） =====

    /**
     * 密码不落盘的防回归。
     *
     * `saveSession` 的入参（[UserSession] + refreshToken）里根本没有密码字段，
     * 所以「断言不存在值为该密码的条目」无法只靠喂一个密码来表达；这里的等效锁法是两层：
     * 1. 落盘键集合**恰为白名单**——将来谁把密码（或任何新字段）加进持久化，本用例会红；
     * 2. 播种旧版本残留的明文密码键，断言构造期的一次性清理（init）把它抹掉，
     *    并断言两处 SP 里不存在值等于该明文密码的条目。
     */
    @Test
    fun `明文密码既不落盘也已在启动清理中被抹除`() = runBlocking {
        // 播种旧版残留（升级设备场景）：user_session.password 与 spUtils.sp_password
        sessionSp.edit().putString(KEY_LEGACY_PASSWORD, PLAINTEXT_PASSWORD).commit()
        SPUtil.put(KEY_SP_LEGACY_PASSWORD, PLAINTEXT_PASSWORD)

        createSubjects()

        assertFalse(sessionSp.contains(KEY_LEGACY_PASSWORD))
        assertFalse(SPUtil.contains(KEY_SP_LEGACY_PASSWORD))

        manager.saveSession(session(), REFRESH_TOKEN)

        assertEquals(EXPECTED_SESSION_KEYS, sessionSp.all.keys)
        assertEquals(EXPECTED_SP_UTILS_KEYS, SPUtil.getAll().keys)
        // access token 不落盘：user_session 里没有任何一处存着它
        assertTrue(sessionSp.all.values.none { it == ACCESS_TOKEN })
        // 明文密码不出现在任何值里
        assertTrue(sessionSp.all.values.none { it == PLAINTEXT_PASSWORD })
        assertTrue(SPUtil.getAll().values.none { it == PLAINTEXT_PASSWORD })
    }

    // ===== endSession：挂起出口的三条契约（IO 派发 / NonCancellable / false 只记日志） =====
    //
    // 调用点（登出/改密/会话过期/调试宿主）此前各自复制八行 withContext(IO + NonCancellable)
    // 模板；契约收进 endSession 后，线程与取消语义只需在真实现上钉一次。

    /**
     * 取消不得打断清理段：`launch → 立即 cancel` 模拟「用户点了退出、页面在收尾期间被销毁」。
     *
     * 取消时机用 [entered] 门控钉在 **endSession 已进入、清理段尚未跑完** 的窗口上——
     * 无门控时 cancel 可能落在协程启动之前，`clearSession` 根本没被调用，断言全绿也什么都没证明
     * （假绿）。取消后三处镜像必须仍被清空：这才是 NonCancellable 契约本身。
     */
    @Test
    fun `endSession 在调用协程被取消后仍完成三处镜像清理`() = runBlocking {
        createSubjects()
        manager.saveSession(session(), REFRESH_TOKEN)
        // 镜像③先建立身份状态，防止 resetProfileState 被删仍绿（同 clearSession 用例的防假绿法）
        profileRepository.updateNickname(NICKNAME)
        profileRepository.updatePicture(AVATAR)

        val entered = CompletableDeferred<Unit>()
        val job = launch {
            // entered 完成后下一行就是 endSession：cancel 落在清理段中途而非启动前
            entered.complete(Unit)
            manager.endSession()
        }
        entered.await()
        job.cancelAndJoin()

        // ① 内存态 + TokenHolder + user_session SP
        assertFalse(manager.isLoggedIn.value)
        assertNull(manager.currentUser.value)
        assertNull(tokenHolder.token)
        assertFalse(sessionSp.contains(KEY_REFRESH_TOKEN))
        // ② spUtils 兼容键
        assertEquals(false, SPUtil.get(KeyCode.Login.SP_IS_LOGIN, false))
        // ③ ProfileRepository 进程内身份流
        assertEquals("", profileRepository.nickname.value)
        assertEquals("", profileRepository.pictureUrl.value)
    }

    /**
     * false 路径不得抛：会话侧 commit 被假件钉死为失败时，endSession 必须正常返回。
     *
     * 落盘失败是「旧 refresh token 可能残留」的降级信号，处置是记日志 + 交给启动兜底标记
     * （ADR-0052）；在这里抛异常会把登出/改密/会话过期的收尾炸断在半路。
     * 失败定位在会话侧（spUtils 走真实 SP、照常清干净），与 clearSession 的 false 面同构。
     */
    @Test
    fun `endSession 在会话侧写盘失败时不抛异常`() = runBlocking {
        val failing = FakeSharedPreferences().apply { commitSucceeds = false }
        val managerOverFailingSp = AndroidUserSessionManager(
            SessionSpApplication(application, PREFS_USER_SESSION, failing),
            TokenHolder(),
            ProfileRepository(),
        )
        managerOverFailingSp.saveSession(session(), REFRESH_TOKEN)
        val commitsBefore = failing.commitCount

        // 抛异常即红：这里没有 expected-exception，「正常返回」本身就是断言
        managerOverFailingSp.endSession()

        assertFalse(managerOverFailingSp.isLoggedIn.value)
        assertNull(managerOverFailingSp.currentUser.value)
        assertEquals("endSession 只在会话侧写一笔 commit", commitsBefore + 1, failing.commitCount)
    }

    /**
     * 线程契约：endSession 必须把 [clearSession] 的同步 `commit` 派到 IO——同步磁盘写
     * 不得落在调用方线程（生产上是主线程）。各调用点此前各自复制的线程断言收敛到此处一处。
     *
     * 观测点是会话侧 SP 的 `commit` 落点（[SessionSpApplication] 换入记录线程的包装）；
     * 未登录时调用也照常走一笔清理 commit，无需先播种会话。
     */
    @Test
    fun `endSession 的同步 commit 不落在调用线程`() = runBlocking {
        val recording = CommitThreadRecordingSp(FakeSharedPreferences())
        val managerOverRecordingSp = AndroidUserSessionManager(
            SessionSpApplication(application, PREFS_USER_SESSION, recording),
            TokenHolder(),
            ProfileRepository(),
        )
        val callingThread = Thread.currentThread()

        managerOverRecordingSp.endSession()

        assertNotNull("清理必须真的落过一笔 commit", recording.lastCommitThread)
        assertNotSame("同步 commit 不得落在调用线程", callingThread, recording.lastCommitThread)
    }

    /** 只替换指定 SP 文件的 [Application]：其余行为委托给 Robolectric 的真实 Application。 */
    private class SessionSpApplication(
        private val delegate: Application,
        private val spName: String,
        private val sp: SharedPreferences,
    ) : Application() {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            if (name == spName) sp else delegate.getSharedPreferences(name, mode)
    }

    /**
     * 记录每笔 `commit` 落在哪个线程的 [SharedPreferences] 包装（其余行为原样委托）。
     * endSession 的「不落主线程」契约靠它观测——契约收进接口后只剩这一处断言。
     */
    private class CommitThreadRecordingSp(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        @Volatile
        var lastCommitThread: Thread? = null
            private set

        override fun edit(): SharedPreferences.Editor = RecordingEditor(delegate.edit())

        private inner class RecordingEditor(
            private val delegate: SharedPreferences.Editor,
        ) : SharedPreferences.Editor by delegate {
            // 链式改写必须回到本包装上：委托的 remove/clear 返回的是内部 editor，
            // 链尾的 commit() 就会绕过本类——clearSession 的 `edit().remove(...)…commit()` 正是这种链
            override fun remove(key: String?): SharedPreferences.Editor {
                delegate.remove(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                delegate.clear()
                return this
            }

            override fun commit(): Boolean {
                lastCommitThread = Thread.currentThread()
                return delegate.commit()
            }
        }
    }

    private companion object {
        // user_session SP（AndroidUserSessionManager 私有常量的字面量镜像）
        const val PREFS_USER_SESSION = "user_session"
        const val KEY_IS_LOGGED_IN = "is_logged_in"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        // clearSession 顺带删除、saveSession 从不写入的 token 键（实现私有常量的字面量镜像）
        const val KEY_TOKEN = "token"
        // SPUtil.DEFAULT_SP_NAME 的字面量镜像
        const val SP_UTILS_NAME = "spUtils"
        const val KEY_USER_ID = "user_id"
        const val KEY_USERNAME = "username"
        const val KEY_NICKNAME = "nickname"
        const val KEY_AVATAR = "avatar"

        // 旧版明文密码键（仅启动清理用，实现里同样是字面量）
        const val KEY_LEGACY_PASSWORD = "password"
        const val KEY_SP_LEGACY_PASSWORD = "sp_password"

        const val PLAINTEXT_PASSWORD = "S3cr3t-Pa55!"

        const val USER_ID = 1001L
        const val USERNAME = "login_name"
        const val NICKNAME = "展示昵称"
        const val AVATAR = "https://example.com/avatar.png"
        const val ACCESS_TOKEN = "access-token-abc"
        const val REFRESH_TOKEN = "refresh-token-xyz"

        val EXPECTED_SESSION_KEYS =
            setOf(KEY_IS_LOGGED_IN, KEY_REFRESH_TOKEN, KEY_USER_ID, KEY_USERNAME, KEY_NICKNAME, KEY_AVATAR)
        val EXPECTED_SP_UTILS_KEYS = setOf(
            KeyCode.Login.SP_IS_LOGIN,
            KeyCode.Login.SP_USERNAME,
            KeyCode.Login.SP_NICKNAME,
            KeyCode.Login.SP_USER_ID,
            KeyCode.Login.SP_IMAGE,
        )
    }
}
