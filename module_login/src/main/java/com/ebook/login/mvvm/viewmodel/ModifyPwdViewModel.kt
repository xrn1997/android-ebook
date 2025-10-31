package com.ebook.login.mvvm.viewmodel

import android.app.Application
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import androidx.core.text.trimmedLength
import androidx.lifecycle.viewModelScope
import com.ebook.api.utils.CoroutineAdapter
import com.ebook.common.event.KeyCode
import com.ebook.common.event.RxBusTag
import com.ebook.common.util.SPUtil
import com.ebook.login.ModifyPwdActivity
import com.ebook.login.mvvm.model.UserModel
import com.hwangjr.rxbus.RxBus
import com.therouter.TheRouter.build
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModifyPwdViewModel @Inject constructor(
    application: Application,
    model: UserModel
) : BaseViewModel<UserModel>(application, model) {
    /**
     * 当前该变量仅修改密码使用，注册不用。
     */
    @JvmField
    var username: String? = null
    @JvmField
    var mVerifyCode: String? = null // 验证码

    fun verify(username: String, verifyCode: String) {
        if (TextUtils.isEmpty(username)) { //用户名为空
            postToastEvent("手机号不能为空")
            return
        } else if (username.trimmedLength() < 11) { // 手机号码不足11位
            postToastEvent("请输入正确的手机号")
            return
        }
        if (!TextUtils.equals(verifyCode, mVerifyCode)) {
            postToastEvent("请输入正确的验证码")
            return
        }
        postFinishActivityEvent()
        toFgtPwdActivity()
    }

    private fun toFgtPwdActivity() {
        val bundle = Bundle()
        bundle.putString("username", username)
        Log.e(TAG, "toFgtPwdActivity: username:$username")
        postStartActivityEvent(ModifyPwdActivity::class.java, bundle)
    }

    fun modify(firstPwd: String, secondPwd: String) {
        if (firstPwd.isEmpty() || secondPwd.isEmpty()) {
            postToastEvent("密码未填写完整")
            return
        }
        if (firstPwd != secondPwd) { //两次密码不一致
            postToastEvent("两次密码不一致")
            return
        }
        Log.d(TAG, "modify: username: ${username},password: $firstPwd")
        val username = this.username
        if (username == null) {
            Log.e(TAG, "modify: 用户名为null")
            return
        }
        viewModelScope.launch {
            val result = mModel.modifyPwd(username, firstPwd)
            result.onSuccess { resp ->
                //  Log.d(TAG, "修改密码onNext: start");
                postToastEvent("修改成功")
                SPUtil.clear()
                RxBus.get().post(RxBusTag.SET_PROFILE_PICTURE_AND_NICKNAME)
                val bundle = Bundle()
                bundle.putString("username", username)
                bundle.putString("password", firstPwd)
                build(KeyCode.Login.LOGIN_PATH)
                    .with(bundle)
                    .navigation()
                //    Log.d(TAG, "修改密码onNext: finish");
                postFinishActivityEvent()
            }.onFailure { exception ->
                if (exception is CoroutineAdapter.ApiException) {
                    postToastEvent(exception.message())
                } else {
                    postToastEvent("${exception.message}")
                }
            }
        }
    }
}
