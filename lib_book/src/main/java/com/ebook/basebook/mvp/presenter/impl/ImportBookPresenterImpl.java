
package com.ebook.basebook.mvp.presenter.impl;

import android.os.Environment;
import android.util.Log;

import com.ebook.common.event.RxBusTag;
import com.ebook.basebook.mvp.model.impl.ImportBookModelImpl;
import com.ebook.basebook.mvp.presenter.IImportBookPresenter;
import com.ebook.basebook.mvp.view.IImportBookView;
import com.ebook.basebook.observer.SimpleObserver;
import com.hwangjr.rxbus.RxBus;
import com.ebook.basebook.base.impl.BasePresenterImpl;
import com.ebook.db.entity.LocBookShelf;


import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.ObservableSource;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;

public class ImportBookPresenterImpl extends BasePresenterImpl<IImportBookView> implements IImportBookPresenter {

    public ImportBookPresenterImpl() {

    }

    @Override
    public void searchLocationBook() {
        Observable.create(new ObservableOnSubscribe<File>() {
            @Override
            public void subscribe(@NotNull ObservableEmitter<File> e) throws Exception {
                if (Environment.getExternalStorageState().equals(
                        Environment.MEDIA_MOUNTED)) {
                    searchBook(e, new File(Environment.getExternalStorageDirectory().getAbsolutePath()));
                }
                e.onComplete();
            }
        }).observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.io())
                .subscribe(new SimpleObserver<File>() {
                    @Override
                    public void onNext(@NotNull File value) {
                        mView.addNewBook(value);
                    }

                    @Override
                    public void onComplete() {
                        mView.searchFinish();
                    }

                    @Override
                    public void onError(@NotNull Throwable e) {
                        e.printStackTrace();
                    }
                });
    }

    private void searchBook(ObservableEmitter<File> e, File parentFile) {
        Log.e("TAGE", "searchBook: "+ Arrays.toString(parentFile.listFiles()));
        if (null != parentFile && parentFile.listFiles().length > 0) {
            File[] childFiles = parentFile.listFiles();
            for (File childFile : childFiles) {
                if (childFile.isFile() && childFile.getName().substring(childFile.getName().lastIndexOf(".") + 1).equalsIgnoreCase("txt") && childFile.length() > 100 * 1024) {   //100kb
                    e.onNext(childFile);
                    continue;
                }
                if(childFile.getAbsolutePath().equals("/storage/emulated/0/Android")){
                    continue;
                }
                if (childFile.isDirectory() && childFile.listFiles().length > 0) {
                    searchBook(e, childFile);
                }
            }
        }
    }

    @Override
    public void importBooks(List<File> books) {
        Observable.fromIterable(books).flatMap(new Function<File, ObservableSource<LocBookShelf>>() {
            @Override
            public ObservableSource<LocBookShelf> apply(@NotNull File file) throws Exception {
                return ImportBookModelImpl.getInstance().importBook(file);
            }
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<LocBookShelf>() {
                    @Override
                    public void onNext(@NotNull LocBookShelf value) {
                        if (value.getNew()) {
                            RxBus.get().post(RxBusTag.HAD_ADD_BOOK, value.getBookShelf());
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                        mView.addError();
                    }

                    @Override
                    public void onComplete() {
                        mView.addSuccess();
                    }
                });
    }

    @Override
    public void detachView() {

    }
}
