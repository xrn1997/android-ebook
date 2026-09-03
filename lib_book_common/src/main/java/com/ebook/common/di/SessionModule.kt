package com.ebook.common.di

import com.ebook.api.auth.TokenRefresher
import com.ebook.common.domain.AndroidUserSessionManager
import com.ebook.common.domain.SessionTokenRefresher
import com.ebook.common.domain.UserSessionManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 会话管理 Hilt 模块
 *
 * 将 UserSessionManager 接口绑定到 AndroidUserSessionManager 实现；
 * 将 lib_ebook_api 定义的 TokenRefresher 接缝绑定到本层 SessionTokenRefresher 实现
 * （刷新依赖会话持久化，只能在上层实现、向下注入）。
 * Token 的运行时容器（TokenHolder）由 lib_common 提供，登录/登出/刷新时由
 * 会话管理器负责同步。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SessionModule {

    @Binds
    @Singleton
    abstract fun bindUserSessionManager(
        impl: AndroidUserSessionManager
    ): UserSessionManager

    @Binds
    @Singleton
    abstract fun bindTokenRefresher(
        impl: SessionTokenRefresher
    ): TokenRefresher
}
