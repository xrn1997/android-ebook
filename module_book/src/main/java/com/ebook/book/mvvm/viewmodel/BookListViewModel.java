package com.ebook.book.mvvm.viewmodel;

import android.app.Application;

import com.ebook.book.mvvm.model.BookListModel;
import com.ebook.common.mvvm.viewmodel.BaseRefreshViewModel;
import com.ebook.common.util.ToastUtil;
import com.ebook.db.entity.BookShelf;

import java.util.List;

import androidx.annotation.NonNull;
import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;


public class BookListViewModel extends BaseRefreshViewModel<BookShelf, BookListModel> {

    public BookListViewModel(@NonNull Application application, BookListModel model) {
        super(application, model);
    }

    @Override
    public void refreshData() {
        mModel.getBookShelfList().subscribe(new Observer<List<BookShelf>>() {
            @Override
            public void onSubscribe(Disposable d) {

            }

            @Override
            public void onNext(List<BookShelf> value) {
                if (value != null && value.size() > 0) {
                    mList.clear();
                    mList.addAll(value);
                } else {
                //    ToastUtil.showToast("没有数据");
                   // postShowNoDataViewEvent(true);
                }
                postStopRefreshEvent();
            }

            @Override
            public void onError(Throwable e) {
            }

            @Override
            public void onComplete() {

            }
        });
    }

    @Override
    public void loadMore() {

    }

    @Override
    public boolean enableLoadMore() {
        return false;
    }



}
