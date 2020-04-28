
package com.ebook.book.mvp.presenter.impl;

import com.ebook.book.mvp.model.OnGetChapterListListener;
import com.ebook.common.event.RxBusTag;
import com.ebook.common.observer.SimpleObserver;
import com.ebook.db.GreenDaoManager;
import com.hwangjr.rxbus.RxBus;
import com.hwangjr.rxbus.annotation.Subscribe;
import com.hwangjr.rxbus.annotation.Tag;
import com.hwangjr.rxbus.thread.EventThread;
import com.ebook.common.mvp.base.IView;
import com.ebook.common.mvp.base.impl.BasePresenterImpl;

import com.ebook.db.entity.BookInfo;
import com.ebook.db.entity.BookInfoDao;
import com.ebook.db.entity.BookShelf;
import com.ebook.db.entity.BookShelfDao;
import com.ebook.db.entity.ChapterListDao;

import com.ebook.book.mvp.model.impl.WebBookModelImpl;
import com.ebook.book.mvp.presenter.IMainPresenter;
import com.ebook.book.mvp.utils.NetworkUtil;
import com.ebook.book.mvp.view.IMainView;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class MainPresenterImpl extends BasePresenterImpl<IMainView> implements IMainPresenter {

    public void queryBookShelf(final Boolean needRefresh) {
        if (needRefresh)
            mView.activityRefreshView();
        Observable.create(new ObservableOnSubscribe<List<BookShelf>>() {
            @Override
            public void subscribe(ObservableEmitter<List<BookShelf>> e) throws Exception {
                List<BookShelf> bookShelfes = GreenDaoManager.getInstance().getmDaoSession().getBookShelfDao().queryBuilder().orderDesc(BookShelfDao.Properties.FinalDate).list();
                for (int i = 0; i < bookShelfes.size(); i++) {
                    List<BookInfo> temp = GreenDaoManager.getInstance().getmDaoSession().getBookInfoDao().queryBuilder().where(BookInfoDao.Properties.NoteUrl.eq(bookShelfes.get(i).getNoteUrl())).limit(1).build().list();
                    if (temp != null && temp.size() > 0) {
                        BookInfo bookInfo = temp.get(0);
                        bookInfo.setChapterlist(GreenDaoManager.getInstance().getmDaoSession().getChapterListDao().queryBuilder().where(ChapterListDao.Properties.NoteUrl.eq(bookShelfes.get(i).getNoteUrl())).orderAsc(ChapterListDao.Properties.DurChapterIndex).build().list());
                        bookShelfes.get(i).setBookInfo(bookInfo);
                    } else {
                        GreenDaoManager.getInstance().getmDaoSession().getBookShelfDao().delete(bookShelfes.get(i));
                        bookShelfes.remove(i);
                        i--;
                    }
                }
                e.onNext(bookShelfes == null ? new ArrayList<BookShelf>() : bookShelfes);
            }
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<List<BookShelf>>() {
                    @Override
                    public void onNext(List<BookShelf> value) {
                        if (null != value) {
                            mView.refreshBookShelf(value);
                            if (needRefresh) {
                                startRefreshBook(value);
                            } else {
                                mView.refreshFinish();
                            }
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                        mView.refreshError(NetworkUtil.getErrorTip(NetworkUtil.ERROR_CODE_ANALY));
                    }
                });
    }

    public void startRefreshBook(List<BookShelf> value){
        if (value != null && value.size() > 0){
            mView.setRecyclerMaxProgress(value.size());
            refreshBookShelf(value,0);
        }else{
            mView.refreshFinish();
        }
    }

    private void refreshBookShelf(final List<BookShelf> value, final int index) {
        if (index<=value.size()-1) {
            WebBookModelImpl.getInstance().getChapterList(value.get(index), new OnGetChapterListListener() {
                @Override
                public void success(BookShelf bookShelf) {
                    saveBookToShelf(value,index);
                }

                @Override
                public void error() {
                    mView.refreshError(NetworkUtil.getErrorTip(NetworkUtil.ERROR_CODE_NONET));
                }
            });
        } else {
            queryBookShelf(false);
        }
    }

    private void saveBookToShelf(final List<BookShelf> datas, final int index){
        Observable.create(new ObservableOnSubscribe<BookShelf>() {
            @Override
            public void subscribe(ObservableEmitter<BookShelf> e) throws Exception {
                GreenDaoManager.getInstance().getmDaoSession().getChapterListDao().insertOrReplaceInTx(datas.get(index).getBookInfo().getChapterlist());
                e.onNext(datas.get(index));
                e.onComplete();
            }
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<BookShelf>() {
                    @Override
                    public void onNext(BookShelf value) {
                        mView.refreshRecyclerViewItemAdd();
                        refreshBookShelf(datas,index+1);
                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                        mView.refreshError(NetworkUtil.getErrorTip(NetworkUtil.ERROR_CODE_NONET));
                    }
                });
    }
    /////////////////////////////////////////////////////////////////////////////////////////////////////////////

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
                    @Tag(RxBusTag.HAD_ADD_BOOK),
                    @Tag(RxBusTag.HAD_REMOVE_BOOK),
                    @Tag(RxBusTag.UPDATE_BOOK_PROGRESS)
            }
    )
    public void hadAddOrRemoveBook(BookShelf bookShelf) {
        queryBookShelf(false);
    }
}
