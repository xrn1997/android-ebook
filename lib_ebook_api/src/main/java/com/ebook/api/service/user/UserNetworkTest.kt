package com.ebook.api.service.user

import com.xrn1997.common.dto.RespDTO
import com.ebook.api.entity.LoginDTO
import com.ebook.api.entity.User
import com.ebook.api.utils.TestAssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.MultipartBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserNetworkTest @Inject constructor(
    private val networkJson: Json,
    private val assets: TestAssetManager,
) : UserDataSource {

    override suspend fun login(user: User): RespDTO<LoginDTO> =
        getDataFromJsonFile(USER_LOGIN)


    override suspend fun register(user: User): RespDTO<LoginDTO> =
        getDataFromJsonFile(USER_REGISTER)


    override suspend fun modifyPwd(user: User): RespDTO<Int> =
        getDataFromJsonFile(USER_MODIFY_PWD)


    override suspend fun modifyNickname(
        token: String?,
        username: String,
        nickname: String
    ): RespDTO<Int> =
        getDataFromJsonFile(USER_MODIFY_NICKNAME)


    override suspend fun modifyProfilePhoto(
        token: String?,
        username: String,
        file: MultipartBody.Part
    ): RespDTO<String> =
        getDataFromJsonFile(USER_MODIFY_PROFILE_PHOTO)


    /**
     * Get data from the given JSON [fileName].
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend inline fun <reified T> getDataFromJsonFile(fileName: String): RespDTO<T> =
        withContext(Dispatchers.IO) {
            assets.open(fileName).use { inputStream ->
                networkJson.decodeFromStream(inputStream)
            }
        }

    companion object {
        private const val USER_LOGIN = "user_login.json"
        private const val USER_REGISTER = "user_register.json"
        private const val USER_MODIFY_PWD = "user_modify_pwd.json"
        private const val USER_MODIFY_NICKNAME = "user_modify_nickname.json"
        private const val USER_MODIFY_PROFILE_PHOTO = "user_modify_profile_photo.json"
    }
}