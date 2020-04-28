package com.ebook.book.mvvm.viewmodel;

import android.app.Application;


import com.ebook.api.dto.RespDTO;
import com.ebook.api.news.entity.NewsDetail;
import com.ebook.book.mvvm.model.NewsDetailModel;
import com.ebook.common.mvvm.viewmodel.BaseViewModel;
import com.ebook.common.util.NetUtil;


import androidx.annotation.NonNull;
import androidx.databinding.ObservableField;
import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;


public class NewsDetailViewModel extends BaseViewModel<NewsDetailModel> {
    public ObservableField<NewsDetail> mNewsDetails = new ObservableField<>();
    public NewsDetailViewModel(@NonNull Application application, NewsDetailModel model) {
        super(application, model);
    }

    public void getNewsDetailById(final int id) {
        if (!NetUtil.checkNetToast()) {
            postShowNetWorkErrViewEvent(true);
            return;
        }
        mModel.getNewsDetailById(id).subscribe(new Observer<RespDTO<NewsDetail>>() {
            @Override
            public void onSubscribe(Disposable d) {
                postShowInitLoadViewEvent(true);
            }

            @Override
            public void onNext(RespDTO<NewsDetail> newsDetailRespDTO) {
                NewsDetail newsDetail = newsDetailRespDTO.data;
                if (newsDetail != null) {
                    //todo getNewsDetailSingleLiveEvent().postValue(newsDetail);
                    mNewsDetails.set(newsDetail);
                } else {
                    postShowNoDataViewEvent(true);
                }
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
//    public SingleLiveEvent<NewsDetail> getNewsDetailSingleLiveEvent() {
//        return mNewsDetailSingleLiveEvent = createLiveData(mNewsDetailSingleLiveEvent);
//    }

}
