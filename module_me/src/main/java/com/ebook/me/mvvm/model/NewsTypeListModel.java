package com.ebook.me.mvvm.model;

import android.app.Application;
import com.ebook.api.NewsTypeService;
import com.ebook.api.RetrofitManager;
import com.ebook.api.dto.RespDTO;
import com.ebook.api.http.RxAdapter;
import com.ebook.api.newstype.entity.NewsType;
import com.ebook.common.mvvm.model.BaseModel;

import java.util.List;

import javax.inject.Inject;

import io.reactivex.Observable;


public class NewsTypeListModel extends BaseModel {
    private NewsTypeService mNewsTypeService;
    @Inject
    public NewsTypeListModel(Application application) {
        super(application);
        mNewsTypeService = RetrofitManager.getInstance().getNewsTypeService();
    }


    public Observable<RespDTO<List<NewsType>>> getListNewsType() {
        return mNewsTypeService.getListNewsType(RetrofitManager.getInstance().TOKEN)
                .compose(RxAdapter.schedulersTransformer())
                .compose(RxAdapter.exceptionTransformer());
    }

    public Observable<RespDTO> deleteNewsTypeById(int id) {
        return mNewsTypeService.deleteNewsTypeById(RetrofitManager.getInstance().TOKEN,id)
                .compose(RxAdapter.schedulersTransformer())
                .compose(RxAdapter.exceptionTransformer());
    }
}
