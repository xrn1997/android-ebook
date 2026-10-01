package com.ebook.me.preview

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.ebook.common.domain.ThemeMode
import com.ebook.common.ui.preview.AppPreview
import com.ebook.me.mvvm.viewmodel.CacheManageViewModel
import com.ebook.me.mvvm.viewmodel.MeUiState
import com.ebook.me.mvvm.viewmodel.ReadingStats
import com.ebook.me.mvvm.viewmodel.UpdateState
import com.ebook.me.page.MainMeScreen
import com.ebook.me.repository.CacheType
import com.ebook.me.view.CacheManageScreen
import com.ebook.me.view.SettingScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 「我的」相关屏幕的渲染冒烟。
 *
 * 这几个页面已经在做无状态化（根组件只吃状态与回调），于是它们**本来就能被预览**——
 * 这一层把预览组合的同一组状态在 JVM 上跑一遍。要抓的不是布局好不好看，而是渲染期才炸的那类：
 * 头部取状态栏图标时要 Activity、渐变取色要读调板、缓存页要解析占位文案，任何一处写错，
 * 预览面板只会给一行看不出根因的错误。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class MeScreensRenderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `我的页已登录态渲染出昵称与最近在读`() {
        composeRule.setContent {
            AppPreview {
                MainMeScreen(
                    uiState = MeUiState(
                        isLoggedIn = true,
                        nickname = "临渊客",
                        username = "reader@example.com",
                        avatarUrl = "https://avatar.sample.example.com/1.png",
                    ),
                    readingStats = ReadingStats(shelfCount = 12, recentBookName = "山海拾遗"),
                    themeMode = ThemeMode.SYSTEM,
                    onLoginClick = {},
                    onMyCommentClick = {},
                    onMyInfoClick = {},
                    onSettingClick = {},
                )
            }
        }

        composeRule.onNodeWithText("临渊客").assertIsDisplayed()
        composeRule.onNodeWithText("山海拾遗").assertIsDisplayed()
    }

    /**
     * 未登录 + 空书架这一档组合得出来。
     *
     * 这条只断言"没抛"：这一档没有来自入参的可断言文案（昵称/书名都是空），而它恰恰是最容易
     * 出事的形态——头部要画默认头像、概览卡要处理 `recentBookName == null`、菜单项走未登录分支。
     */
    @Test
    fun `我的页未登录且空书架时组合不抛`() {
        composeRule.setContent {
            AppPreview {
                MainMeScreen(
                    uiState = MeUiState(),
                    readingStats = ReadingStats(),
                    themeMode = ThemeMode.LIGHT,
                    onLoginClick = {},
                    onMyCommentClick = {},
                    onMyInfoClick = {},
                    onSettingClick = {},
                )
            }
        }

        composeRule.waitForIdle()
    }

    @Test
    fun `设置页渲染出版本号与书源数量`() {
        composeRule.setContent {
            AppPreview {
                SettingScreen(
                    cacheSize = "",
                    sourcesCount = 2,
                    appVersion = "1.4.0",
                    isLoggedIn = true,
                    hasUpdateAvailable = true,
                    updateState = UpdateState.Idle,
                    themeMode = ThemeMode.SYSTEM,
                    onCheckUpdate = {},
                    onDismissUpdateDialog = {},
                    onOpenCacheManage = {},
                    onOpenBookSource = {},
                    onOpenAbout = {},
                    onThemeSelected = {},
                    onLogout = {},
                )
            }
        }

        composeRule.onNodeWithText("1.4.0").assertIsDisplayed()
    }

    @Test
    fun `缓存管理页渲染出可清理总量与书籍占用两个口径`() {
        composeRule.setContent {
            AppPreview {
                CacheManageScreen(
                    uiState = CacheManageViewModel.CacheUiState(
                        items = listOf(
                            CacheManageViewModel.CacheItemState(CacheType.IMAGE, "12.8 MB"),
                            CacheManageViewModel.CacheItemState(CacheType.TEMP, "4.1 MB"),
                            CacheManageViewModel.CacheItemState(CacheType.OTHER, "1.5 MB"),
                        ),
                        totalText = "18.4 MB",
                        totalBytes = 19_294_208L,
                        booksSizeText = "142.6 MB",
                        bookCount = 12,
                    ),
                    detailState = null,
                    onOpenDetail = {},
                    onDismissDetail = {},
                    onClearCategory = {},
                    onClearAll = {},
                    canOpenShelf = true,
                    onOpenShelf = {},
                )
            }
        }

        // 两个口径同时出现才是对的：书籍内容不计入可清理总量（见 ADR-0026）。
        // 书籍那一行是 `cache_books_value` 把「占用 + 册数」格式化进同一句文案，故按子串断言
        composeRule.onNodeWithText("18.4 MB").assertIsDisplayed()
        composeRule.onNodeWithText("142.6 MB", substring = true).assertIsDisplayed()
    }
}
