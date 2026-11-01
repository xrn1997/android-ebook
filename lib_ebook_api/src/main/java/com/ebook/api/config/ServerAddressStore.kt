package com.ebook.api.config

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务器基址的唯一读取口：Retrofit 基址与认证 token 白名单都从这里取（ADR-0052）。
 *
 * **不内置任何默认地址**（ADR-0053）：应用发版包里没有可提取的主机值，状态只有两档——
 * 未配置（null）与用户自填的地址。持久化只有一份（SP `server_address`/`address`）。
 * 两个读面分得很清：[current] 是**配置值**（服务器地址页展示、「待重启」的左值），
 * [effective] 是**本进程生效值**（Retrofit 基址与 token 白名单，构造时冻结）。
 * 改地址只动配置面，所以**重启后才一致**；本类不提供任何运行期通知，
 * 避免让人误以为可以热切换。
 *
 * 未配置时 [effective] 返回 null：网络栈据此对服务端调用快速失败
 * （[ServerAddressNotConfiguredException]），不发请求。阅读/书源/下载不经服务端、不受影响。
 *
 * 换址/清除配置在写地址的**同一笔 commit** 里置 `session_reset_pending`：保存流程的
 * NonCancellable 只保住「本进程内」的写盘—清会话，协程在 IO 提交期间被取消、进程被杀、
 * 或重启前在旧地址重新登录，都只能靠这枚落盘标记在下次启动补清。启动路径先清会话再清标记，
 * 若死在两步之间标记仍在，下次启动重跑（清会话幂等）。
 */
@Singleton
class ServerAddressStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 本进程启动时的地址快照（null = 启动时未配置），[pendingRestart] 的比较基准，
     * 也是 [effective] 的全部内容。
     *
     * 在构造（Hilt 单例首次注入）时取一次，之后只读：改地址写的是 SP，这份快照不动，
     * 于是「配置与进程内实际生效值不一致」这件事在任何时刻都能被判出来。
     * 取值经 [healStoredAddress]：脏值在这里被顺手清掉。
     */
    private val processAddress: ServerAddress? = healStoredAddress()

    /**
     * 配置面（服务器地址页展示与 [pendingRestart] 的左值），只随**成功落盘**推进。
     *
     * 不直接读 SP 的原因：`commit()` 会先更新内存再写盘，写盘失败时 `getString` 已能读到
     * 新值。若 [current] 跟着 SP 走，失败会被读成成功，清除配置失败也会被读成「已清除」。
     * 缓存最近一次确认落盘的值，失败时保持旧值，调用方再据 [save]/[reset] 的返回值提示。
     */
    @Volatile
    private var configAddress: ServerAddress? = processAddress

    /**
     * 待清标记的缓存，理由与 [configAddress] 相同：commit 失败时 SP 内存面已变，
     * 直接读 SP 会把失败读成「标记已在」。只随成功落盘推进，进程重启后从盘上重建。
     */
    @Volatile
    private var sessionResetMarker: Boolean = sp.getBoolean(KEY_SESSION_RESET_PENDING, false)

    /**
     * 本进程**实际生效**的基址——网络栈（Retrofit 基址与 token 白名单）唯一允许读的一档。
     *
     * 读的是 [processAddress] 而不是 [current]，为的是让「首次注入的时刻」不再影响结果：
     * 无论 Retrofit 与白名单谁先被建、中间有没有发生过保存，两者拿到的永远是同一个主机。
     * null = 本进程启动时未配置，网络栈据此快速失败（不发请求）。
     * 服务器地址页要展示**配置值**，走 [current]，不要走这里。
     */
    fun effective(): ServerAddress? = processAddress

    /** 是否已配置服务器地址：false 即未配置（应用不带默认地址，见 ADR-0053）。 */
    val isConfigured: Boolean
        get() = current() != null

    /**
     * 「地址已改（含清除）、但本进程还没重启」——重启提示的可见性判据。
     *
     * 进程级（不是某次保存的回调）：转屏、离页再回来仍读得到 true，由 store 单例持有；
     * 真正重启后新进程的 [processAddress] 就是新值（或 null），这里自然回到 false，
     * 不需要额外清理。一次性回调做不到这一点：Activity 在 IO 写盘期间重建，
     * 回调随旧组合消失，强制重启就被绕过了。
     */
    val pendingRestart: Boolean
        get() = current() != processAddress

    /**
     * 当前**配置值**（服务器地址页展示与 `pendingRestart` 的左值）；网络栈请读 [effective]。
     *
     * 读的是「最近一次成功落盘」的缓存（见 [configAddress]），不是 SP 现值：`commit()` 失败时
     * SP 内存面已经变了，读 SP 会把失败读成成功。落盘值损坏时按未配置处理；
     * 脏键在注入时被 [healStoredAddress] 自愈清掉。null = 未配置。
     */
    fun current(): ServerAddress? = configAddress

    /**
     * 读落盘地址并顺手自愈脏值：解析不了的落盘串已按未配置生效（见 [current]），
     * 留在盘上只会永远占着键、误导后续排查。自愈**不是换址**——不置
     * [KEY_SESSION_RESET_PENDING]：行为上从头到尾都是未配置，没有旧地址的会话要清。
     * `apply` 而非 `commit`：自愈无时序压力，不值得为它同步落盘。
     */
    private fun healStoredAddress(): ServerAddress? {
        val stored = sp.getString(KEY_ADDRESS, null) ?: return null
        ServerAddress.parse(stored)?.let { return it }
        sp.edit { remove(KEY_ADDRESS) }
        return null
    }

    /**
     * 保存地址：返回**是否真的落盘成功**（`commit()` 的 Boolean）。
     *
     * 存 display 而非 baseUrl：baseUrl 会省略 scheme 默认端口（http :80），
     * 回读时无法区分「显式 80」与「未写端口」。
     * commit 而非 apply：保存成功后紧接着可能重启进程，异步落盘会在重启时丢写。
     * 标记与地址同一笔 commit：只落其一就会出现「地址换了没人清会话」或反之的错配。
     * 失败时 [configAddress] 保持旧值：调用方不得清会话、不得弹重启，并要提示保存失败。
     *
     * 同步阻塞（`commit()`），调用方必须在 IO 上下文执行；保存流程已包在
     * `Dispatchers.IO + NonCancellable` 里。这里不再自行切线程，避免双层调度。
     */
    fun save(address: ServerAddress): Boolean {
        val committed = sp.edit()
            .putString(KEY_ADDRESS, address.display)
            .putBoolean(KEY_SESSION_RESET_PENDING, true)
            .commit()
        if (committed) {
            configAddress = address
            sessionResetMarker = true
        }
        return committed
    }

    /**
     * 清除配置（回到未配置）：与 [save] 同语义（返回 commit 结果），同样算一次换址，
     * 标记必须与删除地址同一笔 commit 落盘；失败时保留原地址。同步阻塞，须在 IO 调用。
     */
    fun reset(): Boolean {
        val committed = sp.edit()
            .remove(KEY_ADDRESS)
            .putBoolean(KEY_SESSION_RESET_PENDING, true)
            .commit()
        if (committed) {
            configAddress = null
            sessionResetMarker = true
        }
        return committed
    }

    /**
     * 是否存在「地址已改、旧地址的会话尚未清」的待办标记（启动兜底的判据）。
     * 读 [sessionResetMarker] 缓存而非 SP 现值，理由同 [current]。
     */
    fun hasSessionResetPending(): Boolean = sessionResetMarker

    /**
     * 启动兜底清完会话后消费标记。
     *
     * 同步 `commit`：这是启动路径上对单个布尔的极小写入，异步写会让「进程紧接着被杀」
     * 重新留下已处理过的标记（不清会话，只是多跑一次），不值得为它引入时序。
     * 调用顺序由应用层（lib_book_common 的 `BookApplication`）保证：先清会话、后清标记。
     *
     * @return `commit()` 是否成功。失败时标记仍留在盘上，下次启动会重跑清会话（幂等）。
     */
    fun clearSessionResetPending(): Boolean {
        val committed = sp.edit().remove(KEY_SESSION_RESET_PENDING).commit()
        if (committed) sessionResetMarker = false
        return committed
    }

    private companion object {
        const val PREFS_NAME = "server_address"
        const val KEY_ADDRESS = "address"
        const val KEY_SESSION_RESET_PENDING = "session_reset_pending"
    }
}
