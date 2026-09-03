package com.ebook.me.page

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil.compose.AsyncImage
import com.ebook.common.event.KeyCode
import com.ebook.common.ui.CommonCard
import com.ebook.common.ui.CommonListDivider
import com.ebook.common.ui.CommonListItem
import com.ebook.me.R
import com.ebook.me.mvvm.viewmodel.MePageViewModel
import com.ebook.me.mvvm.viewmodel.MeUiState
import com.ebook.me.mvvm.viewmodel.ReadingStats
import com.therouter.TheRouter.build
import com.xrn1997.common.util.detectColor
import com.xrn1997.common.util.setStatusBarColor

/**
 * 我的页（Compose）：替代原 MainMeFragment。
 *
 * 布局为阅读类 App 常见的个人主页结构：
 * - 顶部 primary→tertiary 垂直渐变沉浸式头部：渐变延伸到状态栏后（宿主不消费状态栏
 *   insets，见 module_main MainActivity.enableFitsSystemWindows），内容区
 *   （大头像 + 昵称 + 账号副标题 + 右侧按钮）信息区纯展示，点击事件只挂右侧——
 *   已登录为编辑资料按钮，未登录为立即登录按钮（编辑资料唯一入口）
 * - 阅读概览卡片：书架藏书数 + 最近在读（本地 Room 数据，与登录态无关）
 * - 功能菜单卡片：图标用 Material Icons 彩色容器块，两项以
 *   primary/tertiary container 语义色区分
 *
 * 页面依赖经 [MePageViewModel] 收敛（Provider 由 TheRouter 创建，非 Hilt）。
 * 跨模块导航：TheRouter 的 navigation() 需要 Activity context（取自 [LocalContext]），
 * 所有跳转统一传 context，避免依赖路由器内部的 Activity 探测。
 */
@Composable
fun MePage(viewModel: MePageViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.meState.collectAsState()
    val readingStats by viewModel.readingStats.collectAsState()
    MainMeScreen(
        uiState = uiState,
        readingStats = readingStats,
        onLoginClick = { build(KeyCode.Login.LOGIN_PATH).navigation(context) },
        onMyCommentClick = { build(KeyCode.Me.COMMENT_PATH).navigation(context) },
        onMyInfoClick = { build(KeyCode.Me.MODIFY_PATH).navigation(context) },
        onSettingClick = { build(KeyCode.Me.SETTING_PATH).navigation(context) }
    )
}

/**
 * 我的页内容：渐变头部 + 阅读概览卡片 + 功能菜单卡片。
 */
@Composable
fun MainMeScreen(
    uiState: MeUiState,
    readingStats: ReadingStats,
    onLoginClick: () -> Unit,
    onMyCommentClick: () -> Unit,
    onMyInfoClick: () -> Unit,
    onSettingClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            MeHeader(
                uiState = uiState,
                // 编辑资料/登录入口只收敛在右侧按钮：头部信息区纯展示，点击无副作用
                onEditClick = onMyInfoClick,
                onLoginClick = onLoginClick
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                ReadingStatsCard(readingStats = readingStats)
                Spacer(modifier = Modifier.height(12.dp))
                MeMenuCard(
                    onMyCommentClick = onMyCommentClick,
                    onSettingClick = onSettingClick
                )
            }
        }
    }
}

/**
 * 渐变沉浸式头部：大头像（带半透明光环）+ 昵称/登录占位 + 账号副标题 + 右侧入口按钮。
 *
 * 渐变铺在 Box 上并延伸到状态栏后（statusBarsPadding 只作用于内容行），
 * 形成「顶栏融入头部」的沉浸感；未登录时副标题换成登录引导文案。
 *
 * 深色适配：primary/tertiary 在深色配色里是亮色（如 #D0BCFF），若沿用会形成
 * 「深色页面 + 顶部大块刺眼亮渐变」的突兀效果，且状态栏图标（深色下强制白色）
 * 落在亮渐变上会看不清。故深色模式改用 primaryContainer/tertiaryContainer 深色容器
 * 做渐变，前景色一并切换为 onPrimaryContainer，保证明暗协调、对比度达标。
 *
 * 信息区（头像/昵称）为纯展示，不响应点击；点击事件只挂在右侧按钮上——
 * 已登录为编辑资料按钮，未登录为「立即登录」按钮，避免"整块可点"造成重复入口。
 *
 * 状态栏图标深浅由本页按渐变起始色亮度自适应（[adaptStatusBarIcons]）：图标压在饱和渐变上，
 * 沿用宿主「跟随系统深浅色」的默认值会在浅色模式得到深色图标、几乎看不清。
 */
@Composable
private fun MeHeader(
    uiState: MeUiState,
    onEditClick: () -> Unit,
    onLoginClick: () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = MaterialTheme.colorScheme
    // 深色模式换用深色容器渐变，前景色同步切换（见函数 KDoc 说明）
    val gradientStart = if (darkTheme) colorScheme.primaryContainer else colorScheme.primary
    val gradientEnd = if (darkTheme) colorScheme.tertiaryContainer else colorScheme.tertiary
    val onGradient = if (darkTheme) colorScheme.onPrimaryContainer else colorScheme.onPrimary
    // 渐变压在状态栏后面，状态栏图标深浅需随渐变起始色调整（见 adaptStatusBarIcons）
    adaptStatusBarIcons(gradientStart)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        gradientStart,
                        gradientEnd
                    )
                )
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MeAvatar(
                isLoggedIn = uiState.isLoggedIn,
                avatarUrl = uiState.avatarUrl,
                ringColor = onGradient
            )
            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (uiState.isLoggedIn && uiState.nickname.isNotEmpty()) {
                        uiState.nickname
                    } else {
                        stringResource(R.string.me_not_logged_in)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = onGradient,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (uiState.isLoggedIn && uiState.username.isNotEmpty()) {
                        stringResource(R.string.me_account_prefix, uiState.username)
                    } else {
                        stringResource(R.string.me_login_hint)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = onGradient.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 右侧入口按钮：已登录 → 编辑资料 / 未登录 → 立即登录，点击事件只挂在这里。
            // clip 放在 clickable 外层：默认涟漪按外接矩形绘制，不裁剪会露出方形阴影
            if (uiState.isLoggedIn) {
                Surface(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onEditClick),
                    shape = CircleShape,
                    color = onGradient.copy(alpha = 0.2f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = stringResource(R.string.me_edit_profile_desc),
                            tint = onGradient,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            } else {
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onLoginClick),
                    shape = RoundedCornerShape(50),
                    color = onGradient
                ) {
                    Text(
                        text = stringResource(R.string.me_login_now),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = gradientStart,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * 状态栏图标深浅跟随头部渐变起始色：渐变延伸到状态栏后，时钟/电量图标直接落在渐变上。
 *
 * 宿主（module_main 的 MainActivity 经 BaseActivity）只在 onCreate 按系统深浅色给一次默认值：
 * 浅色模式下是深色图标，而头部渐变起始色（primary / 深色下的 primaryContainer）明度都低于 0.5，
 * 深字压深底几乎看不清，故按起始色亮度重给（亮度 < 0.5 → 白色图标）。
 *
 * 离页（切走 Tab 时本页从组合中移出）恢复宿主默认「跟随系统深浅色」，
 * 避免白色图标残留到书架/书城的 surface 顶栏上。独立运行宿主（module_me test/debug）无
 * 其他 Tab，恢复只发生在页面销毁时，不影响观感。
 *
 * @param gradientStart 头部渐变起始色（即状态栏区域实际着色的颜色）
 */
@Composable
private fun adaptStatusBarIcons(gradientStart: Color) {
    // 宿主上下文（集成宿主与独立宿主都是 Activity，预览等场景下为 null 则跳过）
    val activity = LocalActivity.current
    LaunchedEffect(gradientStart) {
        activity?.setStatusBarColor(gradientStart.toArgb().detectColor())
    }
    DisposableEffect(Unit) {
        onDispose { activity?.setStatusBarColor() }
    }
}

/**
 * 用户头像：80dp 外圈半透明光环 + 72dp 圆形图像。
 *
 * 登录且头像 URL 非空时加载网络图（[AsyncImage]），否则回退 image_default 默认图。
 *
 * @param ringColor 光环/背景语义色（随头部深色适配切换，保证与渐变背景协调）
 */
@Composable
private fun MeAvatar(isLoggedIn: Boolean, avatarUrl: String, ringColor: Color) {
    Box(
        modifier = Modifier.size(80.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            color = ringColor.copy(alpha = 0.25f)
        ) {}
        if (isLoggedIn && avatarUrl.isNotEmpty()) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = stringResource(R.string.me_avatar_desc),
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Image(
                painter = painterResource(id = R.drawable.image_default),
                contentDescription = stringResource(R.string.me_default_avatar_desc),
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        }
    }
}

/**
 * 阅读概览卡片：藏书数 + 最近在读，两列数据展示。
 *
 * 数据来自本地书架（Room），未登录也展示——阅读 App 的核心资产是本地阅读数据。
 * 书架为空时右列降级为「暂无」占位，卡片保留结构稳定不跳变。
 */
@Composable
private fun ReadingStatsCard(readingStats: ReadingStats) {
    CommonCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatsColumn(
                value = readingStats.shelfCount.toString(),
                label = stringResource(R.string.me_shelf_count),
                modifier = Modifier.weight(1f)
            )
            // 两列数据间的竖向分割线
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(28.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            StatsColumn(
                value = readingStats.recentBookName ?: stringResource(R.string.me_recent_book_empty),
                label = stringResource(R.string.me_recent_book),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
                isBookName = true
            )
        }
    }
}

/**
 * 统计列：大号数值 + 小号标签。
 *
 * @param isBookName 书名模式：数值行号缩小、单行省略（书名可能很长，数字模式则保持大号）
 */
@Composable
private fun StatsColumn(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    isBookName: Boolean = false
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isBookName) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = value,
                style = if (isBookName) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/**
 * 功能菜单卡片：我的评论 / 设置，项间以缩进分割线分隔。
 *
 * 编辑资料入口收敛在头部右侧按钮，不再重复出现菜单项。
 */
@Composable
private fun MeMenuCard(
    onMyCommentClick: () -> Unit,
    onSettingClick: () -> Unit
) {
    CommonCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            CommonListItem(
                icon = Icons.AutoMirrored.Outlined.Comment,
                title = stringResource(R.string.my_comment_title),
                iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                onClick = onMyCommentClick
            )
            CommonListDivider()
            CommonListItem(
                icon = Icons.Outlined.Settings,
                title = stringResource(R.string.setting_title),
                iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                iconContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                onClick = onSettingClick
            )
        }
    }
}
