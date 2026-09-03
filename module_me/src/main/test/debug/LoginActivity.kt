package debug

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ebook.api.entity.User
import com.ebook.common.domain.UserSession
import com.ebook.common.domain.UserSessionManager
import com.ebook.common.event.KeyCode
import com.ebook.common.repository.ProfileRepository
import com.ebook.common.ui.CommonCard
import com.ebook.common.ui.CommonListItem
import com.ebook.me.R
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseActivity
import com.xrn1997.common.util.ToastUtil
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * module_me 独立运行（isModule=true）的模拟登录页。
 *
 * 独立运行时 module_login 不在依赖内，真实登录路由（LOGIN_PATH）经
 * TestApplication 的 PathReplaceInterceptor 重定向到本页。本页提供：
 * - 模拟用户卡片（头像/昵称/账号），一键写入本地会话
 * - 独立运行说明，避免误以为这是真实登录
 *
 * 会话写入与真实登录一致：[UserSessionManager.saveSession] 会更新登录态
 * StateFlow、写入 TokenHolder 并持久化（独立运行不连后端，token/刷新凭证为占位值）。
 */
@AndroidEntryPoint
@Route(path = KeyCode.Me.TEST_LOGIN_PATH)
class LoginActivity : BaseActivity() {
    @Inject lateinit var profileRepository: ProfileRepository
    @Inject lateinit var userSessionManager: UserSessionManager

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        toolbarTitle.value = getString(R.string.me_test_login_title)
    }

    @Composable
    override fun PageContent() {
        TestLoginScreen(
            onLoginClick = { loginOnNext(TEST_USER) },
            onLogoutClick = { logoutAndBack() }
        )
    }

    /**
     * 模拟登录成功：保存会话（含登录态刷新 + ProfileRepository 展示数据）后返回。
     *
     * lifecycleScope 使用 Main.immediate，saveSession 无挂起点，会同步执行完毕，
     * 返回时 MePage 已能收集到最新登录态。
     */
    private fun loginOnNext(user: User) {
        lifecycleScope.launch {
            userSessionManager.saveSession(
                UserSession(
                    userId = user.id,
                    username = user.username,
                    nickname = user.nickname,
                    avatar = user.image,
                    token = "test-token"
                ),
                user.password
            )
            profileRepository.updatePicture(user.image)
            profileRepository.updateNickname(user.nickname)
            ToastUtil.showShort(this@LoginActivity, getString(R.string.me_test_login_success))
            onBackPressedDispatcher.onBackPressed()
        }
    }

    /** 模拟退出登录：清空会话后返回，用于验证未登录态 UI。 */
    private fun logoutAndBack() {
        userSessionManager.clearSession()
        profileRepository.clearAuthData()
        ToastUtil.showShort(this, getString(R.string.me_test_logout_success))
        onBackPressedDispatcher.onBackPressed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    companion object {
        /** 模拟用户：昵称/账号与我的评论测试数据（user_comments.json）保持一致 */
        private val TEST_USER = User().apply {
            id = 0
            nickname = "测试用户"
            image = ""
            password = "123456"
            username = "test_user"
        }
    }
}

/**
 * 模拟登录页内容：说明卡 + 模拟用户卡 + 操作入口。
 *
 * 视觉沿用 lib_book_common 的共享组件（CommonCard/CommonListItem，见 ADR-0006），
 * 保证独立调试时的观感与正式模块一致。
 */
@Composable
private fun TestLoginScreen(
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // 独立运行说明：放在最前，先建立"这是调试页"的预期
            CommonCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Science,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.me_test_mode_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 模拟用户卡片：头像 + 昵称/账号 + 一键登录
            CommonCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.image_default),
                        contentDescription = stringResource(R.string.me_test_avatar_desc),
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.me_test_user_nickname),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.me_test_user_account),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onLoginClick,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.me_test_login_button),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 辅助操作：一键退出，方便在已登录/未登录态之间切换验证
            CommonCard(modifier = Modifier.fillMaxWidth()) {
                CommonListItem(
                    icon = Icons.Outlined.Science,
                    title = stringResource(R.string.me_test_reset_login),
                    iconContainerColor = MaterialTheme.colorScheme.errorContainer,
                    iconContentColor = MaterialTheme.colorScheme.onErrorContainer,
                    showArrow = false,
                    onClick = onLogoutClick
                )
            }
        }
    }
}
