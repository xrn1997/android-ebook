package debug

import android.view.LayoutInflater
import android.view.ViewGroup
import com.blankj.utilcode.util.SPUtils
import com.ebook.common.event.KeyCode
import com.ebook.login.databinding.ActivityMainBinding
import com.therouter.TheRouter.build
import com.xrn1997.common.mvvm.view.BaseActivity
import com.xrn1997.common.util.ToastUtil.showShort

class MainActivity : BaseActivity<ActivityMainBinding>() {
    override fun initView() {
        binding.btnLogin.setOnClickListener {
            build(KeyCode.Login.LOGIN_PATH).navigation()
        }
        binding.btnRegister.setOnClickListener {
            build(KeyCode.Login.REGISTER_PATH)
                .withString("msg", "TheRouter传递过来的不需要登录的参数msg")
                .navigation()
        }
        binding.btnInterrupt.setOnClickListener {
            build(KeyCode.Login.TEST_INTERRUPT_PATH)
                .withString("msg", "TheRouter传递过来的需要登录的参数msg")
                .navigation()
        }
        binding.btnExit.setOnClickListener {
            showShort(this, "退出登录成功")
            SPUtils.getInstance().remove(KeyCode.Login.SP_IS_LOGIN)
        }
    }

    override fun initData() {

    }

    override fun onBindViewBinding(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        attachToParent: Boolean
    ): ActivityMainBinding {
        return ActivityMainBinding.inflate(inflater, parent, attachToParent)
    }
}
