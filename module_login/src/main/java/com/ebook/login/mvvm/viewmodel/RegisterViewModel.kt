package com.ebook.login.mvvm.viewmodel

import android.app.Application
import android.text.TextUtils
import androidx.core.text.trimmedLength
import androidx.lifecycle.viewModelScope
import com.ebook.api.utils.CoroutineAdapter
import com.ebook.login.mvvm.model.UserModel
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RegisterViewModel @Inject constructor(
    application: Application,
    model: UserModel
) : BaseViewModel<UserModel>(application, model) {

    fun register(username: String, firstPwd: String, secondPwd: String) {
        if (TextUtils.isEmpty(username)) { //用户名为空
            postToastEvent("用户名不能为空")
            return
        }
        if (username.trimmedLength() < 11) { // 手机号码不足11位
            postToastEvent("请输入正确的手机号")
            return
        }
        if (TextUtils.isEmpty(firstPwd) || TextUtils.isEmpty((secondPwd))) {
            postToastEvent("密码未填写完整")
            return
        }
        if (!TextUtils.equals(firstPwd, secondPwd)) { //两次密码不一致
            postToastEvent("两次密码不一致")
            return
        }
        viewModelScope.launch {
            postShowLoadingViewEvent(true)
            val result = mModel.register(username, firstPwd)
            result.onSuccess { resp ->
                postToastEvent("注册成功")
                postShowLoadingViewEvent(false)
                postFinishActivityEvent()
            }.onFailure { exception ->
                if (exception is CoroutineAdapter.ApiException) {
                    postToastEvent(exception.message())
                } else {
                    postToastEvent("${exception.message}")
                }
                postShowLoadingViewEvent(false)
            }
        }
    }
}
