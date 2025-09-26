package com.ebook.me.mvvm.model

import android.app.Application
import android.net.Uri
import com.blankj.utilcode.util.SPUtils
import com.ebook.api.config.API
import com.ebook.api.dto.RespDTO
import com.ebook.api.service.UserService
import com.ebook.common.event.KeyCode
import com.xrn1997.common.http.RxJavaAdapter.exceptionTransformer
import com.xrn1997.common.http.RxJavaAdapter.schedulersTransformer
import com.xrn1997.common.manager.RetrofitManager
import com.xrn1997.common.mvvm.model.BaseModel
import com.xrn1997.common.util.FileUtil
import io.reactivex.rxjava3.core.Observable
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModifyModel @Inject constructor(
    application: Application
) : BaseModel(application) {
    private val mUserService: UserService = RetrofitManager.create(UserService::class.java)

    init {
        // 通过反射动态修改 BaseUrl
        RetrofitManager.mHttpUrl.setHost(API.URL_HOST_USER)
        RetrofitManager.mHttpUrl.setPort(API.URL_PORT_USER)
    }

    /**
     * 修改昵称
     */
    fun modifyNickname(nickname: String): Observable<RespDTO<Int>> {
        val username = SPUtils.getInstance().getString(KeyCode.Login.SP_USERNAME)
        return mUserService.modifyNickname(RetrofitManager.TOKEN, username, nickname)
            .compose(schedulersTransformer())
            .compose(exceptionTransformer())
    }

    /**
     * 修改头像
     *
     * @param uri 头像路径
     * @return 返回服务器头像名称
     */
    fun modifyProfilePhoto(uri: Uri): Observable<RespDTO<String>> {
        val username = SPUtils.getInstance().getString(KeyCode.Login.SP_USERNAME)
        mApplication.contentResolver.openInputStream(uri)?.use { stream ->
            // 将InputStream转化为RequestBody
            val requestBody =
                stream.readBytes().toRequestBody("application/octet-stream".toMediaTypeOrNull())
            val body =
                MultipartBody.Part.createFormData("file", FileUtil.getFileName(".jpg"), requestBody)

            return mUserService.modifyProfilePhoto(RetrofitManager.TOKEN, username, body)
                .compose(schedulersTransformer())
                .compose(exceptionTransformer())
        }

        // 如果无法获取InputStream，返回错误Observable
        return Observable.error(IOException("Unable to open InputStream for URI: $uri"))
    }

}
