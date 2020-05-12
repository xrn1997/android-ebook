package com.ebook.find.mvvm.viewmodel;

import android.app.Application;
import android.os.Handler;
import android.util.Log;
import android.view.View;

import com.ebook.basebook.cache.ACache;
import com.ebook.basebook.mvp.model.impl.GxwztvBookModelImpl;
import com.ebook.basebook.observer.SimpleObserver;
import com.ebook.common.BaseApplication;
import com.ebook.common.mvvm.viewmodel.BaseRefreshViewModel;
import com.ebook.db.entity.Library;
import com.ebook.db.entity.LibraryKindBookList;
import com.ebook.find.entity.BookType;
import com.ebook.find.mvvm.model.LibraryModel;

import androidx.annotation.NonNull;
import androidx.databinding.ObservableArrayList;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class LibraryViewModel extends BaseRefreshViewModel<Library, LibraryModel> {
    private ObservableArrayList<BookType> bookTypes = new ObservableArrayList<>();
    private ObservableArrayList<LibraryKindBookList> libraryKindBookLists = new ObservableArrayList<>();
    private Boolean isFirst = true;
    private ACache mCache = ACache.get(BaseApplication.getInstance());
    public static final String TAG = LibraryViewModel.class.getSimpleName();
    public final static String LIBRARY_CACHE_KEY = "cache_library";

    public LibraryViewModel(@NonNull Application application, LibraryModel model) {
        super(application, model);
    }

    @Override
    public void refreshData() {
        //   Log.d(TAG, "refreshData: start");
        if (isFirst) {
            isFirst = false;
            mModel.getLibraryData(mCache)
                    .subscribe(new SimpleObserver<Library>() {
                        @Override
                        public void onNext(Library value) {
                            libraryKindBookLists.clear();
                            libraryKindBookLists.addAll(value.getKindBooks());
                            //       Log.d(TAG, "refreshdata onNext: " + value.toString());
                            getLibraryNewData();
                        }

                        @Override
                        public void onError(Throwable e) {
                            getLibraryNewData();
                        }
                    });
        } else {
            getLibraryNewData();
        }
    }

    @Override
    public boolean enableLoadMore() {
        return false;
    }

    @Override
    public void loadMore() {
        postStopLoadMoreEvent();
    }

    private void getLibraryNewData() {
        //   Log.d(TAG, "getLibraryNewData: start");
        GxwztvBookModelImpl.getInstance().getLibraryData(mCache)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<Library>() {
                    @Override
                    public void onNext(final Library value) {
                        //     Log.d(TAG, "refreshdata onNext: " + value.getKindBooks().get(0).getKindName());
                        libraryKindBookLists.clear();
                        libraryKindBookLists.addAll(value.getKindBooks());
                        postStopRefreshEvent();
                        //   Log.d(TAG, "refreshdata onNext: finish");
                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                    }
                });
    }

    public ObservableArrayList<LibraryKindBookList> getLibraryKindBookLists() {
        return libraryKindBookLists;
    }

    public ObservableArrayList<BookType> getBookTypeList() {
        bookTypes.addAll(mModel.getBookTypeList());
        return bookTypes;
    }

}
