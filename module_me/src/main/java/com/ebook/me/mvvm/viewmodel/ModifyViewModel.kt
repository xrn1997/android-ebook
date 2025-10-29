package com.ebook.me.mvvm.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.ebook.api.utils.CoroutineAdapter
import com.ebook.common.event.KeyCode
import com.ebook.common.event.RxBusTag
import com.ebook.common.util.SPUtil
import com.ebook.me.mvvm.model.ModifyModel
import com.hwangjr.rxbus.RxBus
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
            val result = mModel.modifyNickname(name)
            result.onSuccess {
                postToastEvent("修改成功")
                SPUtil.put(KeyCode.Login.SP_NICKNAME, name)
                RxBus.get().post(RxBusTag.SET_PROFILE_PICTURE_AND_NICKNAME, Any())
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

    /**
     * 修改头像
     *
     * @param uri 图片路径
     */
    fun modifyProfilePhoto(uri: Uri) {
        viewModelScope.launch {
            val result = mModel.modifyProfilePhoto(uri)
            result.onSuccess { resp ->
                postToastEvent("头像修改成功")
                    val url = resp.data
                SPUtil.put(KeyCode.Login.SP_IMAGE, url)
                    Log.e(TAG, "url: $url")
                    RxBus.get().post(RxBusTag.MODIFY_PROFILE_PICTURE, url)
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
