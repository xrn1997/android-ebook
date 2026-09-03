package com.ebook.me.mvvm.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.ebook.api.utils.CoroutineAdapter
import com.ebook.common.repository.ProfileRepository
import com.ebook.me.R
import com.ebook.me.repository.ModifyRepository
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import com.xrn1997.common.mvvm.viewmodel.Overlay
import com.xrn1997.common.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 编辑资料页的资料展示状态（昵称/头像单一数据源）。
 *
 * 编辑资料页与修改昵称页共用 [ModifyViewModel]，页面不再直接收集
 * [ProfileRepository] 的散流，回退与合并规则收敛在 ViewModel。
 */
data class ProfileDisplayState(
    /** 当前昵称（ProfileRepository 为主，登录链路写入） */
    val nickname: String = "",
    /** 当前头像 URL，为空时 UI 回退默认头像 */
    val avatarUrl: String = "",
)

/**
 * 编辑资料 ViewModel：昵称/头像修改（编辑资料页与修改昵称页共用）。
 *
 * 资料展示经 [profileState] 单一流驱动（[ProfileRepository] 为主源），
 * 修改成功后更新资料流，页面自动刷新；成败提示经基类 sendToast 下发（VM 注入
 * Application Context 解析文案资源），会话过期已在网络层全局处置，本 VM 失败分支只记日志不重复提示。
 */
@HiltViewModel
class ModifyViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modifyRepository: ModifyRepository,
    private val profileRepository: ProfileRepository,
) : BaseViewModel<ModifyRepository>(modifyRepository) {

    /** 昵称/头像展示流：修改成功后经 ProfileRepository 更新自动刷新 */
    val profileState: StateFlow<ProfileDisplayState> = combine(
        profileRepository.nickname,
        profileRepository.pictureUrl,
    ) { nickname, pictureUrl ->
        ProfileDisplayState(nickname = nickname, avatarUrl = pictureUrl)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ProfileDisplayState(),
    )

    /**
     * 修改昵称
     */
    fun modifyNickname(name: String) {
        viewModelScope.launch {
            val result = modifyRepository.modifyNickname(name)
            result.onSuccess {
                sendToast(context.getString(R.string.modify_nickname_success))
                profileRepository.updateNickname(name)
                sendFinish()
            }.onFailure { exception ->
                if (CoroutineAdapter.isSessionExpiredHandled(exception)) {
                    Logger.w(TAG, "会话过期已由全局处置，本调用点静默（仅日志）：${exception.message}")
                    return@onFailure
                }
                sendToast(errorText(exception))
            }
        }
    }

    /**
     * 修改头像
     *
     * @param uri 图片路径
     */
    fun modifyProfilePhoto(uri: Uri) {
        updateOverlay(Overlay.Loading)

        viewModelScope.launch {
            try {
                val result = modifyRepository.modifyProfilePhoto(uri)
                result.onSuccess { url ->
                    sendToast(context.getString(R.string.modify_avatar_success))
                    if (url.isNotEmpty()) {
                        profileRepository.updatePicture(url)
                    }
                }.onFailure { exception ->
                    if (CoroutineAdapter.isSessionExpiredHandled(exception)) {
                        Logger.w(TAG, "会话过期已由全局处置，本调用点静默（仅日志）：${exception.message}")
                        return@onFailure
                    }
                    // 固定前缀（上传头像失败：）走资源，动态错误文案经 %1$s 注入
                    sendToast(context.getString(R.string.modify_avatar_failed, errorText(exception)))
                }
            } finally {
                updateOverlay(Overlay.None)
            }
        }
    }

    companion object {
        private const val TAG = "ModifyViewModel"
    }
}
