package com.ebook.login.provider

import com.ebook.common.provider.ILoginProvider
import com.ebook.login.repository.UserRepository
import com.ebook.login.repository.UserRepositoryEntryPoint
import com.therouter.inject.ServiceProvider
import com.therouter.inject.Singleton
import com.xrn1997.common.BaseApplication.Companion.context
import dagger.hilt.android.EntryPointAccessors

/**
 * 登录域跨模块能力提供者（TheRouter SPI 注册）。
 *
 * 其他模块经 [ILoginProvider] 调用登录域能力（不直接依赖 module_login）。
 * Provider 由 TheRouter 创建而非 Hilt，仓库实例经 [UserRepositoryEntryPoint]
 * 从 Hilt 图中桥接获取。
 *
 * 只暴露服务端侧登出：本地清会话由各调用方走
 * [com.ebook.common.domain.UserSessionManager.clearSession] 单点，不在本层重复。
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

    /** 作废服务端会话：服务端会作废该用户全部 refresh token（见 ADR-0008） */
    override suspend fun logout(): Result<Unit> = userRepository.logout()
}

