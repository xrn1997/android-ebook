package com.ebook.me.repository

import android.content.Context
import android.content.SharedPreferences
import com.ebook.me.util.AppVersion
import com.ebook.me.util.isOlderThan
import com.xrn1997.common.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 更新状态的本地持久化槽（SharedPreferences）。
 *
 * 职责：记住「上次检查到的远端 tag 与检查时间」，供版本行角标与 7 天限频判断使用；
 * 不发起网络请求、不含任何解析/比较之外的策略。
 *
 * 设计要点：
 * - **只存事实，不存结论**：落盘的是远端 tag 与成功检查时间戳，「是否有新版本」
 *   （[hasUpdateAvailable]）每次由 tag 与当前装机版本现场派生。存结论布尔量的写法会让
 *   用户升级安装后角标继续挂到下次检查（最长 7 天），而 tag 是稳定的比较输入，
 *   派生一次就自动纠正。
 * - **7 天限频**：[shouldAutoRefresh] 判断「距上次成功检查是否 ≥ [AUTO_REFRESH_INTERVAL_MILLIS]」，
 *   用于设置页进入时的静默刷新——克制，不每次进页都发网络请求。窗口算术抽在
 *   [isRefreshDue] 这个纯函数上，便于单测锁边界。
 * - **失败不覆盖**：只有成功检查才调 [markCheckSuccess]；失败路径不调它即保有上次 tag
 *   与上次检查时间（限频也因此不会被失败提前复位）。「tag 解析不出版本」也算失败，
 *   由调用方（[com.ebook.me.mvvm.viewmodel.SettingViewModel]）把关。
 *
 * 本类只做读写与时间判断，不含网络与 failover 策略（那是 [ReleaseRepository] 的事）。
 */
@Singleton
class ReleaseStateStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 上次成功检查到的远端 tag（如 "V1.3.0"）；从未成功检查过为空串。
     *
     * 它既是角标比较的输入，也是「上次检查到什么」的唯一落盘事实——展示与复算都从这里出发。
     */
    val lastCheckedTag: String
        get() = prefs.getString(KEY_LAST_CHECKED_TAG, "") ?: ""

    /**
     * 本地当前版本号（取自 PackageManager，作比较与展示的基准；取不到返回 null）。
     *
     * **设置页链路只在这一处**读 PackageManager：版本行要显示的版本号经
     * [com.ebook.me.mvvm.viewmodel.SettingViewModel] 转发，不再在页面里各读一遍而漂移。
     */
    val currentVersionName: String? by lazy {
        runCatching {
            val name = context.packageManager.getPackageInfo(context.packageName, 0).versionName
            name?.takeIf { it.isNotBlank() }
        }.onFailure { Logger.w(TAG, "读取本地 versionName 失败：${it.message}") }
            .getOrNull()
    }

    /**
     * 本地当前版本（[currentVersionName] 的解析形态，比较用；不可解析返回 null）。
     */
    val currentVersion: AppVersion? by lazy { currentVersionName?.let(AppVersion::parse) }

    /**
     * 是否「已有可更新的新版本」：上次检查到的 tag 高于当前装机版本。
     *
     * 每次现场比较而非读缓存结论——升级安装后重进设置页，角标就该消失。
     * 三个输入任一缺失（从未检查成功 / 本地版本读不到 / tag 不可解析）都按「无更新」处理。
     */
    val hasUpdateAvailable: Boolean
        get() {
            val remote = AppVersion.parse(lastCheckedTag) ?: return false
            val current = currentVersion ?: return false
            return current.isOlderThan(remote)
        }

    /**
     * 距上次成功检查是否已超过 [AUTO_REFRESH_INTERVAL_MILLIS]（决定进设置页时是否静默刷新）。
     */
    fun shouldAutoRefresh(nowMillis: Long = System.currentTimeMillis()): Boolean =
        isRefreshDue(
            lastSuccessMillis = prefs.getLong(KEY_LAST_SUCCESS_CHECK_TIME, 0L),
            nowMillis = nowMillis,
        )

    /**
     * 记录一次成功检查到的远端 tag。
     *
     * 仅在检查成功且 tag 可判定版本时调用；失败或 tag 不可解析时调用方**不**调本方法，
     * 即保有上次结论与上次检查时间。
     *
     * @param remoteTag 远端最新 tag，原样存（比较与展示各自归一化）
     */
    fun markCheckSuccess(remoteTag: String) {
        prefs.edit()
            .putString(KEY_LAST_CHECKED_TAG, remoteTag)
            .putLong(KEY_LAST_SUCCESS_CHECK_TIME, System.currentTimeMillis())
            .apply()
    }

    internal companion object {
        private const val TAG = "ReleaseStateStore"
        private const val PREFS_NAME = "release_state"

        private const val KEY_LAST_CHECKED_TAG = "last_checked_tag"
        private const val KEY_LAST_SUCCESS_CHECK_TIME = "last_success_check_time"

        /**
         * 静默刷新的限频间隔：7 天。距上次成功检查不足该时长则不做静默刷新。
         */
        internal const val AUTO_REFRESH_INTERVAL_MILLIS = 7L * 24 * 60 * 60 * 1000

        /**
         * 限频窗口的纯算术：上次成功检查在 [lastSuccessMillis]（0 表示从未检查），
         * 现在是否已到 [intervalMillis] 窗口。
         *
         * 抽成纯函数是因为本类要 Context，而 module_me 的单测只有 JUnit（无 Robolectric），
         * 窗口边界这种「差一天就不该多发请求」的判断值得被锁住。
         */
        fun isRefreshDue(
            lastSuccessMillis: Long,
            nowMillis: Long,
            intervalMillis: Long = AUTO_REFRESH_INTERVAL_MILLIS,
        ): Boolean =
            lastSuccessMillis == 0L || nowMillis - lastSuccessMillis >= intervalMillis
    }
}
