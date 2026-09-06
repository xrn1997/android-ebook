package com.ebook

import com.ebook.common.BookApplication
import com.ebook.common.interceptor.LoginInterceptor
import com.ebook.common.repository.BookRepository
import com.therouter.router.addRouterReplaceInterceptor
import com.xrn1997.common.util.Logger
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class MyApplication : BookApplication() {

    /** 内容仓库（书架 + 章文件目录）的持有者，对账用它取活书集合与仓库实例 */
    @Inject lateinit var bookRepository: BookRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 登录拦截
        addRouterReplaceInterceptor(LoginInterceptor())
        // 内容仓库对账（spec §4）：删书与导入中断留下的无主目录只有这一处回收入口。
        // 必须放在启动点——一个进程只跑一次，导入进行中跑会误删正在写入的目录（见
        // BookRepository.reconcileContentStore 的时机不变式）。对账失败不影响启动，
        // 顶多是本次没回收掉空间，故整体兜异常只记日志。
        appScope.launch {
            runCatching { bookRepository.reconcileContentStore() }
                .onFailure { Logger.e(TAG, "内容仓库对账失败（不影响启动）: ", it) }
        }
    }

    private companion object {
        const val TAG = "MyApplication"
    }
}
