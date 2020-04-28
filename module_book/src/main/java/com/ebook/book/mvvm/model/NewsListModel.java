package com.ebook.book.mvvm.model;

import android.app.Application;

import com.ebook.api.NewsDetailService;
import com.ebook.api.RetrofitManager;
import com.ebook.api.dto.RespDTO;
import com.ebook.api.http.RxAdapter;
import com.ebook.api.news.entity.NewsDetail;
import com.ebook.common.mvvm.model.BaseModel;

import java.util.List;

import io.reactivex.Observable;


public class NewsListModel extends BaseModel {
    private NewsDetailService mNewsDetailService;

    public NewsListModel(Application application) {
        super(application);
        mNewsDetailService = RetrofitManager.getInstance().getNewsDetailService();
    }

    public Observable<RespDTO<List<NewsDetail>>> getListNewsByType(int typeid) {
        return mNewsDetailService.getListNewsDetailByType(RetrofitManager.getInstance().TOKEN,typeid)
                .compose(RxAdapter.exceptionTransformer())
                .compose(RxAdapter.schedulersTransformer());
    }
}
