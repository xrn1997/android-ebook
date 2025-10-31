package debug

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import com.ebook.common.event.KeyCode
import com.ebook.common.util.SPUtil
import com.ebook.login.databinding.ActivityMainBinding
import com.therouter.TheRouter.build
import com.xrn1997.common.mvvm.view.BaseActivity
import com.xrn1997.common.util.ToastUtil.showShort

class MainActivity : BaseActivity<ActivityMainBinding>() {
    override fun initView() {
        binding.btnLogin.setOnClickListener {
            if (SPUtil.get(KeyCode.Login.SP_IS_LOGIN, false)) {
                showShort(this, "已经登录")
            } else {
                build(KeyCode.Login.LOGIN_PATH).navigation()
            }
        }
        binding.btnRegister.setOnClickListener {
            build(KeyCode.Login.REGISTER_PATH).navigation()
        }
        binding.btnInterrupt.setOnClickListener {
            val bundle = Bundle()
            bundle.putBoolean("123", true)
            build(KeyCode.Login.TEST_INTERRUPT_PATH)
                .withString("msg", "被therouter拦截的参数：")
                .withInt("key", 1)
                .withBundle("bundle2", bundle)
                .navigation()
        }
        binding.btnExit.setOnClickListener {
            showShort(this, "退出登录成功")
            SPUtil.remove(KeyCode.Login.SP_IS_LOGIN)
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
