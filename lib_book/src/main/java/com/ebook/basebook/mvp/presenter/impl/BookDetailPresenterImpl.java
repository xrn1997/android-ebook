package com.ebook.basebook.mvp.presenter.impl;

import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.ebook.basebook.base.IView;
import com.ebook.basebook.base.activity.BaseActivity;
import com.ebook.basebook.base.impl.BasePresenterImpl;
import com.ebook.basebook.base.manager.BitIntentDataManager;
import com.ebook.basebook.mvp.model.impl.WebBookModelImpl;
import com.ebook.basebook.mvp.presenter.IBookDetailPresenter;
import com.ebook.basebook.mvp.view.IBookDetailView;
import com.ebook.basebook.observer.SimpleObserver;
import com.ebook.common.BaseApplication;
import com.ebook.common.event.RxBusTag;
import com.ebook.db.GreenDaoManager;
import com.ebook.db.entity.BookShelf;
import com.ebook.db.entity.SearchBook;
import com.ebook.db.entity.WebChapter;
import com.hwangjr.rxbus.RxBus;
import com.hwangjr.rxbus.annotation.Subscribe;
import com.hwangjr.rxbus.annotation.Tag;
import com.hwangjr.rxbus.thread.EventThread;
import com.trello.rxlifecycle3.android.ActivityEvent;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.reactivex.Observable;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.ObservableSource;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;

public class BookDetailPresenterImpl extends BasePresenterImpl<IBookDetailView> implements IBookDetailPresenter {
    public final static int FROM_BOOKSHELF = 1;
    public final static int FROM_SEARCH = 2;

    private final int openFrom;
    private final List<BookShelf> bookShelfList = Collections.synchronizedList(new ArrayList<>());   //用来比对搜索的书籍是否已经添加进书架
    private SearchBook searchBook;
    private BookShelf mBookShelf;
    private Boolean inBookShelf;

    public BookDetailPresenterImpl(Intent intent) {
        openFrom = intent.getIntExtra("from", FROM_BOOKSHELF);
        if (openFrom == FROM_BOOKSHELF) {
            String key = intent.getStringExtra("data_key");
            mBookShelf = (BookShelf) BitIntentDataManager.getInstance().getData(key);
            BitIntentDataManager.getInstance().cleanData(key);
            inBookShelf = true;
        } else {
            searchBook = intent.getParcelableExtra("data");
            inBookShelf = searchBook.getAdd();
        }
    }

    public Boolean getInBookShelf() {
        return inBookShelf;
    }

    public void setInBookShelf(Boolean inBookShelf) {
        this.inBookShelf = inBookShelf;
    }

    public int getOpenFrom() {
        return openFrom;
    }

    public SearchBook getSearchBook() {
        return searchBook;
    }

    public BookShelf getBookShelf() {
        return mBookShelf;
    }

    @Override
    public void getBookShelfInfo() {
        Observable.create((ObservableOnSubscribe<List<BookShelf>>) e -> {
                    List<BookShelf> temp = GreenDaoManager.getInstance().getmDaoSession().getBookShelfDao().queryBuilder().list();
                    if (temp == null)
                        temp = new ArrayList<>();
                    e.onNext(temp);
                    e.onComplete();
                }).flatMap((Function<List<BookShelf>, ObservableSource<BookShelf>>) bookShelf -> {
                    bookShelfList.addAll(bookShelf);

                    final BookShelf bookShelfResult = new BookShelf();
                    bookShelfResult.setNoteUrl(searchBook.getNoteUrl());
                    bookShelfResult.setFinalDate(System.currentTimeMillis());
                    bookShelfResult.setDurChapter(0);
                    bookShelfResult.setDurChapterPage(0);
                    bookShelfResult.setTag(searchBook.getTag());
                    return WebBookModelImpl.getInstance().getBookInfo(bookShelfResult);
                }).map(bookShelf -> {
                    for (int i = 0; i < bookShelfList.size(); i++) {
                        if (bookShelfList.get(i).getNoteUrl().equals(bookShelf.getNoteUrl())) {
                            inBookShelf = true;
                            bookShelf.setDurChapter(bookShelfList.get(i).getDurChapter());
                            bookShelf.setDurChapterPage(bookShelfList.get(i).getDurChapterPage());
                            break;
                        }
                    }
                    return bookShelf;
                }).subscribeOn(Schedulers.io())
                .compose(((BaseActivity<?>) mView.getContext()).bindUntilEvent(ActivityEvent.DESTROY))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<>() {
                    @Override
                    public void onNext(@NotNull BookShelf value) {
                        WebBookModelImpl.getInstance().getChapterList(value).subscribe(new SimpleObserver<>() {
                            @Override
                            public void onNext(WebChapter<BookShelf> bookShelfWebChapter) {
                                mBookShelf = bookShelfWebChapter.getData();
                                mView.updateView();
                            }

                            @Override
                            public void onError(Throwable e) {
                                mBookShelf = null;
                                Log.e("错误信息", "getChapterList onError: " + e);
                                mView.getBookShelfError();
                            }
                        });
                    }

                    @Override
                    public void onError(@NotNull Throwable e) {
                        mBookShelf = null;
                        Log.e("错误信息", "subscribe onError: " + e);
                        mView.getBookShelfError();
                    }
                });
    }

    @Override
    public void addToBookShelf() {
        if (mBookShelf != null) {
            Observable.create((ObservableOnSubscribe<Boolean>) e -> {
                        GreenDaoManager.getInstance().getmDaoSession().getChapterListDao().insertOrReplaceInTx(mBookShelf.getBookInfo().getChapterlist());
                        GreenDaoManager.getInstance().getmDaoSession().getBookInfoDao().insertOrReplace(mBookShelf.getBookInfo());
                        //网络数据获取成功  存入BookShelf表数据库
                        GreenDaoManager.getInstance().getmDaoSession().getBookShelfDao().insertOrReplace(mBookShelf);
                        e.onNext(true);
                        e.onComplete();
                    }).subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .compose(((BaseActivity<?>) mView.getContext()).bindUntilEvent(ActivityEvent.DESTROY))
                    .subscribe(new SimpleObserver<>() {
                        @Override
                        public void onNext(@NotNull Boolean value) {
                            if (value) {
                                RxBus.get().post(RxBusTag.HAD_ADD_BOOK, mBookShelf);
                            } else {
                                Toast.makeText(BaseApplication.context, "放入书架失败!", Toast.LENGTH_SHORT).show();
                            }
                        }

                        @Override
                        public void onError(@NotNull Throwable e) {
                            e.printStackTrace();
                            Toast.makeText(BaseApplication.context, "放入书架失败!", Toast.LENGTH_SHORT).show();
                        }
                    });
        }
    }

    @Override
    public void removeFromBookShelf() {
        if (mBookShelf != null) {
            Observable.create((ObservableOnSubscribe<Boolean>) e -> {
                        GreenDaoManager.getInstance().getmDaoSession().getBookShelfDao().deleteByKey(mBookShelf.getNoteUrl());
                        GreenDaoManager.getInstance().getmDaoSession().getBookInfoDao().deleteByKey(mBookShelf.getBookInfo().getNoteUrl());
                        List<String> keys = new ArrayList<>();
                        if (mBookShelf.getBookInfo().getChapterlist().size() > 0) {
                            for (int i = 0; i < mBookShelf.getBookInfo().getChapterlist().size(); i++) {
                                keys.add(mBookShelf.getBookInfo().getChapterlist().get(i).getDurChapterUrl());
                            }
                        }
                        GreenDaoManager.getInstance().getmDaoSession().getBookContentDao().deleteByKeyInTx(keys);
                        GreenDaoManager.getInstance().getmDaoSession().getChapterListDao().deleteInTx(mBookShelf.getBookInfo().getChapterlist());
                        e.onNext(true);
                        e.onComplete();
                    }).subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .compose(((BaseActivity) mView.getContext()).bindUntilEvent(ActivityEvent.DESTROY))
                    .subscribe(new SimpleObserver<>() {
                        @Override
                        public void onNext(Boolean value) {
                            if (value) {
                                RxBus.get().post(RxBusTag.HAD_REMOVE_BOOK, mBookShelf);
                            } else {
                                Toast.makeText(BaseApplication.context, "移出书架失败!", Toast.LENGTH_SHORT).show();
                            }
                        }

                        @Override
                        public void onError(Throwable e) {
                            e.printStackTrace();
                            Toast.makeText(BaseApplication.context, "移出书架失败!", Toast.LENGTH_SHORT).show();
                        }
                    });
        }
    }

    @Override
    public void attachView(@NonNull IView iView) {
        super.attachView(iView);
        RxBus.get().register(this);
    }

    @Override
    public void detachView() {
        RxBus.get().unregister(this);
    }

    @Subscribe(thread = EventThread.MAIN_THREAD,
            tags = {
                    @Tag(RxBusTag.HAD_ADD_BOOK)
            }
    )
    public void hadAddBook(BookShelf value) {
        if ((null != mBookShelf && value.getNoteUrl().equals(mBookShelf.getNoteUrl())) || (null != searchBook && value.getNoteUrl().equals(searchBook.getNoteUrl()))) {
            inBookShelf = true;
            if (null != searchBook) {
                searchBook.setAdd(true);
            }
            mView.updateView();
        }
    }

    @Subscribe(thread = EventThread.MAIN_THREAD,
            tags = {
                    @Tag(RxBusTag.HAD_REMOVE_BOOK)
            }
    )
    public void hadRemoveBook(BookShelf value) {
        for (int i = 0; i < bookShelfList.size(); i++) {
            if (bookShelfList.get(i).getNoteUrl().equals(value.getNoteUrl())) {
                bookShelfList.remove(i);
                break;
            }
        }
        if ((null != mBookShelf && value.getNoteUrl().equals(mBookShelf.getNoteUrl())) || (null != searchBook && value.getNoteUrl().equals(searchBook.getNoteUrl()))) {
            inBookShelf = false;
            if (null != searchBook) {
                searchBook.setAdd(false);
            }
            mView.updateView();
        }
    }

    @Subscribe(thread = EventThread.MAIN_THREAD,
            tags = {
                    @Tag(RxBusTag.HAD_ADD_BOOK),
            }
    )
    public void hadBook(BookShelf value) {
        bookShelfList.add(value);
        if ((null != mBookShelf && value.getNoteUrl().equals(mBookShelf.getNoteUrl())) || (null != searchBook && value.getNoteUrl().equals(searchBook.getNoteUrl()))) {
            inBookShelf = true;
            if (null != searchBook) {
                searchBook.setAdd(true);
            }
            mView.updateView();
        }
    }
}
