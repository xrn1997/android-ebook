package com.ebook.api.entity

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class User(
    var id: Long = 0L,//ID
    var username: String = "", //用户名（账号）
    var password: String = "", //密码
    @JvmField
    var image: String = "", //图片地址
    @JvmField
    var nickname: String = "" //昵称
) : Parcelable {
    constructor(username: String, password: String) : this() {
        this.username = username
        this.password = password
    }
}
