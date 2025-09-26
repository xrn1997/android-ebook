package debug

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import com.blankj.utilcode.util.SPUtils
import com.ebook.api.entity.User
import com.ebook.common.event.KeyCode
import com.ebook.common.event.RxBusTag
import com.hwangjr.rxbus.RxBus
import com.therouter.router.Autowired
import com.therouter.router.Route
import com.xrn1997.common.mvvm.compose.BaseActivity
import com.xrn1997.common.ui.TextInButton
import com.xrn1997.common.util.ToastUtil


@Route(path = KeyCode.Me.TEST_LOGIN_PATH)
class LoginActivity : BaseActivity() {
    @Autowired
    @JvmField
    var path: String = String()
    private var mBundle: Bundle? = null //储存被拦截的信息

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
        val spUtils = SPUtils.getInstance()
        if (!spUtils.getBoolean(KeyCode.Login.SP_IS_LOGIN)) {
            spUtils.put(KeyCode.Login.SP_IS_LOGIN, true)
            spUtils.put(KeyCode.Login.SP_USERNAME, user.username)
            spUtils.put(KeyCode.Login.SP_PASSWORD, user.password)
            spUtils.put(KeyCode.Login.SP_NICKNAME, user.nickname)
            spUtils.put(KeyCode.Login.SP_USER_ID, user.id)
            spUtils.put(KeyCode.Login.SP_IMAGE, user.image)
            ToastUtil.showShort(this, "登录成功")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun initData() {
    }
}
