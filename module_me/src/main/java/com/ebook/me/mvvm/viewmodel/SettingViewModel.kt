package com.ebook.me.mvvm.viewmodel

import androidx.lifecycle.viewModelScope
import com.ebook.common.domain.UserSessionManager
import com.ebook.common.repository.ProfileRepository
import com.ebook.me.repository.CacheModel
import com.ebook.me.repository.formatSize
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设置页 ViewModel。
 *
 * 职责（均为本地操作，无网络依赖）：
 * - 缓存：展示 cacheDir 总占用（点击入口跳缓存管理页做分类清理，本页不再直接清理）
 * - 退出登录：清理会话 + 资料双轨状态
 * - 登录态：控制「退出登录」区块的显隐
 */
@HiltViewModel
class SettingViewModel @Inject constructor(
    private val cacheModel: CacheModel,
    private val userSessionManager: UserSessionManager,
    private val profileRepository: ProfileRepository,
) : BaseViewModel<CacheModel>(cacheModel) {

    /** 当前登录态（控制退出登录区块显隐） */
    val isLoggedIn: StateFlow<Boolean> = userSessionManager.isLoggedIn

    /**
     * cacheDir 可读大小（如 "12.3 MB"）。
     *
     * 初始为空串表示「计算中」，占位文案（--）由 UI 层经字符串资源解析，
     * VM 不持有任何用户可见文本。
     */
    private val _cacheSize = MutableStateFlow("")
    val cacheSize: StateFlow<String> = _cacheSize.asStateFlow()

    init {
        refreshCacheSize()
    }

    /**
     * 重算缓存总占用。
     *
     * public 供 Activity 在 onResume 时刷新——从缓存管理页返回后，
     * 入口行的大小文案需要与实际清理结果同步。
     */
    fun refreshCacheSize() {
        viewModelScope.launch {
            _cacheSize.value = formatSize(cacheModel.cacheSizeBytes())
        }
    }

    /**
     * 退出登录：清理会话与资料双轨状态。
     *
     * [UserSessionManager.clearSession] 清 token/登录态/user_session SP，
     * 但 [ProfileRepository] 的内存昵称/头像流需要单独清（否则重新登录另一账号时会闪现旧昵称）；
     * 其内部对 SPUtil 的清理是幂等的，重复调用无副作用。
     */
    fun logout() {
        userSessionManager.clearSession()
        profileRepository.clearAuthData()
    }
}
