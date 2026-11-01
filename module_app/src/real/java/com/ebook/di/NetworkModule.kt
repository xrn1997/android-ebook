package com.ebook.di

import com.ebook.api.service.user.AccountPassword
import com.ebook.api.service.user.AccountProfile
import com.ebook.api.service.user.AccountSignIn
import com.ebook.api.service.user.SessionRefresh
import com.ebook.api.service.user.UserNetwork
import com.ebook.api.service.comment.CommentDataSource
import com.ebook.api.service.comment.CommentNetwork
import com.ebook.api.service.release.ReleaseDataSource
import com.ebook.api.service.release.ReleaseNetwork
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 集成构建 real flavor 的网络绑定模块。
 *
 * 账号域按调用方切成四个窄面（ADR-0059），本模块给**每一面**各绑一条到同一个
 * `@Singleton UserNetwork`：少绑一面就等于给那一面留一条「依赖找不到」的注入路径，
 * 而拆成四个实例会静默复制基址与刷新状态（绑定形态同 `di/RepositoryModule.kt`）。
 * 聚合的宽接口 `UserDataSource` 已删除——不留一条聚合绑定。
 *
 * [CommentDataSource] 绑到真实后端 [CommentNetwork]（评论域不在本次切面范围内，仍是一张面）；
 * 基址由 lib_ebook_api 的 ServerAddressStore 统一提供：用户自配、不内置默认值，见 ADR-0052/0053。
 * 与 mock flavor 的同名模块互斥（flavor source set）。
 *
 * [ReleaseDataSource] 也在本模块绑定，但它**不指向 ebook-server**：真实实现打的是
 * GitHub/Gitcode 的公开 Releases API（匿名、无 token），mock flavor 才把它换成固定资产。
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface NetworkModule {
    @Binds
    @Singleton
    fun bindSignIn(impl: UserNetwork): AccountSignIn

    @Binds
    @Singleton
    fun bindPassword(impl: UserNetwork): AccountPassword

    @Binds
    @Singleton
    fun bindProfile(impl: UserNetwork): AccountProfile

    @Binds
    @Singleton
    fun bindSessionRefresh(impl: UserNetwork): SessionRefresh

    @Binds
    fun bindComment(impl: CommentNetwork): CommentDataSource

    @Binds
    fun bindRelease(impl: ReleaseNetwork): ReleaseDataSource
}
