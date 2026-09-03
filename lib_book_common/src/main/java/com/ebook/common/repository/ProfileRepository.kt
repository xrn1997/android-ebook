package com.ebook.common.repository

import com.ebook.common.event.KeyCode
import com.ebook.common.util.SPUtil
import com.xrn1997.common.mvvm.model.BaseModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用户个人信息仓库 - 管理用户状态（头像、昵称等）
 *
 * 设计原则：
 * - 状态用 StateFlow（新订阅者立即拿到当前值）
 * - 不含事件发布，UI 刷新由 StateFlow 自动驱动
 */
@Singleton
class ProfileRepository @Inject constructor(
) : BaseModel() {
    // 头像 URL（StateFlow，保证生命周期过渡期不丢失）
    private val _pictureUrl = MutableStateFlow(SPUtil.get(KeyCode.Login.SP_IMAGE, ""))
    val pictureUrl: StateFlow<String> = _pictureUrl.asStateFlow()

    // 用户昵称
    private val _nickname = MutableStateFlow(SPUtil.get(KeyCode.Login.SP_NICKNAME, ""))
    val nickname: StateFlow<String> = _nickname.asStateFlow()

    fun updatePicture(url: String) {
        _pictureUrl.value = url
        SPUtil.put(KeyCode.Login.SP_IMAGE, url)
    }

    fun updateNickname(name: String) {
        _nickname.value = name
        SPUtil.put(KeyCode.Login.SP_NICKNAME, name)
    }

    /** 清除认证数据（退出登录） */
    fun clearAuthData() {
        SPUtil.clearAuthData()
        _pictureUrl.value = ""
        _nickname.value = ""
    }
}
