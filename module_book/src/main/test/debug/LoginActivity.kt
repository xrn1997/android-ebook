package debug

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import com.ebook.api.entity.User
import com.ebook.common.domain.UserSession
import com.ebook.common.domain.UserSessionManager
import com.ebook.common.event.KeyCode
import com.ebook.common.repository.ProfileRepository
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseActivity
import com.xrn1997.common.ui.TextInButton
import com.xrn1997.common.util.ToastUtil
import kotlinx.coroutines.launch
import javax.inject.Inject
import dagger.hilt.android.AndroidEntryPoint


/**
 * module_book 独立运行（isModule=true）时的模拟登录页。
 *
 * 会话必须经 [UserSessionManager.saveSession] 建立（认证状态的唯一 seam）：
 * 原先只直写 `SPUtil` 身份键，会让 `currentUser` 一直为空，按 userId 判定的
 * 评论本人门禁、以及任何读会话身份的页面在调试宿主里都拿不到身份。
 * 写法与 module_me 的同名调试页保持一致（见其 KDoc）。
 */
@AndroidEntryPoint
@Route(path = KeyCode.Book.TEST_LOGIN_PATH)
class LoginActivity : BaseActivity() {
    @Inject lateinit var profileRepository: ProfileRepository
    @Inject lateinit var userSessionManager: UserSessionManager

    @Composable
    override fun PageContent() {
        TextInButton(onClick = {
            loginOnNext(TEST_USER)
        })
    }

    /**
     * 模拟登录：保存会话（登录态 + TokenHolder + 持久化）并同步 ProfileRepository 展示态。
     *
     * 独立运行不连后端，token 与刷新凭证均为占位值。
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
                "test-refresh-token"
            )
            profileRepository.updatePicture(user.image)
            profileRepository.updateNickname(user.nickname)
            ToastUtil.showShort(this@LoginActivity, "登录成功")
            onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun initData() {
    }

    companion object {
        /**
         * 模拟用户：id 与昵称须与 `user_login.json` 的 uid/nickname 一致——
         * mock 的服务端署名（CommentNetworkTest）取自同一份资产，id 不同则评论
         * 本人判定（按 userId）永不通过。
         */
        private val TEST_USER = User().apply {
            id = 1
            nickname = "测试用户"
            image = ""
            username = "user_test01"
        }
    }
}
