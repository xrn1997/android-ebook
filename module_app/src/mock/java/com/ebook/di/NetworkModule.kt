package com.ebook.di

import com.ebook.api.service.user.AccountPassword
import com.ebook.api.service.user.AccountProfile
import com.ebook.api.service.user.AccountSignIn
import com.ebook.api.service.user.SessionRefresh
import com.ebook.api.service.user.UserNetworkTest
import com.ebook.api.service.comment.CommentDataSource
import com.ebook.api.service.comment.CommentNetworkTest
import com.ebook.api.service.release.ReleaseDataSource
import com.ebook.api.service.release.ReleaseNetworkTest
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 集成构建 mock flavor 的网络绑定模块。
 *
 * 账号四个窄面（ADR-0059）各绑一条到同一个 `@Singleton UserNetworkTest`（类名是历史误称：
 * 它是 mock flavor 的实现，不是单元测试；载荷来自 lib_ebook_api/assets 的 JSON，与服务端
 * 线上载荷同形），无后端服务器即可跑通全链路；少绑一面就少一条能注入的路径，拆成四个实例
 * 会静默复制 mock 的内存态。与 real flavor 的同名模块互斥（flavor source set）。
 * [CommentDataSource] 仍绑整张 [CommentNetworkTest]（评论域不在本次切面范围内）。
 *
 * [ReleaseDataSource] 绑到 [ReleaseNetworkTest]：真实实现打的是 GitHub/Gitcode 的公开
 * Releases API，与后端无关，但 mock 构建（含 CI 与离线开发）不该依赖外网可达性，
 * 换成固定资产 `release_latest.json` 才能稳定演练「检查更新」弹窗与角标。
 * 注意该资产的形态是**平台原始 JSON**（无 RespDTO 信封），与另两份评论资产的形态不同。
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface NetworkModule {
    @Binds
    @Singleton
    fun bindSignIn(impl: UserNetworkTest): AccountSignIn

    @Binds
    @Singleton
    fun bindPassword(impl: UserNetworkTest): AccountPassword

    @Binds
    @Singleton
    fun bindProfile(impl: UserNetworkTest): AccountProfile

    @Binds
    @Singleton
    fun bindSessionRefresh(impl: UserNetworkTest): SessionRefresh

    @Binds
    fun bindComment(impl: CommentNetworkTest): CommentDataSource

    @Binds
    fun bindRelease(impl: ReleaseNetworkTest): ReleaseDataSource
}
