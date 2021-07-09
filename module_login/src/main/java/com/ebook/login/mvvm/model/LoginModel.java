package com.ebook.login.mvvm.model;

import android.app.Application;

import com.ebook.api.service.UserService;
import com.ebook.api.RetrofitManager;
import com.ebook.api.dto.RespDTO;
import com.ebook.api.http.RxAdapter;
import com.ebook.api.entity.LoginDTO;
import com.ebook.api.entity.User;
import com.ebook.common.mvvm.model.BaseModel;

import io.reactivex.Observable;

public class LoginModel extends BaseModel {
    private UserService mUserService;

    public LoginModel(Application application) {
        super(application);
        mUserService = RetrofitManager.getInstance().getUserService();
    }

    @SuppressWarnings("unchecked")
    public Observable<RespDTO<LoginDTO>> login(String username, String password) {
        Observable<RespDTO<LoginDTO>> result = mUserService.login(new User(username, password));
        return result
                .compose(RxAdapter.schedulersTransformer())
                .compose(RxAdapter.exceptionTransformer());
    }
}
