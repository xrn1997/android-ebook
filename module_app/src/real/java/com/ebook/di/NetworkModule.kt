package com.ebook.di

import com.ebook.api.service.comment.CommentDataSource
import com.ebook.api.service.comment.CommentNetwork
import com.ebook.api.service.release.ReleaseDataSource
import com.ebook.api.service.release.ReleaseNetwork
import com.ebook.api.service.user.UserDataSource
import com.ebook.api.service.user.UserNetwork
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 集成构建 real flavor 的网络绑定模块。
 *
 * 将 UserDataSource/CommentDataSource 绑定到真实后端实现（UserNetwork/CommentNetwork，
 * 基址经 BuildConfig 注入，见 lib_ebook_api 的 API 配置）；
 * 与 mock flavor 的同名模块互斥（flavor source set）。
 *
 * [ReleaseDataSource] 也在本模块绑定，但它**不指向 ebook-server**：真实实现打的是
 * GitHub/Gitcode 的公开 Releases API（匿名、无 token），mock flavor 才把它换成固定资产。
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface NetworkModule {
    @Binds
    fun bindUser(impl: UserNetwork): UserDataSource

    @Binds
    fun bindComment(impl: CommentNetwork): CommentDataSource

    @Binds
    fun bindRelease(impl: ReleaseNetwork): ReleaseDataSource
}
