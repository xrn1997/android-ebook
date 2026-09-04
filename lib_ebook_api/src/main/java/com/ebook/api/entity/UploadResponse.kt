package com.ebook.api.entity

import kotlinx.serialization.Serializable

/**
 * 头像上传响应（对齐服务端上传契约：POST /api/uploads/avatar 返回可访问的图片 URL）。
 *
 * 客户端流程为两步：先上传文件拿 [url]，再 PUT /api/users/me 提交 avatar=url。
 */
@Serializable
data class UploadResponse(
    val url: String = ""
)
