package com.ebook.login.mvvm.viewmodel

import android.app.Application
import android.text.TextUtils
import android.util.Log
import com.ebook.api.dto.RespDTO
import com.ebook.api.entity.LoginDTO
import com.ebook.login.mvvm.model.RegisterModel
import com.xrn1997.common.event.SimpleObserver
import com.xrn1997.common.http.ExceptionHandler
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class RegisterViewModel @Inject constructor(
    application: Application,
    model: RegisterModel
) : BaseViewModel<RegisterModel>(application, model) {

    fun register(username: String, firstPwd: String, secondPwd: String) {
        if (TextUtils.isEmpty(username)) { //用户名为空
            mToastLiveEvent.setValue("用户名不能为空")
            return
        }
        if (TextUtils.getTrimmedLength(username) < 11) { // 手机号码不足11位
            mToastLiveEvent.setValue("请输入正确的手机号")
            return
        }
        if (TextUtils.isEmpty(firstPwd) || TextUtils.isEmpty((secondPwd))) {
            mToastLiveEvent.setValue("密码未填写完整")
            return
        }
        if (!TextUtils.equals(firstPwd, secondPwd)) { //两次密码不一致
            mToastLiveEvent.setValue("两次密码不一致")
            return
        }
        postShowLoadingViewEvent(true)
        mModel.register(username, firstPwd)
            .doOnSubscribe(this)
            .subscribe(object : SimpleObserver<RespDTO<LoginDTO>>() {
                override fun onNext(loginDTORespDTO: RespDTO<LoginDTO>) {
                    if (loginDTORespDTO.code == ExceptionHandler.AppError.SUCCESS) {
                        mToastLiveEvent.setValue("注册成功")
                        postShowLoadingViewEvent(false)
                        postFinishActivityEvent()
                    } else {
                        onError(Throwable(loginDTORespDTO.error))
                    }
                }

                override fun onError(e: Throwable) {
                    Log.v(TAG, "error:", e)
                    postShowLoadingViewEvent(false)
                }
            })
    }

    companion object {
        private val TAG: String = RegisterViewModel::class.java.simpleName
    }
}
