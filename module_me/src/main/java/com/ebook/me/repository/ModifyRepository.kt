package com.ebook.me.repository

import android.app.Application
import android.net.Uri
import com.ebook.api.entity.UpdateUserRequest
import com.ebook.api.service.user.UserDataSource
import com.ebook.api.utils.CoroutineAdapter
import com.ebook.me.R
import com.xrn1997.common.mvvm.model.BaseModel
import com.xrn1997.common.util.FileUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModifyRepository @Inject constructor(
    private val application: Application,
    private val dataSource: UserDataSource,
    private val coroutineAdapter: CoroutineAdapter
) : BaseModel() {

    /**
     * 修改昵称
     *
     * 流程：PUT /api/users/me 部分更新（body 仅 nickname）→ 服务端返回更新后的用户。
     * 历史独立端点 PUT /api/users/me/nickname 已被服务端废弃（统一走本端点的部分更新）。
     *
     * @param nickname 新昵称（已由 UI 层校验：非空、长度合规）
     * @return Result<Unit> 成功返回 Unit，失败返回异常
     */
    suspend fun modifyNickname(nickname: String): Result<Unit> {
        return coroutineAdapter.safeApiCall {
            dataSource.updateMe(UpdateUserRequest(nickname = nickname))
        }.mapCatching { resp ->
            // 失败文案走字符串资源（异常 message 会经 ViewModel 展示给用户）
            resp.data ?: throw Exception(application.getString(R.string.modify_nickname_failed))
        }
    }

    /**
     * 修改头像（两步流程：上传拿 URL → 更新资料）
     *
     * 1. 读取图片字节（IO 线程）→ POST /api/uploads/avatar 上传拿 URL
     * 2. PUT /api/users/me 提交 avatar=url 更新资料
     *
     * @param uri 图片 Uri（来自拍照或相册选择）
     * @return Result<String> 成功返回新头像 URL，失败返回异常
     */
    suspend fun modifyProfilePhoto(uri: Uri): Result<String> {
        // 读整个图片文件字节可能耗时（content:// 还涉及跨进程 IPC），必须切 IO 线程
        val bytes = withContext(Dispatchers.IO) {
            application.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes()
            }
        } ?: throw Exception(application.getString(R.string.modify_photo_open_failed))

        // 第一步：上传文件拿 URL（multipart 字段名 avatar，与后端契约一致）
        val uploadPart = MultipartBody.Part.createFormData(
            "avatar",
            FileUtil.generateFileName("jpg"),
            bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
        )
        val url = coroutineAdapter.safeApiCall {
            dataSource.uploadAvatar(uploadPart)
        }.mapCatching { resp ->
            resp.data?.url?.takeIf { it.isNotEmpty() }
                ?: throw Exception(application.getString(R.string.modify_avatar_failed_internal))
        }.getOrElse { return Result.failure(it) }

        // 第二步：提交 URL 更新头像
        return coroutineAdapter.safeApiCall {
            dataSource.updateMe(UpdateUserRequest(avatar = url))
        }.mapCatching { resp ->
            resp.data ?: throw Exception(application.getString(R.string.modify_avatar_failed_internal))
            url
        }
    }
}
