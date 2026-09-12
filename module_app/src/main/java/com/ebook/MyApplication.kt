package com.ebook

import com.ebook.common.BookApplication
import com.ebook.common.interceptor.LoginInterceptor
import com.ebook.common.repository.BookRepository
import com.ebook.source.sandbox.SandboxProcess
import com.therouter.router.addRouterReplaceInterceptor
import com.xrn1997.common.util.Logger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class MyApplication : BookApplication() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        // super 必须留着且放在最前：它里面是 Hilt 的注入与 lib_common 基类的初始化，跳过 super 是拿
        // 「不跑注入」去换一次框架契约的破坏。真正要防的是注入的**内容**，所以下面不再有 eager @Inject。
        super.onCreate()
        if (SandboxProcess.isInIsolatedProcess) return
        // 登录拦截
        addRouterReplaceInterceptor(LoginInterceptor())
        // 内容仓库对账（spec §4）：删书与导入中断留下的无主目录只有这一处回收入口。
        // 必须放在启动点——一个进程只跑一次，导入进行中跑会误删正在写入的目录（见
        // BookRepository.reconcileContentStore 的时机不变式）。对账失败不影响启动，
        // 顶多是本次没回收掉空间，故整体兜异常只记日志。
        appScope.launch {
            runCatching {
                // 晚到这一刻才建 DI 图：隔离进程在上面那道门就 return 了，永远走不到这里
                EntryPointAccessors.fromApplication(
                    this@MyApplication,
                    ContentStoreEntryPoint::class.java,
                ).bookRepository().reconcileContentStore()
            }.onFailure { Logger.e(TAG, "内容仓库对账失败（不影响启动）: ", it) }
        }
    }

    /**
     * Hilt entry point：与 BookApplication 的 ThemeModeManagerEntryPoint 同法。
     *
     * 存在的唯一理由是把 BookRepository 的构造从「Application 字段注入」挪进「用到它的那一刻」——
     * 字段注入在 `:js` 里也会跑，而那个进程读不到 Room 的库文件。
     *
     * 由此定下一条给将来的规则：**Application 上不许再有 eager `@Inject` 字段**。进程门写在
     * `super.onCreate()` 之后，而 Hilt 的字段注入发生在 `super.onCreate()` 里面（生成的
     * `Hilt_MyApplication.onCreate()` 先建组件、先注入，再调父类），所以再加一个注入字段等于
     * 把门挪到注入之前——沙箱进程起来即崩，而崩的现场藏在 binder 连接超时里，看不出来。
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ContentStoreEntryPoint {
        fun bookRepository(): BookRepository
    }

    private companion object {
        const val TAG = "MyApplication"
    }
}
