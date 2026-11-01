package com.ebook.api.service.user

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [UserNetwork] 的 `avatarPart` 的纯 JVM 契约测试：头像 multipart 的字段名、文件名与媒体类型。
 *
 * 为什么单独钉这一处：`UserService.uploadAvatar` 的注解是裸的 `@Part MultipartBody.Part`——
 * Retrofit 只把现成的分段塞进请求体，**不从注解重新推导字段名**。于是 `name="avatar"` 与
 * `image/jpeg` 这两个服务端契约值既没有编译期主人也没有别的读数：写错不会有信号，mock 构建
 * 照常全绿（它不真发请求），只有真实后端把它当成「没收到文件」才拒。ADR-0059 把字节与文件名
 * 留给调用方、把这两项收进适配器，契约的读数就只剩这里，故把 `avatarPart` 放宽到 `internal`
 * 专门钉住它。
 */
class AvatarPartTest {

    @Test
    fun `头像分段的字段名是 avatar，文件名沿用入参，媒体类型按 JPEG`() {
        val part = UserNetwork.avatarPart(bytes = ByteArray(4) { 7 }, fileName = "avatar.jpg")

        assertEquals(
            "form-data; name=\"avatar\"; filename=\"avatar.jpg\"",
            part.headers?.get("Content-Disposition"),
        )
        // 媒体类型带在服务体上（MultipartBody 写请求体时取它），服务端按它判是否接受该格式
        assertEquals("image/jpeg", part.body.contentType()?.toString())
    }
}
