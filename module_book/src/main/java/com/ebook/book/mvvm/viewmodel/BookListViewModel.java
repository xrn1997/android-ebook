package com.ebook.book.mvvm.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;

import com.ebook.book.mvvm.model.BookListModel;
import com.ebook.db.entity.BookShelf;
import com.xrn1997.common.mvvm.viewmodel.BaseRefreshViewModel;

import java.util.List;

import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;


public class BookListViewModel extends BaseRefreshViewModel<BookShelf, BookListModel> {

    public BookListViewModel(@NonNull Application application, BookListModel model) {
        super(application, model);
    }

    @Override
    public void refreshData() {
        mModel.getBookShelfList().subscribe(new Observer<>() {
            @Override
            public void onSubscribe(Disposable d) {

            }

            @Override
            public void onNext(List<BookShelf> value) {
                mList.clear();
                if (value != null && !value.isEmpty()) {
                    mList.addAll(value);
                }
                postStopRefreshEvent(true);
            }

            @Override
            public void onError(Throwable e) {
                postStopRefreshEvent(false);
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
