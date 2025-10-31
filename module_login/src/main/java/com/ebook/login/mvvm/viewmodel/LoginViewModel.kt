package com.ebook.login.mvvm.viewmodel

import android.app.Application
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.ebook.api.entity.User
import com.ebook.api.utils.CoroutineAdapter
import com.ebook.common.event.KeyCode
import com.ebook.common.event.RxBusTag
import com.ebook.common.util.SPUtil
import com.ebook.login.mvvm.model.UserModel
import com.hwangjr.rxbus.RxBus
import com.therouter.TheRouter.build
import com.xrn1997.common.constant.ErrorCode
import com.xrn1997.common.manager.RetrofitManager
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    application: Application,
    model: UserModel
) : BaseViewModel<UserModel>(application, model) {
    var bundle: Bundle? = null //被拦截的信息

    fun login(username: String, password: String) {
        if (TextUtils.isEmpty(username)) { //用户名为空
            postToastEvent("用户名不能为空")
            //  Log.d(TAG, "login: " + username);
            return
        }
        if (username.length < 11) { // 手机号码不足11位
            postToastEvent("请输入正确的手机号")
            return
        }
        if (TextUtils.isEmpty(password)) { //密码为空
            postToastEvent("密码不能为空")
            return
        }
        viewModelScope.launch {
            postShowLoadingViewEvent(true)
            val result = mModel.login(username, password)
            result.onSuccess { resp ->
                RetrofitManager.TOKEN = "Bearer " + resp.data?.token
                val user = resp.data?.user
                if (user == null) {
                    Log.e(TAG, "onNext: user==null")
                    return@launch
                }
                user.password = password //返回的是加密过的密码，不能使用，需要记住本地输入的密码。
                loginOnNext(user) //非自动登录
                RxBus.get().post(RxBusTag.SET_PROFILE_PICTURE_AND_NICKNAME, Any()) //通知其更新UI
            }.onFailure { exception ->
                if (exception is CoroutineAdapter.ApiException) {
                    if (exception.code == ErrorCode.USER_ERROR_A0230.code) {
                        RxBus.get().post(RxBusTag.SET_PROFILE_PICTURE_AND_NICKNAME, Any())
                        SPUtil.clear()
                        //   Log.d(TAG, "登录失效 is login 状态：" + SPUtils.getInstance().getString(KeyCode.Login.SP_IS_LOGIN));
                    }
                    postToastEvent(exception.message())
                } else {
                    postToastEvent("${exception.message}")
                }
            }
            postShowLoadingViewEvent(false)
        }
    }

    private fun loginOnNext(user: User) {
        //不是自动登录则调用以下语句
        SPUtil.apply {
            if (!get(KeyCode.Login.SP_IS_LOGIN, false)) {
                put(KeyCode.Login.SP_IS_LOGIN, true)
                put(KeyCode.Login.SP_USERNAME, user.username)
                put(KeyCode.Login.SP_PASSWORD, user.password)
                put(KeyCode.Login.SP_NICKNAME, user.nickname)
                put(KeyCode.Login.SP_USER_ID, user.id)
                put(KeyCode.Login.SP_IMAGE, user.image)
                postShowLoadingViewEvent(false)
                val path = bundle?.getString(KeyCode.Login.PATH)
                if (path != KeyCode.Login.LOGIN_PATH) {
                    build(path).navigation()
                }

                postFinishActivityEvent()
                postToastEvent("登录成功")
            }
        }
    }
}
