package com.ebook.login

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.viewModels
import com.ebook.login.databinding.ActivityModifyPwdBinding
import com.ebook.login.mvvm.viewmodel.ModifyPwdViewModel
import com.hwangjr.rxbus.RxBus
import com.xrn1997.common.mvvm.view.BaseMvvmActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ModifyPwdActivity :
    BaseMvvmActivity<ActivityModifyPwdBinding, ModifyPwdViewModel>() {
    override val mViewModel: ModifyPwdViewModel by viewModels()
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RxBus.get().register(this)
    }

    override fun initView() {
        binding.idBtnRegister.setOnClickListener {
            val newPwd1 = binding.idEtReg1stPwd.text.toString()
            val newPwd2 = binding.idEtReg2ndPwd.text.toString()
            mViewModel.modify(newPwd1, newPwd2)
        }
    }

    override fun initData() {
        val bundle = this.intent.extras
        if (bundle != null) {
            val username = bundle.getString("username")
            mViewModel.username = username
        }
    }
    public override fun onDestroy() {
        super.onDestroy()
        RxBus.get().unregister(this)
    }

    override fun onBindViewBinding(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        attachToParent: Boolean
    ): ActivityModifyPwdBinding {
        return ActivityModifyPwdBinding.inflate(inflater, parent, attachToParent)
    }

}
