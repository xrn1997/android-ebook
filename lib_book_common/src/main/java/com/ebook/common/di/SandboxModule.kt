package com.ebook.common.di

import android.content.Context
import com.ebook.source.sandbox.HostCallbackRouter
import com.ebook.source.sandbox.JsLimits
import com.ebook.source.sandbox.JsSandboxClient
import com.ebook.source.sandbox.JsSandboxConnector
import com.ebook.source.sandbox.JsSandboxHost
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * 脚本沙箱的进程级装配。
 *
 * 落在 `lib_book_common` 而不是 `lib_book_source`：`lib_book_source` 今天没有 Hilt/KSP 依赖，
 * 为一个 `@Provides` 给它上两行插件不值，而这里三样输入（App 上下文、`@Named("source")` 纯净
 * 客户端、连接器）全现成。**客户端不在这儿预热**——第一次真执行才 bind 沙箱进程，
 * 于是冷启动不付任何跨进程代价，没用到脚本的源连不上沙箱也不算失败。
 *
 * 注入方一律用**可空**参数接它（`BookSourceManagerImpl` 的 `jsHost: JsSandboxHost?`）：
 * 生产恒有值，测试与独立运行路径没有 binding 时按 null 走「JS 待执行」的原行为。
 */
@Module
@InstallIn(SingletonComponent::class)
object SandboxModule {

    @Provides
    @Singleton
    fun provideJsSandboxHost(
        @ApplicationContext context: Context,
        @Named("source") sourceClient: OkHttpClient,
    ): JsSandboxHost {
        val limits = JsLimits()
        // 转子必须在客户端之前建好：`hostHandler` 是构造期定死的（Task 6），
        // 而它指向的那个代理每次调用都在换——顺序写反就是「客户端读的是另一个转子」。
        // 下面这份 router 必须**显式传给** JsSandboxHost：省略它会由默认值另起一个转子，
        // 两者不是同一个对象时每一次回调都回「主进程没有正在进行的脚本任务」（装机清单里专验这条）
        val router = HostCallbackRouter()
        return JsSandboxHost(
            client = JsSandboxClient(
                openChannel = { JsSandboxConnector(context, limits).open() },
                limits = limits,
                hostHandler = router,
            ),
            baseClient = sourceClient,
            limits = limits,
            router = router,
        )
    }
}
