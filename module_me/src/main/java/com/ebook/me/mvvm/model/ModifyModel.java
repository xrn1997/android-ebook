package com.ebook.me.mvvm.model;

import android.app.Application;

import com.blankj.utilcode.util.SPUtils;
import com.ebook.api.service.UserService;
import com.ebook.api.RetrofitManager;
import com.ebook.api.dto.RespDTO;
import com.ebook.api.http.RxAdapter;
import com.ebook.common.event.KeyCode;
import com.ebook.common.mvvm.model.BaseModel;

import java.io.File;

import io.reactivex.Observable;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;

public class ModifyModel extends BaseModel {
    private UserService userService;

    public ModifyModel(Application application) {
        super(application);
        userService = RetrofitManager.getInstance().getUserService();
    }

    /**
     * 修改昵称
     */
    @SuppressWarnings("unchecked")
    public Observable<RespDTO<Integer>> modifyNickname(String nickname) {
        String username = SPUtils.getInstance().getString(KeyCode.Login.SP_USERNAME);
        return userService.modifyNickname(RetrofitManager.getInstance().TOKEN, username, nickname)
                .compose(RxAdapter.schedulersTransformer())
                .compose(RxAdapter.exceptionTransformer());
    }

    /**
     * 修改头像
     *
     * @param path 头像路径
     * @return 返回服务器头像名称
     */
    @SuppressWarnings("unchecked")
    public Observable<RespDTO<String>> modifyProfiePhoto(String path) {
        String username = SPUtils.getInstance().getString(KeyCode.Login.SP_USERNAME);
        File file = new File(path);
        MultipartBody.Part body = MultipartBody.Part.createFormData("file", file.getName(), RequestBody.create(MediaType.parse("application/octet-stream"), file));
        return userService.modifyProfiePhoto(RetrofitManager.getInstance().TOKEN, username, body)
                .compose(RxAdapter.schedulersTransformer())
                .compose(RxAdapter.exceptionTransformer());
    }

}
