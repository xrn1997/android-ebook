package com.ebook.me.mvvm.model;

import android.app.Application;

import com.ebook.api.NewsDetailService;
import com.ebook.api.NewsTypeService;
import com.ebook.api.RetrofitManager;
import com.ebook.api.dto.RespDTO;
import com.ebook.api.http.RxAdapter;
import com.ebook.api.news.entity.NewsDetail;
import com.ebook.api.newstype.entity.NewsType;
import com.ebook.common.mvvm.model.BaseModel;
import com.ebook.common.util.DateUtil;

import java.util.Date;
import java.util.List;

import io.reactivex.Observable;


public class NewsDetailAddModel extends BaseModel {
    //负责与数据库进行交互，与VM进行交互
    private NewsTypeService mNewsTypeService;
    private NewsDetailService mNewsDetailService;

    public NewsDetailAddModel(Application application) {
        super(application);
        mNewsTypeService = RetrofitManager.getInstance().getNewsTypeService();
        mNewsDetailService = RetrofitManager.getInstance().getNewsDetailService();
    }

    public Observable<RespDTO<NewsDetail>> addNewsDetail(int type, String title, String content) {
        NewsDetail newsDetail = new NewsDetail();
        newsDetail.setTypeid(type);
        newsDetail.setTitle(title);
        newsDetail.setContent(content);
        newsDetail.setAddtime(DateUtil.formatDate(new Date(), DateUtil.FormatType.yyyyMMddHHmmss));
        return mNewsDetailService.addNewsDetail(RetrofitManager.getInstance().TOKEN, newsDetail)
                .compose(RxAdapter.schedulersTransformer())
                .compose(RxAdapter.exceptionTransformer());
    }

    public Observable<RespDTO<List<NewsType>>> getNewsType() {
        return mNewsTypeService.getListNewsType(RetrofitManager.getInstance().TOKEN)
                .compose(RxAdapter.schedulersTransformer())
                .compose(RxAdapter.exceptionTransformer());
    }
}
