package com.ebook.find.mvvm.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.databinding.ObservableArrayList;

import com.ebook.basebook.cache.ACache;
import com.ebook.basebook.mvp.model.impl.WebBookModelImpl;
import com.ebook.basebook.observer.SimpleObserver;
import com.ebook.common.BaseApplication;
import com.ebook.common.mvvm.viewmodel.BaseRefreshViewModel;
import com.ebook.db.entity.Library;
import com.ebook.db.entity.LibraryKindBookList;
import com.ebook.find.entity.BookType;
import com.ebook.find.mvvm.model.LibraryModel;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class LibraryViewModel extends BaseRefreshViewModel<Library, LibraryModel> {
    public static final String TAG = LibraryViewModel.class.getSimpleName();
    public final static String LIBRARY_CACHE_KEY = "cache_library";
    private final ObservableArrayList<BookType> bookTypes = new ObservableArrayList<>();
    private final ObservableArrayList<LibraryKindBookList> libraryKindBookLists = new ObservableArrayList<>();
    private final ACache mCache = ACache.get(BaseApplication.getInstance());
    private Boolean isFirst = true;

    public LibraryViewModel(@NonNull Application application, LibraryModel model) {
        super(application, model);
    }

    @Override
    public void refreshData() {
        //   Log.d(TAG, "refreshData: start");
        if (isFirst) {
            isFirst = false;
            mModel.getLibraryData(mCache)
                    .subscribe(new SimpleObserver<>() {
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
        WebBookModelImpl.getInstance().getLibraryData(mCache)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<>() {
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
