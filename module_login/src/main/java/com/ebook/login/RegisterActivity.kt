package com.ebook.login

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.viewModels
import com.ebook.common.event.KeyCode
import com.ebook.login.databinding.ActivityRegisterBinding
import com.ebook.login.mvvm.viewmodel.RegisterViewModel
import com.therouter.router.Route
import com.xrn1997.common.mvvm.view.BaseMvvmActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
@Route(path = KeyCode.Login.REGISTER_PATH)
class RegisterActivity : BaseMvvmActivity<ActivityRegisterBinding, RegisterViewModel>() {
    override val mViewModel: RegisterViewModel by viewModels()
    override fun initView() {
        binding.idBtnRegister.setOnClickListener {
            val username = binding.idEtRegUsername.text.toString()
            val password1 = binding.idEtReg1stPwd.text.toString()
            val password2 = binding.idEtReg2ndPwd.text.toString()
            mViewModel.register(username, password1, password2)
        }
    }

    override fun initData() {
    }

    override fun onBindViewBinding(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        attachToParent: Boolean
    ): ActivityRegisterBinding {
        return ActivityRegisterBinding.inflate(inflater, parent, attachToParent)
    }
}