package com.ebook.me.mvvm.viewmodel;

import android.app.Application;
import androidx.databinding.ObservableField;
import androidx.annotation.NonNull;
import android.text.TextUtils;

import com.ebook.api.dto.RespDTO;
import com.ebook.api.http.ExceptionHandler;
import com.ebook.api.newstype.entity.NewsType;
import com.ebook.common.event.SingleLiveEvent;
import com.ebook.common.mvvm.viewmodel.BaseViewModel;
import com.ebook.common.util.DateUtil;
import com.ebook.common.util.ToastUtil;
import com.ebook.common.util.log.KLog;
import com.ebook.me.mvvm.model.NewsTypeAddModel;

import java.util.Date;

import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;

public class NewsTypeAddViewModel extends BaseViewModel<NewsTypeAddModel> {
    public static final String TAG = NewsTypeAddModel.class.getSimpleName();
    private SingleLiveEvent<Void> addNewsTypeSuccViewEvent;
    public ObservableField<String> typeName = new ObservableField<>();

    public NewsTypeAddViewModel(@NonNull Application application, NewsTypeAddModel model) {
        super(application, model);
    }

    public void addNewsType() {
        if (TextUtils.isEmpty(typeName.get())) {
            ToastUtil.showToast("请输入新闻类型");
            return;
        }
        NewsType newsType = new NewsType();
        newsType.setTypename(typeName.get());
        newsType.setAddtime(DateUtil.formatDate(new Date(), DateUtil.FormatType.yyyyMMddHHmmss));
        mModel.addNewsType(newsType).doOnSubscribe(this).subscribe(new Observer<RespDTO<NewsType>>() {
            @Override
            public void onSubscribe(Disposable d) {
                KLog.v("MYTAG","viewmodel postShowTransLoadingViewEvent start...");
                postShowTransLoadingViewEvent(true);
            }

            @Override
            public void onNext(RespDTO<NewsType> newsTypeRespDTO) {
                if (newsTypeRespDTO.code == ExceptionHandler.APP_ERROR.SUCC) {
                    ToastUtil.showToast("添加成功");
                    addNewsTypeSuccViewEvent.call();
                } else {
                    ToastUtil.showToast("添加失败");
                }

            }

            @Override
            public void onError(Throwable e) {
                postShowTransLoadingViewEvent(false);
            }

            @Override
            public void onComplete() {
                postShowTransLoadingViewEvent(false);
            }
        });
    }
    public SingleLiveEvent<Void> getAddNewsTypeSuccViewEvent() {
        return addNewsTypeSuccViewEvent = createLiveData(addNewsTypeSuccViewEvent);
    }

}
