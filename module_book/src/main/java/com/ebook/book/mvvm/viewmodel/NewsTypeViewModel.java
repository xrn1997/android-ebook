package com.ebook.book.mvvm.viewmodel;

import android.app.Application;

import com.ebook.api.dto.RespDTO;
import com.ebook.api.newstype.entity.NewsType;
import com.ebook.book.mvvm.model.NewsTypeModel;
import com.ebook.common.event.SingleLiveEvent;
import com.ebook.common.mvvm.viewmodel.BaseViewModel;

import java.util.List;

import androidx.annotation.NonNull;
import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;

public class NewsTypeViewModel extends BaseViewModel<NewsTypeModel> {
    private SingleLiveEvent<List<NewsType>> mListSingleLiveEvent;
    public NewsTypeViewModel(@NonNull Application application, NewsTypeModel model) {
        super(application, model);
    }

    public void getListNewsType() {
        mModel.getListNewsType().subscribe(new Observer<RespDTO<List<NewsType>>>() {
            @Override
            public void onSubscribe(Disposable d) {

            }

            @Override
            public void onNext(RespDTO<List<NewsType>> listRespDTO) {
                getListSingleLiveEvent().postValue(listRespDTO.data);
            }

            @Override
            public void onError(Throwable e) {

            }

            @Override
            public void onComplete() {

            }
        });
    }
    public SingleLiveEvent<List<NewsType>> getListSingleLiveEvent() {
        return mListSingleLiveEvent = createLiveData(mListSingleLiveEvent);
    }
}
