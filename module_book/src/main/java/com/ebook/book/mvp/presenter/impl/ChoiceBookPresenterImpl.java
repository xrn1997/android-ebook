
package com.ebook.book.mvp.presenter.impl;

import android.content.Intent;

import com.ebook.book.mvp.model.OnGetChapterListListener;
import com.ebook.common.event.RxBusTag;
import com.ebook.common.observer.SimpleObserver;
import com.ebook.db.GreenDaoManager;
import com.hwangjr.rxbus.RxBus;
import com.hwangjr.rxbus.annotation.Subscribe;
import com.hwangjr.rxbus.annotation.Tag;
import com.hwangjr.rxbus.thread.EventThread;
import com.ebook.common.mvp.base.IView;
import com.ebook.common.mvp.base.activity.BaseActivity;
import com.ebook.common.mvp.base.impl.BasePresenterImpl;

import com.ebook.db.entity.BookShelf;
import com.ebook.db.entity.SearchBook;

import com.ebook.book.mvp.model.impl.WebBookModelImpl;
import com.ebook.book.mvp.presenter.IChoiceBookPresenter;
import com.ebook.book.mvp.utils.NetworkUtil;
import com.ebook.book.mvp.view.IChoiceBookView;
import com.trello.rxlifecycle3.android.ActivityEvent;


import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class ChoiceBookPresenterImpl extends BasePresenterImpl<IChoiceBookView> implements IChoiceBookPresenter {
    private String url = "";
    private String title;

    private int page = 1;
    private long startThisSearchTime;
    private List<BookShelf> bookShelfs = new ArrayList<>();   //用来比对搜索的书籍是否已经添加进书架

    public ChoiceBookPresenterImpl(final Intent intent) {
        url = intent.getStringExtra("url");
        title = intent.getStringExtra("title");
        Observable.create(new ObservableOnSubscribe<List<BookShelf>>() {
            @Override
            public void subscribe(ObservableEmitter<List<BookShelf>> e) throws Exception {
                List<BookShelf> temp = GreenDaoManager.getInstance().getmDaoSession().getBookShelfDao().queryBuilder().list();
                if (temp == null)
                    temp = new ArrayList<BookShelf>();
                e.onNext(temp);
            }
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<List<BookShelf>>() {
                    @Override
                    public void onNext(List<BookShelf> value) {
                        bookShelfs.addAll(value);
                        initPage();
                        toSearchBooks(null);
                        mView.startRefreshAnim();
                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                    }
                });
    }

    @Override
    public int getPage() {
        return page;
    }

    @Override
    public void initPage() {
        this.page = 1;
        this.startThisSearchTime = System.currentTimeMillis();
    }

    @Override
    public void toSearchBooks(String key) {
        final long tempTime = startThisSearchTime;
        searchBook(tempTime);
    }

    private void searchBook(final long searchTime) {
        WebBookModelImpl.getInstance().getKindBook(url, page)
                .subscribeOn(Schedulers.io())
                .compose(((BaseActivity)mView.getContext()).<List<SearchBook>>bindUntilEvent(ActivityEvent.DESTROY))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<List<SearchBook>>() {
                    @Override
                    public void onNext(List<SearchBook> value) {
                        if (searchTime == startThisSearchTime) {
                            for (SearchBook temp : value) {
                                for (BookShelf bookShelf : bookShelfs) {
                                    if (temp.getNoteUrl().equals(bookShelf.getNoteUrl())) {
                                        temp.setAdd(true);
                                        break;
                                    }
                                }
                            }
                            if (page == 1) {
                                mView.refreshSearchBook(value);
                                mView.refreshFinish(value.size()<=0?true:false);
                            } else {
                                mView.loadMoreSearchBook(value);
                                mView.loadMoreFinish(value.size()<=0?true:false);
                            }
                            page++;
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                        mView.searchBookError();
                    }
                });
    }
    ////////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void addBookToShelf(final SearchBook searchBook) {
        final BookShelf bookShelfResult = new BookShelf();
        bookShelfResult.setNoteUrl(searchBook.getNoteUrl());
        bookShelfResult.setFinalDate(0);
        bookShelfResult.setDurChapter(0);
        bookShelfResult.setDurChapterPage(0);
        bookShelfResult.setTag(searchBook.getTag());
        WebBookModelImpl.getInstance().getBookInfo(bookShelfResult)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .compose(((BaseActivity)mView.getContext()).<BookShelf>bindUntilEvent(ActivityEvent.DESTROY))
                .subscribe(new SimpleObserver<BookShelf>() {
                    @Override
                    public void onNext(BookShelf value) {
                        WebBookModelImpl.getInstance().getChapterList(value, new OnGetChapterListListener() {
                            @Override
                            public void success(BookShelf bookShelf) {
                                saveBookToShelf(bookShelf);
                            }

                            @Override
                            public void error() {
                                mView.addBookShelfFailed(NetworkUtil.ERROR_CODE_OUTTIME);
                            }
                        });
                    }

                    @Override
                    public void onError(Throwable e) {
                        mView.addBookShelfFailed(NetworkUtil.ERROR_CODE_OUTTIME);
                    }
                });
    }

    @Override
    public String getTitle() {
        return title;
    }

    private void saveBookToShelf(final BookShelf bookShelf){
        Observable.create(new ObservableOnSubscribe<BookShelf>() {
            @Override
            public void subscribe(ObservableEmitter<BookShelf> e) throws Exception {
                GreenDaoManager.getInstance().getmDaoSession().getChapterListDao().insertOrReplaceInTx(bookShelf.getBookInfo().getChapterlist());
                GreenDaoManager.getInstance().getmDaoSession().getBookInfoDao().insertOrReplace(bookShelf.getBookInfo());
                //网络数据获取成功  存入BookShelf表数据库
                GreenDaoManager.getInstance().getmDaoSession().getBookShelfDao().insertOrReplace(bookShelf);
                e.onNext(bookShelf);
                e.onComplete();
            }
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .compose(((BaseActivity)mView.getContext()).<BookShelf>bindUntilEvent(ActivityEvent.DESTROY))
                .subscribe(new SimpleObserver<BookShelf>() {
                    @Override
                    public void onNext(BookShelf value) {
                        //成功   //发送RxBus
                        RxBus.get().post(RxBusTag.HAD_ADD_BOOK, value);
                    }

                    @Override
                    public void onError(Throwable e) {
                        mView.addBookShelfFailed(NetworkUtil.ERROR_CODE_OUTTIME);
                    }
                });
    }
    ////////////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void attachView(@NonNull IView iView) {
        super.attachView(iView);
        RxBus.get().register(this);
    }

    @Override
    public void detachView() {
        RxBus.get().unregister(this);
    }

    @Subscribe(
            thread = EventThread.MAIN_THREAD,
            tags = {
                    @Tag(RxBusTag.HAD_ADD_BOOK)
            }
    )
    public void hadAddBook(BookShelf bookShelf) {
        bookShelfs.add(bookShelf);
        List<SearchBook> datas = mView.getSearchBookAdapter().getSearchBooks();
        for (int i = 0; i < datas.size(); i++) {
            if (datas.get(i).getNoteUrl().equals(bookShelf.getNoteUrl())) {
                datas.get(i).setAdd(true);
                mView.updateSearchItem(i);
                break;
            }
        }
    }

    @Subscribe(
            thread = EventThread.MAIN_THREAD,
            tags = {
                    @Tag(RxBusTag.HAD_REMOVE_BOOK)
            }
    )
    public void hadRemoveBook(BookShelf bookShelf) {
        if(bookShelfs!=null){
            for(int i=0;i<bookShelfs.size();i++){
                if(bookShelfs.get(i).getNoteUrl().equals(bookShelf.getNoteUrl())){
                    bookShelfs.remove(i);
                    break;
                }
            }
        }
        List<SearchBook> datas = mView.getSearchBookAdapter().getSearchBooks();
        for (int i = 0; i < datas.size(); i++) {
            if (datas.get(i).getNoteUrl().equals(bookShelf.getNoteUrl())) {
                datas.get(i).setAdd(false);
                mView.updateSearchItem(i);
                break;
            }
        }
    }
}