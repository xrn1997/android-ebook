package com.ebook.me.mvvm.viewmodel

import androidx.lifecycle.viewModelScope
import com.ebook.common.domain.UserSessionManager
import com.ebook.common.repository.BookRepository
import com.ebook.common.repository.ProfileRepository
import com.xrn1997.common.mvvm.model.NoOpModel
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 我的页 UI 状态（单一数据源）。
 *
 * 把头像/昵称的回退规则从 Composable 收敛到 ViewModel：
 * - 昵称以 [ProfileRepository]（登录/改昵称链路写入）为主，[com.ebook.common.domain.UserSession] 兜底，
 *   覆盖老数据只存在 user_session SP 文件、未同步到 spUtils 的迁移场景
 * - 头像同理；为空时 UI 回退默认头像
 */
data class MeUiState(
    /** 是否已登录 */
    val isLoggedIn: Boolean = false,
    /** 展示昵称，未登录或无数据时为空（UI 显示「未登录」占位） */
    val nickname: String = "",
    /** 登录账号名（头部副标题），未登录为空 */
    val username: String = "",
    /** 头像 URL，为空时 UI 回退默认头像 */
    val avatarUrl: String = "",
)

/**
 * 阅读概览统计（本地数据，与登录态无关）。
 *
 * @param shelfCount 书架藏书数
 * @param recentBookName 最近在读书名（按 finalDate 最新），书架为空时为 null
 */
data class ReadingStats(
    val shelfCount: Int = 0,
    val recentBookName: String? = null,
)

/**
 * 我的页（Compose）ViewModel。
 *
 * 页面经 TheRouter 的 ServiceProvider 暴露（非 Hilt 创建），无法直接 @Inject，
 * 因此把页面依赖（个人资料 + 登录态 + 书架数据）收进 ViewModel 注入，页面经 hiltViewModel() 获取。
 *
 * 继承 [BaseViewModel] 对齐全仓 ViewModel 约定（AGENTS.md）；Model 用 [NoOpModel] 占位——
 * 本页依赖直接注入三个仓库（UseCase 式），没有也不需要一个 Model 门面类。
 *
 * 状态分两条流：
 * - [meState]：登录态（[UserSessionManager]）+ 资料（[ProfileRepository]）合并，
 *   页面只收集一个状态流，避免在 Composable 里散落多条 collectAsState 与回退判断
 *   （命名避开基类的 uiState，后者专驱加载/错误覆盖层，与 BookDetailViewModel/CacheManageViewModel 同约定）
 * - [readingStats]：书架本地数据（[BookRepository.observeBookShelf]），Room 失效追踪自动推送，
 *   阅读 App 的「我的」页核心内容，无后端也真实可读
 *
 * WhileSubscribed(5s)：切走 Tab 停止合并，切回立即用缓存值，兼顾省电与即时刷新。
 */
@HiltViewModel
class MePageViewModel @Inject constructor(
    profileRepository: ProfileRepository,
    userSessionManager: UserSessionManager,
    bookRepository: BookRepository,
) : BaseViewModel<NoOpModel>(NoOpModel()) {

    val meState: StateFlow<MeUiState> = combine(
        userSessionManager.isLoggedIn,
        userSessionManager.currentUser,
        profileRepository.nickname,
        profileRepository.pictureUrl,
    ) { isLoggedIn, user, nickname, pictureUrl ->
        MeUiState(
            isLoggedIn = isLoggedIn,
            nickname = nickname.ifEmpty { user?.nickname.orEmpty() },
            username = user?.username.orEmpty(),
            avatarUrl = pictureUrl.ifEmpty { user?.avatar.orEmpty() },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MeUiState(),
    )

    /** 书架统计：observeBookShelf 按 finalDate 倒序，first 即最近在读 */
    val readingStats: StateFlow<ReadingStats> = bookRepository.observeBookShelf()
        .map { books ->
            ReadingStats(
                shelfCount = books.size,
                recentBookName = books.firstOrNull()?.bookInfo?.name,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ReadingStats(),
        )
}
