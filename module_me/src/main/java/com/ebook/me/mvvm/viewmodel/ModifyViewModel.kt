package com.ebook.me.mvvm.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.blankj.utilcode.util.SPUtils
import com.ebook.common.event.KeyCode
import com.ebook.common.event.RxBusTag
import com.ebook.me.mvvm.model.ModifyModel
import com.hwangjr.rxbus.RxBus
import com.xrn1997.common.http.ExceptionHandler
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
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
        viewModelScope.launch {
            try {
                val resp = mModel.modifyNickname(name)
                if (resp.code == ExceptionHandler.AppError.SUCCESS) {
                    mToastLiveEvent.setValue("修改成功")
                    SPUtils.getInstance().put(KeyCode.Login.SP_NICKNAME, name)
                    RxBus.get().post(RxBusTag.SET_PROFILE_PICTURE_AND_NICKNAME, Any())
                    postFinishActivityEvent()
                } else {
                    Log.e(TAG, "error: ${resp.error}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "modifyNickname error", e)
            }
        }
    }

    /**
     * 修改头像
     *
     * @param uri 图片路径
     */
    fun modifyProfilePhoto(uri: Uri) {
        viewModelScope.launch {
            try {
                val resp = mModel.modifyProfilePhoto(uri)
                if (resp.code == ExceptionHandler.AppError.SUCCESS) {
                    mToastLiveEvent.setValue("头像修改成功")
                    val url = resp.data
                    SPUtils.getInstance().put(KeyCode.Login.SP_IMAGE, url)
                    Log.e(TAG, "url: $url")
                    RxBus.get().post(RxBusTag.MODIFY_PROFILE_PICTURE, url)
                } else {
                    Log.e(TAG, "error: ${resp.error}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "modifyProfilePhoto error", e)
            }
        }
    }

    companion object {
        private val TAG: String = ModifyViewModel::class.java.simpleName
    }
}
