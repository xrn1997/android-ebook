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

    /**
     * 清除进程内的身份态（用户会话的第 ③ 处镜像：昵称与头像内存流）。
     *
     * 只清内存：SP 侧（② `spUtils` 与 ① `user_session`）由
     * [com.ebook.common.domain.AndroidUserSessionManager.clearSession] 统一清除，
     * 此处重复清 SP 不会出错，但会让「谁负责哪一处镜像」重新变模糊。
     *
     * 不作对外入口：清会话一律只调 `clearSession()`（见 ADR-0008 与 AGENTS.md
     * §认证体系约定），本方法仅作为其内部实现细节被调用。
     */
    internal fun resetProfileState() {
        _pictureUrl.value = ""
        _nickname.value = ""
    }
}
