package com.ebook.me.mvvm.model;

import android.app.Application;

import com.ebook.api.NewsTypeService;
import com.ebook.api.RetrofitManager;
import com.ebook.api.dto.RespDTO;
import com.ebook.api.http.RxAdapter;
import com.ebook.api.newstype.entity.NewsType;
import com.ebook.common.mvvm.model.BaseModel;

import io.reactivex.Observable;


public class NewsTypeAddModel extends BaseModel{
    private NewsTypeService mNewsTypeService;
    public NewsTypeAddModel(Application application) {
        super(application);
        mNewsTypeService = RetrofitManager.getInstance().getNewsTypeService();
    }
    public Observable<RespDTO<NewsType>> addNewsType(NewsType type) {
        return mNewsTypeService.addNewsType(RetrofitManager.getInstance().TOKEN,type)
                .compose(RxAdapter.schedulersTransformer())
                .compose(RxAdapter.exceptionTransformer());
    }

}
