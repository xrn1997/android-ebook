package com.ebook.me.repository

import com.ebook.me.util.AppVersion

/**
 * [ReleaseCheckController] 需要的环境面：限频判定、成功落盘与角标派生。
 *
 * 生产实现是 [ReleaseStateStore]（SharedPreferences + PackageManager），测试用内存假件。
 * 只暴露值与方法、不暴露 Context：判据（「要不要检查」「判不判得出结论」）因此可以在纯 JVM
 * 下锁死，Android 读写留在 adapter 里。
 */
interface ReleaseState {
    /** 本地当前版本的可比较形态（不可解析为 null）。 */
    val currentVersion: AppVersion?

    /** 上次成功检查到的 tag 是否高于当前装机版本（现场派生，不存布尔量）。 */
    val hasUpdateAvailable: Boolean

    /** 距上次成功检查是否已到静默刷新窗口。 */
    fun shouldAutoRefresh(): Boolean

    /** 记录一次成功检查到的远端 tag（只在该结论可判定时调用）。 */
    fun markCheckSuccess(remoteTag: String)
}
