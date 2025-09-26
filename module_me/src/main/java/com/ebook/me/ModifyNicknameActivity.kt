package com.ebook.me

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.viewModels
import com.ebook.me.databinding.ActivityModifyNicknameBinding
import com.ebook.me.mvvm.viewmodel.ModifyViewModel
import com.xrn1997.common.mvvm.view.BaseMvvmActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ModifyNicknameActivity : BaseMvvmActivity<ActivityModifyNicknameBinding, ModifyViewModel>() {
    override val mViewModel: ModifyViewModel by viewModels()
    override fun initView() {
        binding.idBtnRegister.setOnClickListener {
            mViewModel.modifyNickname(binding.idEtReg1stPwd.text.toString())
        }
    }

    override fun initData() {
    }

    override fun onBindViewBinding(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        attachToParent: Boolean
    ): ActivityModifyNicknameBinding {
        return ActivityModifyNicknameBinding.inflate(inflater, parent, attachToParent)
    }
}
