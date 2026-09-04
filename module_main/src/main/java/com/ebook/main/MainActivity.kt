package com.ebook.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ebook.common.provider.IBookProvider
import com.ebook.common.provider.IFindProvider
import com.ebook.common.provider.IMeProvider
import androidx.lifecycle.lifecycleScope
import com.ebook.api.auth.SessionEvent
import com.ebook.api.auth.SessionEventBus
import com.ebook.common.domain.UserSessionManager
import com.ebook.common.event.KeyCode
import com.therouter.TheRouter
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseActivity
import com.xrn1997.common.util.ToastUtil
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.system.exitProcess
import kotlinx.coroutines.launch

/**
 * 主界面（三个 Tab 的宿主容器）。
 *
 * 继承 lib_common 的 Compose 基类：基类只提供主题与状态覆盖层，**不**负责 insets 偏移
 * （见 [enableFitsSystemWindows]）——三个 Tab 的顶栏要画到状态栏后面才有沉浸式观感，
 * 本页不启用 Toolbar（Tab 页各自渲染自己的顶栏），
 * [PageContent] 直接组合 [MainScreen]（系统栏 insets 由本页与各 Tab 页面自行避让）。
 *
 * 会话过期处置：本页是登录后最长驻留的宿主，故由它订阅 [SessionEventBus] 的
 * [SessionEvent.SessionExpired] 事件（规格中的统一收口处置：清会话 + 提示 + 立即跳登录页）；
 * SplashActivity 启动后即结束，不适合承担长时订阅。
 */
@AndroidEntryPoint
@Route(path = KeyCode.Main.MAIN_PATH)
class MainActivity : BaseActivity() {
    private var exitTime: Long = 0

    @Inject
    lateinit var sessionEventBus: SessionEventBus

    @Inject
    lateinit var userSessionManager: UserSessionManager

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        // 会话「救不回来」（refresh 失败）的统一处置：事件由网络层收口发射，此处幂等执行
        lifecycleScope.launch {
            sessionEventBus.events.collect { event ->
                if (event is SessionEvent.SessionExpired) {
                    userSessionManager.clearSession()
                    ToastUtil.showShort(this@MainActivity, getString(R.string.session_expired))
                    TheRouter.build(KeyCode.Login.LOGIN_PATH).navigation()
                }
            }
        }
    }

    override fun enableToolbar(): Boolean = false

    /**
     * 关闭基类的系统栏偏移，把 insets 下放给各 Tab 页面自己处理。
     *
     * 基类在 `enableToolbar()=false` 时默认取 `ScaffoldDefaults.contentWindowInsets` 作为内容
     * padding 并随后消费掉，结果是整个 Tab 内容被推到状态栏下方、状态栏区域只剩宿主
     * Scaffold 的 background：书架/书城的顶栏是 surface（与 background 几乎同色）看不出来，
     * 我的页的饱和渐变头部则出现一条明显断层（渐变从状态栏下沿才开始）。
     *
     * 关闭后各页面按 M3 默认 bar insets 自行避让——[androidx.compose.material3.TopAppBar]
     * 自带状态栏避让（容器色延伸到状态栏后）、[androidx.compose.material3.NavigationBar] 自带
     * 手势条避让、MePage 渐变头部用 statusBarsPadding 只偏移内容——与 module_book /
     * module_find / module_me 独立运行宿主（无人消费 insets）的渲染路径保持一致。
     */
    override fun enableFitsSystemWindows(): Boolean = false

    @Composable
    override fun PageContent() {
        MainScreen(
            onBackPress = { exit() }
        )
    }

    private fun exit() {
        if (System.currentTimeMillis() - exitTime > 2000) {
            ToastUtil.showShort(this, getString(R.string.press_again_to_exit))
            exitTime = System.currentTimeMillis()
        } else {
            finish()
            exitProcess(0)
        }
    }
}

/**
 * 主页三个 Tab 的路由定义。
 *
 * 文案走字符串资源（对齐纯 View 时代 menu_navigation.xml 的 @string 引用），
 * 图标用 Material 矢量图标（选中/常态的区分由 NavigationBar 语义色承担，见项目图标规范）。
 */
sealed class Screen(val route: String, @param:StringRes val titleRes: Int, val icon: ImageVector) {
    data object Bookshelf : Screen("bookshelf", R.string.title_bookshelf, Icons.Default.Book)
    data object Bookstore : Screen("bookstore", R.string.title_bookstore, Icons.Default.Explore)
    data object Me : Screen("me", R.string.title_me, Icons.Default.Person)
}

/**
 * 主页骨架：底部导航 + 三个 Tab 的 NavHost。
 *
 * 选中态单一数据源为 navController 的回退栈（派生自 currentBackStackEntryAsState），
 * 不再额外维护本地选中状态，避免双份状态在进程恢复等场景下不同步。
 *
 * insets 划分：[NavigationBar] 自行含手势条避让，故内容区不再叠加默认的
 * `ScaffoldDefaults.contentWindowInsets`（否则会把 Tab 内容推离状态栏，
 * 各 Tab 顶栏的沉浸式避让失效），状态栏避让下沉到各 Tab 页面的顶栏。
 */
@Composable
fun MainScreen(
    onBackPress: () -> Unit
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    BackHandler {
        onBackPress()
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                val screens = listOf(Screen.Bookshelf, Screen.Bookstore, Screen.Me)
                screens.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = stringResource(screen.titleRes)) },
                        label = { Text(stringResource(screen.titleRes)) },
                        selected = currentRoute == screen.route,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        },
        contentWindowInsets = WindowInsets(0.dp)
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Screen.Bookshelf.route,
            modifier = Modifier
                .padding(paddingValues)
                // paddingValues 此时只带底部导航栏高度（含其已避让的手势条），消费掉它
                // 以免 Tab 页内容再叠一次底部避让（顶部 insets 保持不消费，留给各 Tab 顶栏）
                .consumeWindowInsets(paddingValues)
        ) {
            // 各 Tab 直接组合 Provider 暴露的 Compose 页面（替代原 FragmentContainerView 嵌 Fragment）：
            // 页面 ViewModel 经 hiltViewModel() 绑定 NavBackStackEntry，切 Tab 保留状态、返回栈退出时销毁
            composable(Screen.Bookshelf.route) {
                TheRouter.get(IBookProvider::class.java)?.mainBookPage?.invoke()
            }
            composable(Screen.Bookstore.route) {
                TheRouter.get(IFindProvider::class.java)?.mainFindPage?.invoke()
            }
            composable(Screen.Me.route) {
                TheRouter.get(IMeProvider::class.java)?.mainMePage?.invoke()
            }
        }
    }
}
