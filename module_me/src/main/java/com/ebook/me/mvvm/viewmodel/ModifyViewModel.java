package com.ebook.me.mvvm.viewmodel;

import android.app.Application;

import android.util.Log;

import com.blankj.utilcode.util.SPUtils;
import com.ebook.api.dto.RespDTO;
import com.ebook.api.http.ExceptionHandler;
import com.ebook.common.event.KeyCode;
import com.ebook.common.event.RxBusTag;
import com.ebook.common.glide.GlideApp;
import com.ebook.common.mvvm.viewmodel.BaseViewModel;
import com.ebook.common.util.ToastUtil;
import com.ebook.me.mvvm.model.ModifyModel;
import com.hwangjr.rxbus.RxBus;

import androidx.annotation.NonNull;
import androidx.databinding.ObservableField;
import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;

public class ModifyViewModel extends BaseViewModel<ModifyModel> {
    private static String TAG = ModifyViewModel.class.getSimpleName();
    public String url;
    public ObservableField<String> username = new ObservableField<>();
    public ModifyViewModel(@NonNull Application application, ModifyModel model) {
        super(application, model);
    }

    public void modifyProfiePhoto(String path){

        mModel.modifyProfiePhoto(path).subscribe(new Observer<RespDTO<String>>() {
            @Override
            public void onSubscribe(Disposable d) {

            }
            @Override
            public void onNext(RespDTO<String> stringRespDTO) {
                if (stringRespDTO.code == ExceptionHandler.APP_ERROR.SUCC) {
                    ToastUtil.showToast("头像修改成功");
                    url=stringRespDTO.data;
                    SPUtils.getInstance().put(KeyCode.Login.SP_IMAGE,url);
                    Log.e(TAG, "url: "+url );
                    RxBus.get().post(RxBusTag.MODIFY_PROFIE_PICTURE,url);
                }else{
                    Log.e(TAG, "error: "+stringRespDTO.error);
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
