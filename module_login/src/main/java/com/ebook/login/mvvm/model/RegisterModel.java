package com.ebook.login.mvvm.model;

import android.app.Application;

import com.ebook.api.CommonService;
import com.ebook.api.RetrofitManager;
import com.ebook.api.dto.RespDTO;
import com.ebook.api.http.RxAdapter;
import com.ebook.api.user.LoginDTO;
import com.ebook.api.user.entity.User;
import com.ebook.common.mvvm.model.BaseModel;

import io.reactivex.Observable;


public class RegisterModel extends BaseModel {

    private CommonService mCommonService;

    public RegisterModel(Application application) {
        super(application);
        mCommonService = RetrofitManager.getInstance().getCommonService();
    }
    @SuppressWarnings("unchecked")
    public Observable<RespDTO<LoginDTO>> register(String username, String password) {
        Observable<RespDTO<LoginDTO>> result=mCommonService.register(new User(username,password));
        return result
                .compose(RxAdapter.schedulersTransformer())
                .compose(RxAdapter.exceptionTransformer());
    }

}
