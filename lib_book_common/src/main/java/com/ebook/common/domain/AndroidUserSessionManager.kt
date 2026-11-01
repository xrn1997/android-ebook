package com.ebook.common.domain

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import com.ebook.common.event.KeyCode
import com.ebook.common.repository.ProfileRepository
import com.ebook.common.util.SPUtil
import com.xrn1997.common.di.TokenHolder
import com.xrn1997.common.mvvm.model.BaseModel
import com.xrn1997.common.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UserSessionManager 的 Android 实现
 *
 * 职责：
 * - 管理内存中的状态（StateFlow）
 * - 持久化到 SharedPreferences
 * - 登录/登出时同步 token 到 lib_common 的 TokenHolder（AuthInterceptor 从那里取）
 * - 兼容 LoginInterceptor（同时写入 spUtils 文件）
 * - 清会话时一并失效用户会话的全部三处镜像：本类的内存态与 `user_session` SP（①）、
 *   `spUtils` 兼容键（②）、[ProfileRepository] 的进程内身份流（③）
 */
@Singleton
class AndroidUserSessionManager @Inject constructor(
    application: Application,
    private val tokenHolder: TokenHolder,
    private val profileRepository: ProfileRepository,
) : BaseModel(), UserSessionManager {

    private val sp by lazy {
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _isLoggedIn = MutableStateFlow(sp.getBoolean(KEY_IS_LOGGED_IN, false))
    override val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _currentUser = MutableStateFlow(loadSessionFromSp())
    override val currentUser: StateFlow<UserSession?> = _currentUser.asStateFlow()

    init {
        // 启动恢复：将持久化的 token 写入 TokenHolder，供 AuthInterceptor 使用
        tokenHolder.setToken(_currentUser.value?.token)
        // 安全债清理（一次性、幂等）：旧版本会把明文密码落盘到两处 SP，
        // 密码改为彻底不落盘后，升级设备上遗留的旧键在此抹除（见 ADR-0008）
        sp.edit { remove(LEGACY_KEY_PASSWORD) }
        SPUtil.remove(LEGACY_SP_PASSWORD)
    }

    override fun getRefreshToken(): String? {
        if (!isLoggedIn.value) return null
        return sp.getString(KEY_REFRESH_TOKEN, null)
    }

    override fun getToken(): String? = tokenHolder.token

    override suspend fun saveSession(session: UserSession, refreshToken: String) {
        // 更新内存状态
        _currentUser.value = session
        _isLoggedIn.value = true
        tokenHolder.setToken(session.token)

        // 写入 user_session 文件
        // 注：access token 只驻内存（TokenHolder），不落盘——冷启动时为空，
        // 首个请求 A0230 会用 refresh token 静默轮换（见 ADR-0011 / Q2 权衡）
        sp.edit {
            putBoolean(KEY_IS_LOGGED_IN, true)
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .putString(KEY_USER_ID, session.userId.toString())
                .putString(KEY_USERNAME, session.username)
                .putString(KEY_NICKNAME, session.nickname)
                .putString(KEY_AVATAR, session.avatar)
        }

        // 兼容 LoginInterceptor：同时写入 spUtils 文件
        SPUtil.put(KeyCode.Login.SP_IS_LOGIN, true)
        SPUtil.put(KeyCode.Login.SP_USERNAME, session.username)
        SPUtil.put(KeyCode.Login.SP_NICKNAME, session.nickname)
        SPUtil.put(KeyCode.Login.SP_USER_ID, session.userId)
        SPUtil.put(KeyCode.Login.SP_IMAGE, session.avatar)
    }

    override suspend fun rotateCredentials(accessToken: String, refreshToken: String) {
        // 仅会话已登录时轮换；无会话则无处续期
        val current = _currentUser.value ?: return
        // 只更新内存中的 token，身份字段原样保留
        _currentUser.value = current.copy(token = accessToken)
        tokenHolder.setToken(accessToken)

        // 只落盘 refresh token（access 只驻内存）；绝不触碰身份键与兼容 SP_* 键
        sp.edit {
            putString(KEY_REFRESH_TOKEN, refreshToken)
        }
    }

    /**
     * 清除会话：用户会话的三处镜像在此一次性全部失效。
     *
     * - ① `user_session` SP 与本类内存态（`_currentUser`/`_isLoggedIn`/TokenHolder）
     * - ② `spUtils` 的 `SP_IS_LOGIN`/`SP_USERNAME`/`SP_NICKNAME`/`SP_USER_ID`/`SP_IMAGE`
     *   （`LoginInterceptor` 读这份，漏清会出现「已登出但仍被放行」）
     * - ③ [ProfileRepository] 的进程内昵称/头像流
     *   （漏清的症状：会话已过期、token 已清，但「我的」页仍显示上一个身份）
     *
     * 调用方不得再自行补调 [ProfileRepository] 的清理方法。
     *
     * 两处 SP 写一律 `commit`（不是 `apply`）：改服务器地址的保存流程在清会话后可能
     * **立刻强制退出进程**，异步写会在退出时丢掉，旧 refresh token 就会活到下次启动
     * 并被发往新地址——安全债不能押在「进程还有时间 flush」上（ADR-0052）。
     *
     * **线程契约**：`commit` 是同步磁盘写，调用方不得让它落在主线程——保存/清除配置/登出/
     * 改密/会话过期各调用点都把本方法包进 `Dispatchers.IO + NonCancellable`：清会话是安全
     * 收尾，页面在切换线程/提交期间被销毁不得把它取消掉。唯一例外是启动兜底
     * （`BookApplication.applyPendingAddressSessionReset`）：它必须赶在首个请求之前完成，
     * 同步留在主线程是已声明的取舍。
     *
     * @return 两处 SP 的 `commit()` 均成功、内存镜像已复位时为 true。false 表示磁盘上可能
     *   仍留着旧 refresh token，调用方必须保留/不消费启动补清标记，下次启动重试。
     */
    override fun clearSession(): Boolean {
        _currentUser.value = null
        _isLoggedIn.value = false
        tokenHolder.clear()

        // 清除 user_session 文件；commit 保证落盘（见上方 KDOC：随后可能强制退出）
        val sessionFlushed = sp.edit()
            .remove(KEY_IS_LOGGED_IN)
            .remove(KEY_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USERNAME)
            .remove(KEY_NICKNAME)
            .remove(KEY_AVATAR)
            .commit()

        // 兼容 LoginInterceptor：同时清除 spUtils 文件；同样必须同步落盘
        val compatFlushed = SPUtil.clearAuthData(isCommit = true)

        // 镜像③：ProfileRepository 的 StateFlow 只在构造时读过一次 SP，
        // 上一步清了 SP 也不会反映到已存在的单例实例上，必须显式复位
        profileRepository.resetProfileState()

        return sessionFlushed && compatFlushed
    }

    /**
     * 挂起出口：三条契约（IO 派发 / NonCancellable / false 只记日志）见接口 KDoc。
     *
     * 实现只是包一层 [clearSession]——清会话的唯一实现仍是 [clearSession]，本方法不新增
     * 任何清理动作，只把「同步 commit 不落主线程、收尾不可取消、失败不中断调用方」
     * 三条调用纪律从各调用点的复制粘贴收进会话模块一处。
     */
    override suspend fun endSession() {
        withContext(Dispatchers.IO + NonCancellable) {
            if (!clearSession()) {
                Logger.w(TAG, "本地会话未确认清干净：旧 refresh token 可能残留，待下次启动补清")
            }
        }
    }

    private fun loadSessionFromSp(): UserSession? {
        if (!sp.getBoolean(KEY_IS_LOGGED_IN, false)) return null

        return UserSession(
            userId = sp.getString(KEY_USER_ID, null)?.toLongOrNull() ?: 0L,
            username = sp.getString(KEY_USERNAME, "") ?: "",
            nickname = sp.getString(KEY_NICKNAME, "") ?: "",
            avatar = sp.getString(KEY_AVATAR, "") ?: "",
            // access token 只驻内存：不留盘，冷启动 token 为空，交由首请求 A0230 静默轮换
            token = ""
        )
    }

    companion object {
        private const val TAG = "AndroidUserSessionManager"
        private const val PREFS_NAME = "user_session"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_TOKEN = "token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_AVATAR = "avatar"

        // 旧版遗留键（明文密码），仅启动清理用，不得再写入；常量已删，故用字面量钉死
        private const val LEGACY_KEY_PASSWORD = "password"
        private const val LEGACY_SP_PASSWORD = "sp_password"
    }
}
