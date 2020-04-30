package com.ebook.login.mvvm.model;

import android.app.Application;

import com.ebook.api.CommonService;
import com.ebook.api.RetrofitManager;
import com.ebook.api.dto.RespDTO;
import com.ebook.api.http.RxAdapter;
import com.ebook.api.user.entity.User;
import com.ebook.common.mvvm.model.BaseModel;

import io.reactivex.Observable;

public class ModifyPwdModel extends BaseModel {
    private CommonService mCommonService;

    public ModifyPwdModel(Application application) {
        super(application);
        mCommonService = RetrofitManager.getInstance().getCommonService();
    }
    @SuppressWarnings("unchecked")
    public Observable<RespDTO<Integer>> modifyPwd(String username, String password) {
        Observable<RespDTO<Integer>> result = mCommonService.modifyPwd(new User(username, password));
        return result
                .compose(RxAdapter.schedulersTransformer())
                .compose(RxAdapter.exceptionTransformer());
    }
}
