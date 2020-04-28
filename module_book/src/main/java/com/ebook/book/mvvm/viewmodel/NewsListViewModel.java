package com.ebook.book.mvvm.viewmodel;

import android.app.Application;


import com.ebook.api.dto.RespDTO;
import com.ebook.api.news.entity.NewsDetail;
import com.ebook.book.mvvm.model.NewsListModel;
import com.ebook.common.mvvm.viewmodel.BaseRefreshViewModel;
import com.ebook.common.util.NetUtil;
import com.ebook.common.util.ToastUtil;

import java.util.List;

import androidx.annotation.NonNull;
import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;


public class NewsListViewModel extends BaseRefreshViewModel<NewsDetail, NewsListModel> {
    private int newsType = 0;

    public NewsListViewModel(@NonNull Application application, NewsListModel model) {
        super(application, model);
    }

    @Override
    public void refreshData() {
        postShowNoDataViewEvent(false);
        if (!NetUtil.checkNetToast()) {
            postShowNetWorkErrViewEvent(true);
            return;
        }
        mModel.getListNewsByType(newsType).subscribe(new Observer<RespDTO<List<NewsDetail>>>() {
            @Override
            public void onSubscribe(Disposable d) {
                postShowInitLoadViewEvent(true);
            }

            @Override
            public void onNext(RespDTO<List<NewsDetail>> listRespDTO) {
                List<NewsDetail> datailList = listRespDTO.data;
                if (datailList != null && datailList.size() > 0) {
                    mList.clear();
                    mList.addAll(datailList);
                } else {
                    ToastUtil.showToast("没有数据");
                    postShowNoDataViewEvent(true);
                }
                postStopRefreshEvent();
            }

            @Override
            public void onError(Throwable e) {
                postShowInitLoadViewEvent(false);
            }

            @Override
            public void onComplete() {
                postShowInitLoadViewEvent(false);
            }
        });
    }

    @Override
    public void loadMore() {
        mModel.getListNewsByType(newsType).subscribe(new Observer<RespDTO<List<NewsDetail>>>() {
            @Override
            public void onSubscribe(Disposable d) {

            }

            @Override
            public void onNext(RespDTO<List<NewsDetail>> listRespDTO) {
                List<NewsDetail> datailList = listRespDTO.data;
                if (datailList != null && datailList.size() > 0) {
                    mList.addAll(datailList);
                }
            }

            @Override
            public void onError(Throwable e) {
                postStopLoadMoreEvent();
            }

            @Override
            public void onComplete() {
                postStopLoadMoreEvent();
            }
        });
    }


    public void setNewsType(int newsType) {
        this.newsType = newsType;
    }
}
