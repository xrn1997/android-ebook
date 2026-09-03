package com.ebook.di

import com.ebook.api.service.comment.CommentDataSource
import com.ebook.api.service.comment.CommentNetworkTest
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
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface NetworkModule {
    @Binds
    fun bindUser(impl: UserNetworkTest): UserDataSource

    @Binds
    fun bindComment(impl: CommentNetworkTest): CommentDataSource
}
