package com.ebook.book.mvvm.model;

import android.app.Application;

import com.ebook.api.NewsDetailService;
import com.ebook.api.RetrofitManager;
import com.ebook.api.dto.RespDTO;
import com.ebook.api.http.RxAdapter;
import com.ebook.api.news.entity.NewsDetail;
import com.ebook.common.mvvm.model.BaseModel;

import io.reactivex.Observable;


public class NewsDetailModel extends BaseModel {
    private NewsDetailService mNewsDetailService;

    public NewsDetailModel(Application application) {
        super(application);
        mNewsDetailService = RetrofitManager.getInstance().getNewsDetailService();
    }

    public Observable<RespDTO<NewsDetail>> getNewsDetailById(int id) {
        return mNewsDetailService.getNewsDetailById(RetrofitManager.getInstance().TOKEN,id)
                .compose(RxAdapter.schedulersTransformer())
                .compose(RxAdapter.exceptionTransformer());
    }
}