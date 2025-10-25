package com.ebook.me.mvvm.model

import android.app.Application
import android.net.Uri
import com.blankj.utilcode.util.SPUtils
import com.ebook.api.dto.RespDTO
import com.ebook.api.service.user.UserDataSource
import com.ebook.common.event.KeyCode
import com.xrn1997.common.manager.RetrofitManager
import com.xrn1997.common.mvvm.model.BaseModel
import com.xrn1997.common.util.FileUtil
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModifyModel @Inject constructor(
    application: Application,
    private val dataSource: UserDataSource,
) : BaseModel(application) {

    /**
     * 修改昵称
     */
    suspend fun modifyNickname(nickname: String): RespDTO<Int> {
        val username = SPUtils.getInstance().getString(KeyCode.Login.SP_USERNAME)
        return dataSource.modifyNickname(RetrofitManager.TOKEN, username, nickname)
    }

    /**
     * 修改头像
     *
     * @param uri 头像路径
     * @return 返回服务器头像名称
     */
    @Throws(IOException::class)
    suspend fun modifyProfilePhoto(uri: Uri): RespDTO<String> {
        val username = SPUtils.getInstance().getString(KeyCode.Login.SP_USERNAME)

        val inputStream = mApplication.contentResolver.openInputStream(uri)
            ?: throw IOException("Unable to open InputStream for URI: $uri")

        inputStream.use { stream ->
            val requestBody = stream.readBytes()
                .toRequestBody("application/octet-stream".toMediaTypeOrNull())

            val body = MultipartBody.Part.createFormData(
                "file",
                FileUtil.getFileName(".jpg"),
                requestBody
            )

            // 调用 Retrofit suspend API
            return dataSource.modifyProfilePhoto(RetrofitManager.TOKEN, username, body)
        }
    }

}
