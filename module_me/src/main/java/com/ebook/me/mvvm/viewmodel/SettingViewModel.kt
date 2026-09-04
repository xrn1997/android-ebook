package com.ebook.me.mvvm.viewmodel

import androidx.lifecycle.viewModelScope
import com.ebook.common.domain.UserSessionManager
import com.ebook.common.provider.ILoginProvider
import com.ebook.me.repository.CacheModel
import com.ebook.me.repository.formatSize
import com.therouter.TheRouter
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import com.xrn1997.common.util.Logger
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
 * - 退出登录：经 UserSessionManager 单点清会话
 * - 登录态：控制「退出登录」区块的显隐
 */
@HiltViewModel
class SettingViewModel @Inject constructor(
    private val cacheModel: CacheModel,
    private val userSessionManager: UserSessionManager,
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
     * 退出登录：先尽力作废服务端会话，再无条件清本地会话。
     *
     * 顺序与容错的取法：
     * - 服务端作废（`POST /api/auth/logout`，作废该用户全部 refresh token）经
     *   [ILoginProvider] 跨模块取用；**失败不阻塞本地清理**——救不回的凭证不该把用户
     *   锁在一个他已认为退出的会话里。
     * - 独立运行（isModule=true）时 module_login 不在依赖图内，provider 为 null，
     *   此时只有本地清理（调试宿主本就不连后端）。
     *
     * 本方法是 `suspend`：调用方必须在页面 `finish()` 前 await，否则承载它的协程作用域
     * 会随页面销毁被取消，登出请求等于从未发出。
     *
     * 本地清理只走 [UserSessionManager.clearSession]，它一次覆盖用户会话的三处镜像
     * （`user_session` SP、`spUtils` 兼容键、ProfileRepository 进程内身份流）。
     */
    suspend fun logout() {
        TheRouter.get(ILoginProvider::class.java)?.logout()?.onFailure {
            Logger.w(TAG, "服务端登出失败，仍继续清本地会话：${it.message}")
        }
        userSessionManager.clearSession()
    }
}
