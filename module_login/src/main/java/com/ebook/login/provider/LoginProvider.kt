package com.ebook.login.provider


import com.xrn1997.common.dto.RespDTO
import com.ebook.api.entity.LoginDTO
import com.ebook.common.provider.ILoginProvider
import com.ebook.login.mvvm.model.UserModel
import com.ebook.login.mvvm.model.UserModelEntryPoint
import com.therouter.inject.ServiceProvider
import com.therouter.inject.Singleton
import com.xrn1997.common.BaseApplication.Companion.context
import dagger.hilt.android.EntryPointAccessors

@Singleton
@ServiceProvider
class LoginProvider : ILoginProvider {
    private var userModel: UserModel

    init {
        val entryPoint = EntryPointAccessors.fromApplication(
            context, UserModelEntryPoint::class.java
        )
        userModel = entryPoint.getUserModel()
    }

    override suspend fun login(username: String, password: String): Result<RespDTO<LoginDTO>> {
        return userModel.login(username, password)
    }
}

