package com.ebook.me.mvvm.model;

import android.app.Application;

import com.ebook.api.CommentService;
import com.ebook.api.CommonService;
import com.ebook.api.RetrofitManager;
import com.ebook.api.comment.entity.Comment;
import com.ebook.api.dto.RespDTO;
import com.ebook.api.http.RxAdapter;
import com.ebook.common.mvvm.model.BaseModel;

import io.reactivex.Observable;

public class ModifyModel extends BaseModel {
    private CommonService commonService;

    public ModifyModel(Application application) {
        super(application);
        commonService = RetrofitManager.getInstance().getCommonService();
    }

    /**
     *修改昵称
     */
    @SuppressWarnings("unchecked")
    public Observable<RespDTO<Integer>> modifyNickname(String username, String nickname) {
        return commonService.modifyNickname(RetrofitManager.getInstance().TOKEN, username, nickname)
                .compose(RxAdapter.schedulersTransformer())
                .compose(RxAdapter.exceptionTransformer());
    }

}
