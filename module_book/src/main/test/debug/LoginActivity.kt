package debug

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import com.ebook.api.entity.User
import com.ebook.common.event.KeyCode
import com.ebook.common.repository.ProfileRepository
import com.ebook.common.util.SPUtil
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseActivity
import com.xrn1997.common.ui.TextInButton
import com.xrn1997.common.util.ToastUtil
import kotlinx.coroutines.launch
import javax.inject.Inject
import dagger.hilt.android.AndroidEntryPoint


@AndroidEntryPoint
@Route(path = KeyCode.Book.TEST_LOGIN_PATH)
class LoginActivity : BaseActivity() {
    @Inject lateinit var profileRepository: ProfileRepository

    @Composable
    override fun PageContent() {
        TextInButton(onClick = {
            val user = User()
            user.id = 0
            user.nickname = "二哈"
            user.image = ""
            user.password = "123456"
            user.username = "xrn1997"
            loginOnNext(user)
            profileRepository.updatePicture(user.image ?: "")
            profileRepository.updateNickname(user.nickname)
            onBackPressedDispatcher.onBackPressed()
        })
    }

    private fun loginOnNext(user: User) {
        SPUtil.apply {
            if (!get(KeyCode.Login.SP_IS_LOGIN, false)) {
                put(KeyCode.Login.SP_IS_LOGIN, true)
                put(KeyCode.Login.SP_USERNAME, user.username)
                // 密码不落盘（ADR-0008）：这里只写展示身份键，绝不写密码
                put(KeyCode.Login.SP_NICKNAME, user.nickname)
                put(KeyCode.Login.SP_USER_ID, user.id)
                put(KeyCode.Login.SP_IMAGE, user.image)
                ToastUtil.showShort(this@LoginActivity, "登录成功")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun initData() {
    }
}
