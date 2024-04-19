package com.ebook.login

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import com.ebook.common.event.KeyCode
import com.ebook.login.databinding.ActivityLoginBinding
import com.ebook.login.mvvm.factory.LoginViewModelFactory
import com.ebook.login.mvvm.viewmodel.LoginViewModel
import com.therouter.router.Autowired
import com.therouter.router.Route
import com.xrn1997.common.mvvm.view.BaseMvvmActivity

@Route(path = KeyCode.Login.LOGIN_PATH)
class LoginActivity : BaseMvvmActivity<ActivityLoginBinding, LoginViewModel>() {
    @Autowired
    @JvmField
    var path: String? = null
    private var mBundle: Bundle? = null //储存被拦截的信息
    override fun onBindLayout(): Int {
        return R.layout.activity_login
    }

    override fun onBindViewModel(): Class<LoginViewModel> {
        return LoginViewModel::class.java
    }

    override fun onBindViewModelFactory(): ViewModelProvider.Factory {
        return LoginViewModelFactory
    }

    override fun onBindVariableId(): Int {
        return BR.viewModel
    }

    /**
     * 禁止显示Toolbar，默认为true
     */
    override fun enableToolbar(): Boolean {
        return false
    }

    override fun initView() {
        val mTvRegister = findViewById<View>(R.id.id_tv_register) as TextView
        val mTvForgetPwd = findViewById<View>(R.id.id_tv_fgt_pwd) as TextView
        mTvRegister.setOnClickListener { toRegisterActivity() }
        mTvForgetPwd.setOnClickListener { toForgetPwdActivity() }
    }

    override fun initData() {}
    override fun onStart() {
        super.onStart()
        val bundle = this.intent.extras
        var username: String? = ""
        var password: String? = ""
        if (bundle != null && !bundle.isEmpty()) {
            username = bundle.getString("username")
            password = bundle.getString("password")
        }
        if (!TextUtils.isEmpty(username) && !TextUtils.isEmpty(password)) {
            mViewModel.username.set(username)
            mViewModel.password.set(password)
        }
        if (!TextUtils.isEmpty(path) && mBundle == null) {
            mViewModel.path = path
            if (bundle != null && !bundle.isEmpty()) {
                mBundle = bundle
                mViewModel.bundle = mBundle
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun toRegisterActivity() {
        val intent = Intent(this, RegisterActivity::class.java)
        startActivity(intent)
    }

    private fun toForgetPwdActivity() {
        val intent = Intent(this, VerifyUserActivity::class.java)
        startActivity(intent)
    }

    override fun initViewObservable() {}
}
