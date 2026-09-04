package com.ebook.api.entity

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 用户实体（展示用身份数据）。
 *
 * 账号根标识为 [id]（线上键 uid），头像为 [image]（线上键 avatar），对齐服务端用户契约；
 * username 仅为展示名（可重复、可后改），不再作为账号标识。
 * 属性名保持客户端历史命名（id/image 在 UI/Parcelable 层广泛使用），
 * 仅在序列化边界用 @SerialName 翻译。
 */
@Serializable
@Parcelize
data class User(
    @SerialName("uid")
    var id: Long = 0L,//账号根标识（线上键 uid）
    var username: String = "", //用户名（展示用，非账号标识）
    @JvmField
    @SerialName("avatar")
    var image: String = "", //头像地址（线上键 avatar）
    @JvmField
    var nickname: String = "", //昵称
    @JvmField
    var email: String = "" //邮箱（账号主标识）
) : Parcelable
