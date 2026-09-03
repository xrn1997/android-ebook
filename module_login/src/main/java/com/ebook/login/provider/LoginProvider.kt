package com.ebook.login.provider

import com.ebook.common.domain.UserSession
import com.ebook.common.provider.ILoginProvider
import com.ebook.login.repository.UserRepository
import com.ebook.login.repository.UserRepositoryEntryPoint
import com.therouter.inject.ServiceProvider
import com.therouter.inject.Singleton
import com.xrn1997.common.BaseApplication.Companion.context
import dagger.hilt.android.EntryPointAccessors

/**
 * 登录能力服务提供者（TheRouter SPI 注册）。
 *
 * 其他模块经 [ILoginProvider] 接口调用登录能力（不直接依赖 module_login）。
 * Provider 由 TheRouter 创建而非 Hilt，仓库实例经 [UserRepositoryEntryPoint]
 * 从 Hilt 图中桥接获取。
 */
@Singleton
@ServiceProvider
class LoginProvider : ILoginProvider {
    private var userRepository: UserRepository

    init {
        val entryPoint = EntryPointAccessors.fromApplication(
            context, UserRepositoryEntryPoint::class.java
        )
        userRepository = entryPoint.getUserRepository()
    }

    /** 登录（参数名为接口历史遗留，实际传邮箱：邮箱为登录主标识，见 ADR-0009） */
    override suspend fun login(username: String, password: String): Result<UserSession> {
        return userRepository.login(username, password)
    }
}

