package com.ebook.me.mvvm.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.ebook.common.repository.ProfileRepository
import com.ebook.common.util.reportFailure
import com.ebook.common.util.userMessage
import com.ebook.me.R
import com.ebook.me.repository.ModifyRepository
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import com.xrn1997.common.mvvm.viewmodel.Overlay
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
 * Application Context 解析文案资源），失败一律走共享的
 * [com.ebook.common.util.reportFailure]——会话过期已在网络层全局处置，那条路径只记日志不重复提示。
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
     * 提交在途闸门：昵称与头像两条修改共用一道。
     *
     * 两笔都落在同一份用户资料上，且本页的交互是独占式的（输入或选图不会并行），
     * 故一道足够：在途期间重复触发一律挡掉，避免重复 PUT 与重复提示。
     */
    private var submitInProgress = false

    /**
     * 修改昵称
     *
     * 与 [modifyProfilePhoto] 同构：闸门 → [Overlay.Loading] → 结果分流 → finally 复位。
     * 修复前这里既无等待态也无闸门——慢网下点下去像没反应，连点会发两次 PUT、弹两次提示，
     * 而第二次 `sendFinish` 只是空转。
     */
    fun modifyNickname(name: String) {
        if (submitInProgress) return
        submitInProgress = true
        viewModelScope.launch {
            updateOverlay(Overlay.Loading)
            try {
                val result = modifyRepository.modifyNickname(name)
                result.onSuccess {
                    sendToast(context.getString(R.string.modify_nickname_success))
                    profileRepository.updateNickname(name)
                    sendFinish()
                }.onFailure { reportFailure(it) }
            } finally {
                updateOverlay(Overlay.None)
                submitInProgress = false
            }
        }
    }

    /**
     * 修改头像
     *
     * @param uri 图片路径
     */
    fun modifyProfilePhoto(uri: Uri) {
        if (submitInProgress) return
        submitInProgress = true
        viewModelScope.launch {
            updateOverlay(Overlay.Loading)
            try {
                val result = modifyRepository.modifyProfilePhoto(uri)
                result.onSuccess { url ->
                    sendToast(context.getString(R.string.modify_avatar_success))
                    if (url.isNotEmpty()) {
                        profileRepository.updatePicture(url)
                    }
                }.onFailure { exception ->
                    // 固定前缀（上传头像失败：）走资源，动态错误文案经 %1$s 注入
                    reportFailure(
                        exception,
                        context.getString(R.string.modify_avatar_failed, exception.userMessage()),
                    )
                }
            } finally {
                updateOverlay(Overlay.None)
                submitInProgress = false
            }
        }
    }
}
