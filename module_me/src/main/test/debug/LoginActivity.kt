package debug

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import com.ebook.api.entity.User
import com.ebook.common.event.KeyCode
import com.ebook.common.event.RxBusTag
import com.ebook.common.util.SPUtil
import com.hwangjr.rxbus.RxBus
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseActivity
import com.xrn1997.common.ui.TextInButton
import com.xrn1997.common.util.ToastUtil


@Route(path = KeyCode.Me.TEST_LOGIN_PATH)
class LoginActivity : BaseActivity() {

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RxBus.get().register(this)
    }

    public override fun onDestroy() {
        super.onDestroy()
        RxBus.get().unregister(this)
    }

    @Composable
    override fun InitView() {
        TextInButton(onClick = {
            val user = User()
            user.id = 0
            user.nickname = "二哈"
            user.image = ""
            user.password = "123456"
            user.username = "xrn1997"
            loginOnNext(user)
            RxBus.get().post(RxBusTag.SET_PROFILE_PICTURE_AND_NICKNAME, Any()) //通知其更新UI
            onBackPressedDispatcher.onBackPressed()
        })
    }

    private fun loginOnNext(user: User) {
        SPUtil.apply {
            if (!get(KeyCode.Login.SP_IS_LOGIN, false)) {
                put(KeyCode.Login.SP_IS_LOGIN, true)
                put(KeyCode.Login.SP_USERNAME, user.username)
                put(KeyCode.Login.SP_PASSWORD, user.password)
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
