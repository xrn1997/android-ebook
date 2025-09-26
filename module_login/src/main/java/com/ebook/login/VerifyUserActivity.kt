package com.ebook.login

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.viewModels
import com.ebook.common.event.KeyCode
import com.ebook.login.databinding.ActivityVerifyUserBinding
import com.ebook.login.mvvm.viewmodel.ModifyPwdViewModel
import com.therouter.router.Route
import com.xrn1997.common.mvvm.view.BaseMvvmActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlin.random.Random

@AndroidEntryPoint
@Route(path = KeyCode.Login.MODIFY_PATH)
class VerifyUserActivity : BaseMvvmActivity<ActivityVerifyUserBinding, ModifyPwdViewModel>() {
    override val mViewModel: ModifyPwdViewModel by viewModels()
    /**
     * todo 用真的验证码，而不是编的。
     */
    private fun getVerifyCode() {
        // 生成六位随机数字的验证码
        mViewModel.mVerifyCode = Random.nextInt(100000, 1000000).toString()
        // 弹出提醒对话框，提示用户六位验证码数字
        val builder = AlertDialog.Builder(getContext())
        builder.setTitle("请记住验证码")
        builder.setMessage("手机号${mViewModel.username}，本次验证码是${mViewModel.mVerifyCode}，请输入验证码")
        builder.setPositiveButton("好的", null)
        val alert = builder.create()
        alert.show()
    }

    override fun initView() {
        binding.btnVerifycode.setOnClickListener { getVerifyCode() }
        binding.idBtnLogin.setOnClickListener {
            val username = binding.idEtFgtUsername.text.toString()
            val verifyCode = binding.idEtFgtVerifyCode.text.toString()
            mViewModel.verify(username, verifyCode)
        }
    }

    override fun initData() {}

    override fun onBindViewBinding(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        attachToParent: Boolean
    ): ActivityVerifyUserBinding {
        return ActivityVerifyUserBinding.inflate(inflater, parent, attachToParent)
    }
}
