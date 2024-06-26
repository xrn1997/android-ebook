package com.ebook.basebook.mvp.presenter.impl;

import android.os.Environment;
import android.util.Log;

import com.ebook.basebook.base.impl.BasePresenterImpl;
import com.ebook.basebook.mvp.model.impl.ImportBookModelImpl;
import com.ebook.basebook.mvp.presenter.IImportBookPresenter;
import com.ebook.basebook.mvp.view.IImportBookView;
import com.ebook.basebook.observer.SimpleObserver;
import com.ebook.common.event.RxBusTag;
import com.ebook.db.entity.LocBookShelf;
import com.hwangjr.rxbus.RxBus;

import org.jetbrains.annotations.NotNull;

import java.io.File;
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
    public static final String TAG = "ImportBookPresenterImpl";
    //停止扫描
    boolean isCancel = false;

    public ImportBookPresenterImpl() {

    }

    @Override
    public void searchLocationBook() {
        isCancel = false;
        Observable.create((ObservableOnSubscribe<File>) e -> {
                    if (Environment.getExternalStorageState().equals(
                            Environment.MEDIA_MOUNTED)) {
                        searchBook(e, new File(Environment.getExternalStorageDirectory().getAbsolutePath()));
                    }
                    e.onComplete();
                }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<>() {
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
                        Log.e(TAG, "onError: ", e);
                    }
                });
    }

    private void searchBook(ObservableEmitter<File> e, File parentFile) {
        if (isCancel) {
            e.onComplete();
            return;
        }
        if (Objects.requireNonNull(parentFile.listFiles()).length > 0) {
            File[] childFiles = parentFile.listFiles();
            if (childFiles != null) {
                for (File childFile : childFiles) {
                    if (childFile.isFile() && childFile.getName().substring(childFile.getName().lastIndexOf(".") + 1).equalsIgnoreCase("txt")
                            && childFile.length() > 100 * 1024) {   //100kb
                        e.onNext(childFile);
                        continue;
                    }
                    if (childFile.getAbsolutePath().equals("/storage/emulated/0/Android/data")
                            || childFile.getAbsolutePath().equals("/storage/emulated/0/Android/obb")) {
                        //这个两个路径没有权限，不扫
                        continue;
                    }
                    if (childFile.isDirectory() && Objects.requireNonNull(childFile.listFiles()).length > 0) {
                        //进入文件夹中继续扫
                        searchBook(e, childFile);
                    }
                }
            }

        }
    }

    @Override
    public void importBooks(List<File> books) {
        Observable.fromIterable(books).flatMap((Function<File, ObservableSource<LocBookShelf>>) file -> ImportBookModelImpl.getInstance().importBook(file))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<>() {
                    @Override
                    public void onNext(@NotNull LocBookShelf value) {
                        Log.e(TAG, "onNext: " + value.getNew());
                        if (value.getNew()) {
                            RxBus.get().post(RxBusTag.HAD_ADD_BOOK, value.getBookShelf());
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        Log.e(TAG, "onError: ", e);
                        mView.addError();
                    }

                    @Override
                    public void onComplete() {
                        mView.addSuccess();
                    }
                });
    }

    @Override
    public void ScanCancel() {
        isCancel = true;
    }

    @Override
    public void detachView() {

    }
}
