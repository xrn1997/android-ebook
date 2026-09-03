package com.ebook.common.domain

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.ebook.common.event.KeyCode
import com.ebook.common.repository.ProfileRepository
import com.ebook.common.util.SPUtil
import com.xrn1997.common.BaseApplication
import com.xrn1997.common.di.TokenHolder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

        manager.clearSession()

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

    private companion object {
        // user_session SP（AndroidUserSessionManager 私有常量的字面量镜像）
        const val PREFS_USER_SESSION = "user_session"
        const val KEY_IS_LOGGED_IN = "is_logged_in"
        const val KEY_REFRESH_TOKEN = "refresh_token"
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
