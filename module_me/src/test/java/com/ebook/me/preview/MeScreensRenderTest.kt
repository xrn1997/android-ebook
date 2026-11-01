package com.ebook.me.preview

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ebook.common.domain.ThemeMode
import com.ebook.common.ui.preview.AppPreview
import com.ebook.me.R
import com.ebook.me.mvvm.viewmodel.CacheManageViewModel
import com.ebook.me.mvvm.viewmodel.MeUiState
import com.ebook.me.mvvm.viewmodel.ReadingStats
import com.ebook.me.mvvm.viewmodel.UpdateState
import com.ebook.me.page.MainMeScreen
import com.ebook.me.repository.CacheType
import com.ebook.me.view.CacheManageScreen
import com.ebook.me.view.SettingScreen
import org.junit.Assert.assertEquals
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
 *
 * 「服务器地址」页自己的契约（进页才见地址、行内报错、重启提示等）住
 * [ServerAddressScreenRenderTest]；本文件只锁设置页**行**的形态。
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
                    isServerAddressConfigured = true,
                    onOpenServerAddress = {},
                    onRestartApp = {},
                    isServerRestartPending = false,
                )
            }
        }

        composeRule.onNodeWithText("1.4.0").assertIsDisplayed()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.setting_book_source_count, 2)
        ).assertIsDisplayed()
    }

    /**
     * 独立运行（module_find 不在依赖图里）不渲染书源入口行。
     *
     * 这一档只有路由探测能判：页面拿不到 module_find 的管理页，留一个点了没反应的入口
     * 比不显示更糟（ADR-0026 的既有处置口径）。
     */
    @Test
    fun `独立运行时设置页不渲染书源入口行`() {
        composeRule.setContent {
            AppPreview {
                SettingScreen(
                    cacheSize = "",
                    sourcesCount = 2,
                    appVersion = "1.4.0",
                    showBookSource = false,
                    isLoggedIn = true,
                    hasUpdateAvailable = false,
                    updateState = UpdateState.Idle,
                    themeMode = ThemeMode.SYSTEM,
                    onCheckUpdate = {},
                    onDismissUpdateDialog = {},
                    onOpenCacheManage = {},
                    onOpenBookSource = {},
                    onOpenAbout = {},
                    onThemeSelected = {},
                    onLogout = {},
                    isServerAddressConfigured = false,
                    onOpenServerAddress = {},
                    onRestartApp = {},
                    isServerRestartPending = false,
                )
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.setting_book_source))
            .assertDoesNotExist()
    }

    /**
     * 服务器地址行**只显示状态、不显示地址值**（ADR-0052 形态调整）。
     *
     * 地址不是可以随便亮在锁屏/录屏里的东西：设置页是会被旁人一眼扫到的界面，
     * 完整地址、明文警告与编辑都在服务器地址页（点进去再说）。
     */
    @Test
    fun `设置页服务器地址行只显示状态不显示地址值`() {
        composeRule.setContent {
            AppPreview {
                SettingScreen(
                    cacheSize = "",
                    sourcesCount = 2,
                    appVersion = "1.4.0",
                    isLoggedIn = true,
                    hasUpdateAvailable = false,
                    updateState = UpdateState.Idle,
                    themeMode = ThemeMode.SYSTEM,
                    onCheckUpdate = {},
                    onDismissUpdateDialog = {},
                    onOpenCacheManage = {},
                    onOpenBookSource = {},
                    onOpenAbout = {},
                    onThemeSelected = {},
                    onLogout = {},
                    isServerAddressConfigured = true,
                    onOpenServerAddress = {},
                    onRestartApp = {},
                    isServerRestartPending = false,
                )
            }
        }

        composeRule.onNodeWithText("自定义").assertIsDisplayed()
        // 地址值（裸值与带 scheme 形态）都不得出现在设置页
        composeRule.onNodeWithText("192.168.0.156:9090").assertDoesNotExist()
        composeRule.onNodeWithText("http://192.168.0.156:9090").assertDoesNotExist()
    }

    /** 未配置这一档：行内标注「未配置」（应用不带默认地址，ADR-0053），同样不带地址值。 */
    @Test
    fun `服务器地址未配置时行内标注未配置`() {
        composeRule.setContent {
            AppPreview {
                SettingScreen(
                    cacheSize = "",
                    sourcesCount = 2,
                    appVersion = "1.4.0",
                    isLoggedIn = true,
                    hasUpdateAvailable = false,
                    updateState = UpdateState.Idle,
                    themeMode = ThemeMode.SYSTEM,
                    onCheckUpdate = {},
                    onDismissUpdateDialog = {},
                    onOpenCacheManage = {},
                    onOpenBookSource = {},
                    onOpenAbout = {},
                    onThemeSelected = {},
                    onLogout = {},
                    isServerAddressConfigured = false,
                    onOpenServerAddress = {},
                    onRestartApp = {},
                    isServerRestartPending = false,
                )
            }
        }

        composeRule.onNodeWithText("未配置").assertIsDisplayed()
        composeRule.onNodeWithText("10.0.2.2:9090").assertDoesNotExist()
    }

    /** 带箭头=点进去（同缓存/书源行的语义）：点行进服务器地址页，不在设置页里原地编辑。 */
    @Test
    fun `点服务器地址行进入服务器地址页`() {
        var openCalls = 0
        composeRule.setContent {
            AppPreview {
                SettingScreen(
                    cacheSize = "",
                    sourcesCount = 2,
                    appVersion = "1.4.0",
                    isLoggedIn = true,
                    hasUpdateAvailable = false,
                    updateState = UpdateState.Idle,
                    themeMode = ThemeMode.SYSTEM,
                    onCheckUpdate = {},
                    onDismissUpdateDialog = {},
                    onOpenCacheManage = {},
                    onOpenBookSource = {},
                    onOpenAbout = {},
                    onThemeSelected = {},
                    onLogout = {},
                    isServerAddressConfigured = true,
                    onOpenServerAddress = { openCalls++ },
                    onRestartApp = {},
                    isServerRestartPending = false,
                )
            }
        }

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.setting_server_address)
        ).performClick()

        assertEquals("点行应进服务器地址页而不是原地编辑", 1, openCalls)
    }

    /**
     * 重启提示的可见性由「待重启」状态驱动，而不是由保存回调里的局部状态驱动。
     *
     * 这一档直接以 `isServerRestartPending = true` 进入（等价于保存成功后 store 的状态，
     * 或 Activity 在写盘期间被重建、回调已丢的那一帧）：不点保存也必须有不可取消的重启提示。
     * 服务器地址页同样按此状态渲染（见 [ServerAddressScreenRenderTest]），两处同源。
     */
    @Test
    fun `地址已改待重启时设置页也渲染不可取消的重启提示`() {
        composeRule.setContent {
            AppPreview {
                SettingScreen(
                    cacheSize = "",
                    sourcesCount = 2,
                    appVersion = "1.4.0",
                    isLoggedIn = true,
                    hasUpdateAvailable = false,
                    updateState = UpdateState.Idle,
                    themeMode = ThemeMode.SYSTEM,
                    onCheckUpdate = {},
                    onDismissUpdateDialog = {},
                    onOpenCacheManage = {},
                    onOpenBookSource = {},
                    onOpenAbout = {},
                    onThemeSelected = {},
                    onLogout = {},
                    isServerAddressConfigured = true,
                    onOpenServerAddress = {},
                    onRestartApp = {},
                    isServerRestartPending = true,
                )
            }
        }

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.setting_server_address_saved_title)
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.setting_server_address_restart_now)
        ).assertIsDisplayed()
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
