package com.ebook.di

import com.ebook.api.service.comment.CommentDataSource
import com.ebook.api.service.comment.CommentNetworkTest
import com.ebook.api.service.release.ReleaseDataSource
import com.ebook.api.service.release.ReleaseNetworkTest
import com.ebook.api.service.user.UserDataSource
import com.ebook.api.service.user.UserNetworkTest
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 集成构建 mock flavor 的网络绑定模块。
 *
 * 将 UserDataSource/CommentDataSource 绑定到内存 mock 实现（UserNetworkTest/
 * CommentNetworkTest，载荷来自 lib_ebook_api/assets 的 JSON，与服务端线上载荷同形），
 * 无后端服务器即可跑通全链路；与 real flavor 的同名模块互斥（flavor source set）。
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
    fun bindUser(impl: UserNetworkTest): UserDataSource

    @Binds
    fun bindComment(impl: CommentNetworkTest): CommentDataSource

    @Binds
    fun bindRelease(impl: ReleaseNetworkTest): ReleaseDataSource
}
