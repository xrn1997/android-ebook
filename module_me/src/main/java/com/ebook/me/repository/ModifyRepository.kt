package com.ebook.me.repository

import android.app.Application
import android.net.Uri
import com.ebook.api.entity.UpdateUserRequest
import com.ebook.api.service.user.AccountProfile
import com.ebook.api.service.user.missingPayloadAs
import com.ebook.me.R
import com.xrn1997.common.mvvm.model.BaseModel
import com.xrn1997.common.util.FileUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 资料修改仓库：昵称与头像两条改动路径的数据层收口。
 *
 * 注入账号的资料面 [AccountProfile] 一张（ADR-0059），不注入整张宽接口：本仓库只用
 * 「更新当前用户」与「上传头像」两个端点。信封的业务码、A0230 处置与 `data` 空判都在
 * 网络适配器的缝内做完，本层只把「成功码却没带回载荷」这一档换成自己的文案
 * （[missingPayloadAs]）——服务端文案与会话过期仍原样透传给 `reportFailure`。
 */
@Singleton
class ModifyRepository @Inject constructor(
    private val application: Application,
    private val accountProfile: AccountProfile,
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
    suspend fun modifyNickname(nickname: String): Result<Unit> =
        accountProfile.updateMe(UpdateUserRequest(nickname = nickname))
            // 更新后的用户已由 ProfileRepository 那条流覆盖，本层只需要「改成了」这个结论
            .map { }
            // 失败文案走字符串资源（异常 message 会经 ViewModel 展示给用户）
            .missingPayloadAs(application.getString(R.string.modify_nickname_failed))

    /**
     * 修改头像（两步流程：上传拿 URL → 更新资料）
     *
     * 1. 读取图片字节（IO 线程）→ POST /api/uploads/avatar 上传拿 URL
     * 2. PUT /api/users/me 提交 avatar=url 更新资料
     *
     * 上传只交「字节 + 文件名」：multipart 字段名 `avatar` 与媒体类型归传输适配器。
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

        // 第一步：上传字节拿 URL
        val url = accountProfile.uploadAvatar(bytes, FileUtil.generateFileName("jpg"))
            .missingPayloadAs(application.getString(R.string.modify_avatar_failed_internal))
            .getOrElse { return Result.failure(it) }

        // 第二步：提交 URL 更新头像
        return accountProfile.updateMe(UpdateUserRequest(avatar = url))
            .map { url }
            .missingPayloadAs(application.getString(R.string.modify_avatar_failed_internal))
    }
}
