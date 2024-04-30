package com.ebook.me.mvvm.viewmodel;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.databinding.ObservableField;

import com.blankj.utilcode.util.SPUtils;
import com.ebook.api.dto.RespDTO;
import com.ebook.api.http.ExceptionHandler;
import com.ebook.common.event.KeyCode;
import com.ebook.common.event.RxBusTag;
import com.ebook.common.util.ToastUtil;
import com.ebook.me.mvvm.model.ModifyModel;
import com.hwangjr.rxbus.RxBus;
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel;

import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;

public class ModifyViewModel extends BaseViewModel<ModifyModel> {
    private static final String TAG = ModifyViewModel.class.getSimpleName();

    public ObservableField<String> nickname = new ObservableField<>();

    public ModifyViewModel(@NonNull Application application, ModifyModel model) {
        super(application, model);
    }

    /**
     * 修改昵称
     */
    public void modifyNickname() {

        mModel.modifyNickname(nickname.get()).subscribe(new Observer<>() {
            @Override
            public void onSubscribe(Disposable d) {

            }

            @Override
            public void onNext(RespDTO<Integer> integerRespDTO) {
                if (integerRespDTO.code == ExceptionHandler.APP_ERROR.SUCC) {
                    ToastUtil.showToast("修改成功");
                    SPUtils.getInstance().put(KeyCode.Login.SP_NICKNAME, nickname.get());
                    RxBus.get().post(RxBusTag.SET_PROFILE_PICTURE_AND_NICKNAME, new Object());
                    postFinishActivityEvent();
                } else {
                    Log.e(TAG, "error: " + integerRespDTO.error);
                }
            }

            @Override
            public void onError(Throwable e) {

            }

            @Override
            public void onComplete() {

            }
        });
    }

    /**
     * 修改头像
     *
     * @param path 图片路径
     */
    public void modifyProfilePhoto(String path) {

        mModel.modifyProfiePhoto(path).subscribe(new Observer<>() {
            @Override
            public void onSubscribe(Disposable d) {

            }

            @Override
            public void onNext(RespDTO<String> stringRespDTO) {
                if (stringRespDTO.code == ExceptionHandler.APP_ERROR.SUCC) {
                    ToastUtil.showToast("头像修改成功");
                    String url = stringRespDTO.data;
                    SPUtils.getInstance().put(KeyCode.Login.SP_IMAGE, url);
                    Log.e(TAG, "url: " + url);
                    RxBus.get().post(RxBusTag.MODIFY_PROFILE_PICTURE, url);
                } else {
                    Log.e(TAG, "error: " + stringRespDTO.error);
                }
            }

            @Override
            public void onError(Throwable e) {

            }

            @Override
            public void onComplete() {

            }
        });

    }

}
