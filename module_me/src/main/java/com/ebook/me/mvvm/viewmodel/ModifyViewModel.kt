package com.ebook.me.mvvm.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import com.blankj.utilcode.util.SPUtils
import com.ebook.api.dto.RespDTO
import com.ebook.common.event.KeyCode
import com.ebook.common.event.RxBusTag
import com.ebook.me.mvvm.model.ModifyModel
import com.hwangjr.rxbus.RxBus
import com.xrn1997.common.event.SimpleObserver
import com.xrn1997.common.http.ExceptionHandler
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ModifyViewModel @Inject constructor(
    application: Application,
    model: ModifyModel
) : BaseViewModel<ModifyModel>(application, model) {
    /**
     * 修改昵称
     */
    fun modifyNickname(name: String) {
        mModel.modifyNickname(name).doOnSubscribe(this)
            .subscribe(object : SimpleObserver<RespDTO<Int>>() {
            override fun onNext(integerRespDTO: RespDTO<Int>) {
                if (integerRespDTO.code == ExceptionHandler.AppError.SUCCESS) {
                    mToastLiveEvent.setValue("修改成功")
                    SPUtils.getInstance().put(KeyCode.Login.SP_NICKNAME, name)
                    RxBus.get().post(RxBusTag.SET_PROFILE_PICTURE_AND_NICKNAME, Any())
                    postFinishActivityEvent()
                } else {
                    Log.e(TAG, "error: " + integerRespDTO.error)
                }
            }

            override fun onError(e: Throwable) {
            }
        })
    }

    /**
     * 修改头像
     *
     * @param uri 图片路径
     */
    fun modifyProfilePhoto(uri: Uri) {
        mModel.modifyProfilePhoto(uri).doOnSubscribe(this)
            .subscribe(object : SimpleObserver<RespDTO<String>>() {
            override fun onNext(stringRespDTO: RespDTO<String>) {
                if (stringRespDTO.code == ExceptionHandler.AppError.SUCCESS) {
                    mToastLiveEvent.setValue("头像修改成功")
                    val url = stringRespDTO.data
                    SPUtils.getInstance().put(KeyCode.Login.SP_IMAGE, url)
                    Log.e(TAG, "url: $url")
                    RxBus.get().post(RxBusTag.MODIFY_PROFILE_PICTURE, url)
                } else {
                    Log.e(TAG, "error: " + stringRespDTO.error)
                }
            }

            override fun onError(e: Throwable) {
            }
        })
    }

    companion object {
        private val TAG: String = ModifyViewModel::class.java.simpleName
    }
}
