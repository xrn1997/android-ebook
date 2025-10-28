package com.ebook.login.mvvm.model

import android.app.Application
import com.ebook.api.entity.LoginDTO
import com.ebook.api.entity.User
import com.ebook.api.service.user.UserDataSource
import com.ebook.api.utils.CoroutineAdapter
import com.therouter.inject.Singleton
import com.xrn1997.common.dto.RespDTO
import com.xrn1997.common.mvvm.model.BaseModel
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

@Singleton
class UserModel @Inject constructor(
    application: Application,
    private val dataSource: UserDataSource
) : BaseModel(application) {

    suspend fun register(username: String, password: String): Result<RespDTO<LoginDTO>> =
        CoroutineAdapter.safeApiCall { dataSource.register(User(username, password)) }

    suspend fun modifyPwd(username: String, password: String): Result<RespDTO<Int>> =
        CoroutineAdapter.safeApiCall { dataSource.modifyPwd(User(username, password)) }

    suspend fun login(username: String, password: String): Result<RespDTO<LoginDTO>> =
        CoroutineAdapter.safeApiCall { dataSource.login(User(username, password)) }

}


@InstallIn(SingletonComponent::class)
@EntryPoint
interface UserModelEntryPoint {
    fun getUserModel(): UserModel
}
