package com.ebook.common.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * 用户会话管理接口 - 认证状态的唯一 seam
 *
 * 设计原则：
 * - 纯 Kotlin 接口，无 Android 依赖
 * - 所有状态读写通过此接口
 * - 实现类处理持久化细节
 */
interface UserSessionManager {
    /**
     * 是否已登录
     */
    val isLoggedIn: StateFlow<Boolean>

    /**
     * 当前用户会话信息
     */
    val currentUser: StateFlow<UserSession?>

    /**
     * 保存会话信息（登录/注册建立会话：身份 + 双 token）
     *
     * @param session 用户会话信息
     * @param refreshToken 刷新token
     */
    suspend fun saveSession(session: UserSession, refreshToken: String)

    /**
     * 轮换双凭证（静默刷新专用）
     *
     * 只更新 access token（内存，经 TokenHolder）与 refresh token（落盘），
     * **绝不触碰用户身份字段**——refresh 语义只是续期凭证，不回填/改写身份
     * （见 ADR-0011：刷新端点不再返回 user，与身份解耦）。
     *
     * @param accessToken 新 access token
     * @param refreshToken 新 refresh token
     */
    suspend fun rotateCredentials(accessToken: String, refreshToken: String)

    /**
     * 清除会话信息（退出登录）。
     *
     * 内存态一定会被清空；返回值表示**三处镜像是否成功落盘/复位**。返回 false 时调用方
     * 必须保留「启动补清」标记（如换址场景的 `session_reset_pending`），下次启动重试，
     * 不能把落盘失败当成已清干净——旧 refresh token 仍可能在盘上。
     */
    fun clearSession(): Boolean

    /**
     * 清会话的挂起出口：登出/改密/会话过期/调试宿主等「清完即走」的收尾一律调本方法，
     * 只 await、不再各自包模板。三条契约由接口收口，实现必须逐条兑现：
     *
     * 1. **不落主线程**：[clearSession] 的两处 SP 写是同步 `commit`，本方法内部派发到
     *    `Dispatchers.IO` 执行——同步磁盘写不得落在主线程。
     * 2. **不可取消**：清理段在 [kotlinx.coroutines.NonCancellable] 内执行——清会话是
     *    安全收尾，页面/ViewModel 在收尾期间被销毁不得把它取消掉（否则用户点了退出、
     *    页面转回来仍是登录态）。
     * 3. **false 只记日志**：落盘失败时记一条 `Logger.w`（点名「旧 refresh token 可能残留」），
     *    不抛异常、不返回值——补清重试归启动兜底标记（ADR-0052），不在这里中断调用方的收尾。
     *
     * 仍需要 [clearSession] 的布尔返回值做门控的调用方（如换址流程要按失败分支保留
     * `session_reset_pending`、不催重启）**继续直接调 [clearSession]**，不经本方法。
     */
    suspend fun endSession()

    /**
     * 获取当前会话 token（运行时令牌）
     *
     * @return token 字符串，未登录或未保存 token 时返回 null
     */
    fun getToken(): String? = null

    /**
     * 获取保存的刷新token
     *
     * @return 刷新token字符串，未登录或未保存时返回 null
     */
    fun getRefreshToken(): String?
}
