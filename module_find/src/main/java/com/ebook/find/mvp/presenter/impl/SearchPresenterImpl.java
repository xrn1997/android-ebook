package com.ebook.find.mvp.presenter.impl;


import androidx.annotation.NonNull;

import com.ebook.api.service.ZeroBookService;
import com.ebook.basebook.base.IView;
import com.ebook.basebook.base.impl.BasePresenterImpl;
import com.ebook.basebook.mvp.model.impl.WebBookModelImpl;
import com.ebook.basebook.observer.SimpleObserver;
import com.ebook.basebook.utils.NetworkUtil;
import com.ebook.common.event.RxBusTag;
import com.ebook.db.GreenDaoManager;
import com.ebook.db.entity.BookShelf;
import com.ebook.db.entity.SearchBook;
import com.ebook.db.entity.SearchHistory;
import com.ebook.db.entity.SearchHistoryDao;
import com.ebook.db.entity.WebChapter;
import com.ebook.find.mvp.presenter.ISearchPresenter;
import com.ebook.find.mvp.view.ISearchView;
import com.hwangjr.rxbus.RxBus;
import com.hwangjr.rxbus.annotation.Subscribe;
import com.hwangjr.rxbus.annotation.Tag;
import com.hwangjr.rxbus.thread.EventThread;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class SearchPresenterImpl extends BasePresenterImpl<ISearchView> implements ISearchPresenter {
    public static final String TAG_KEY = "tag";
    public static final String HAS_MORE_KEY = "hasMore";
    public static final String HAS_LOAD_KEY = "hasLoad";
    public static final String DUR_REQUEST_TIME = "durRequestTime";    //当前搜索引擎失败次数  成功一次会重新开始计数
    public static final String MAX_REQUEST_TIME = "maxRequestTime";   //最大连续请求失败次数

    public static final int BOOK = 2;
    private final List<Map<String, Object>> searchEngine;
    private final List<BookShelf> bookShelfList = new ArrayList<>();   //用来比对搜索的书籍是否已经添加进书架
    private Boolean hasSearch = false;   //判断是否搜索过
    private int page = 1;
    private long startThisSearchTime;
    private String durSearchKey;
    private Boolean isInput = false;

    public SearchPresenterImpl() {
        Observable.create((ObservableOnSubscribe<List<BookShelf>>) e -> {
                    List<BookShelf> temp = GreenDaoManager.getInstance().getmDaoSession().getBookShelfDao().queryBuilder().list();
                    if (temp == null)
                        temp = new ArrayList<>();
                    e.onNext(temp);
                    e.onComplete();
                }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<>() {
                    @Override
                    public void onNext(List<BookShelf> value) {
                        bookShelfList.addAll(value);
                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                    }
                });

        //搜索引擎初始化
        searchEngine = new ArrayList<>();

        Map<String, Object> map = new HashMap<>();
        map.put(TAG_KEY, ZeroBookService.URL);
        map.put(HAS_MORE_KEY, true);
        map.put(HAS_LOAD_KEY, false);
        map.put(DUR_REQUEST_TIME, 1);
        map.put(MAX_REQUEST_TIME, 3);
        searchEngine.add(map);
    }

    @Override
    public Boolean getHasSearch() {
        return hasSearch;
    }

    @Override
    public void setHasSearch(Boolean hasSearch) {
        this.hasSearch = hasSearch;
    }

    @Override
    public void insertSearchHistory() {
        final int type = SearchPresenterImpl.BOOK;
        final String content = mView.getEdtContent().getText().toString().trim();
        Observable.create((ObservableOnSubscribe<SearchHistory>) e -> {
                    List<SearchHistory> datas = GreenDaoManager.getInstance().getmDaoSession().getSearchHistoryDao()
                            .queryBuilder()
                            .where(SearchHistoryDao.Properties.Type.eq(type), SearchHistoryDao.Properties.Content.eq(content))
                            .limit(1)
                            .build().list();
                    SearchHistory searchHistory;
                    if (null != datas && datas.size() > 0) {
                        searchHistory = datas.get(0);
                        searchHistory.setDate(System.currentTimeMillis());
                        GreenDaoManager.getInstance().getmDaoSession().getSearchHistoryDao().update(searchHistory);
                    } else {
                        searchHistory = new SearchHistory(type, content, System.currentTimeMillis());
                        GreenDaoManager.getInstance().getmDaoSession().getSearchHistoryDao().insert(searchHistory);
                    }
                    e.onNext(searchHistory);
                }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<>() {
                    @Override
                    public void onNext(SearchHistory value) {
                        mView.insertSearchHistorySuccess(value);
                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                    }
                });
    }

    @Override
    public void cleanSearchHistory() {
        final String content = mView.getEdtContent().getText().toString().trim();
        Observable.create((ObservableOnSubscribe<Integer>) e -> {
                    int a = GreenDaoManager.getInstance().getDb().delete(
                            SearchHistoryDao.TABLENAME,
                            SearchHistoryDao.Properties.Type.columnName + "=? and " + SearchHistoryDao.Properties.Content.columnName + " like ?",
                            new String[]{String.valueOf(SearchPresenterImpl.BOOK), "%" + content + "%"});
                    e.onNext(a);
                }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<>() {
                    @Override
                    public void onNext(Integer value) {
                        if (value > 0) {
                            mView.querySearchHistorySuccess(null);
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                    }
                });
    }

    @Override
    public void querySearchHistory() {
        final String content = mView.getEdtContent().getText().toString().trim();
        Observable.create((ObservableOnSubscribe<List<SearchHistory>>) e -> {
                    List<SearchHistory> datas = GreenDaoManager.getInstance().getmDaoSession().getSearchHistoryDao()
                            .queryBuilder()
                            .where(SearchHistoryDao.Properties.Type.eq(SearchPresenterImpl.BOOK), SearchHistoryDao.Properties.Content.like("%" + content + "%"))
                            .orderDesc(SearchHistoryDao.Properties.Date)
                            .limit(20)
                            .build().list();
                    e.onNext(datas);
                }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<>() {
                    @Override
                    public void onNext(List<SearchHistory> value) {
                        if (null != value)
                            mView.querySearchHistorySuccess(value);
                    }

                    @Override
                    public void onError(Throwable e) {

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
    }

    @Override
    public void toSearchBooks(String key, Boolean fromError) {
        if (key != null) {
            durSearchKey = key;
            this.startThisSearchTime = System.currentTimeMillis();
            for (int i = 0; i < searchEngine.size(); i++) {
                searchEngine.get(i).put(HAS_MORE_KEY, true);
                searchEngine.get(i).put(HAS_LOAD_KEY, false);
                searchEngine.get(i).put(DUR_REQUEST_TIME, 1);
            }
        }
        searchBook(durSearchKey, startThisSearchTime, fromError);
    }

    private void searchBook(final String content, final long searchTime, Boolean fromError) {
        if (searchTime == startThisSearchTime) {
            boolean canLoad = false;
            for (Map<String, Object> temp : searchEngine) {
                if ((Boolean) temp.get(HAS_MORE_KEY) && (int) temp.get(DUR_REQUEST_TIME) <= (int) temp.get(MAX_REQUEST_TIME)) {
                    canLoad = true;
                    break;
                }
            }
            if (canLoad) {
                int searchEngineIndex = -1;
                for (int i = 0; i < searchEngine.size(); i++) {
                    if (!(Boolean) searchEngine.get(i).get(HAS_LOAD_KEY) && (int) searchEngine.get(i).get(DUR_REQUEST_TIME) <= (int) searchEngine.get(i).get(MAX_REQUEST_TIME)) {
                        searchEngineIndex = i;
                        break;
                    }
                }
                if (searchEngineIndex == -1) {
                    this.page++;
                    for (Map<String, Object> item : searchEngine) {
                        item.put(HAS_LOAD_KEY, false);
                    }
                    if (!fromError) {
                        if (page - 1 == 1) {
                            mView.refreshFinish(false);
                        } else {
                            mView.loadMoreFinish(false);
                        }
                    } else {
                        searchBook(content, searchTime, false);
                    }
                } else {
                    final int finalSearchEngineIndex = searchEngineIndex;
                    WebBookModelImpl.getInstance().searchBook(content, page)
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribeOn(Schedulers.io())
                            .subscribe(new SimpleObserver<>() {
                                @Override
                                public void onNext(List<SearchBook> value) {
                                    if (searchTime == startThisSearchTime) {
                                        searchEngine.get(finalSearchEngineIndex).put(HAS_LOAD_KEY, true);
                                        searchEngine.get(finalSearchEngineIndex).put(DUR_REQUEST_TIME, 1);
                                        if (value.size() == 0) {
                                            searchEngine.get(finalSearchEngineIndex).put(HAS_MORE_KEY, false);
                                        } else {
                                            for (SearchBook temp : value) {
                                                for (BookShelf bookShelf : bookShelfList) {
                                                    if (temp.getNoteUrl().equals(bookShelf.getNoteUrl())) {
                                                        temp.setAdd(true);
                                                        break;
                                                    }
                                                }
                                            }
                                        }
                                        if (page == 1 && finalSearchEngineIndex == 0) {
                                            mView.refreshSearchBook(value);
                                        } else {
                                            if (value.size() > 0 && !mView.checkIsExist(value.get(0)))
                                                mView.loadMoreSearchBook(value);
                                            else {
                                                searchEngine.get(finalSearchEngineIndex).put(HAS_MORE_KEY, false);
                                            }
                                        }
                                        searchBook(content, searchTime, false);
                                    }
                                }

                                @Override
                                public void onError(Throwable e) {
                                    e.printStackTrace();
                                    if (searchTime == startThisSearchTime) {
                                        searchEngine.get(finalSearchEngineIndex).put(HAS_LOAD_KEY, false);
                                        searchEngine.get(finalSearchEngineIndex).put(DUR_REQUEST_TIME, ((int) searchEngine.get(finalSearchEngineIndex).get(DUR_REQUEST_TIME)) + 1);
                                        mView.searchBookError(page == 1 && (finalSearchEngineIndex == 0 || (finalSearchEngineIndex > 0 && mView.getSearchBookAdapter().getItemcount() == 0)));
                                    }
                                }
                            });
                }
            } else {
                if (page == 1) {
                    mView.refreshFinish(true);
                } else {
                    mView.loadMoreFinish(true);
                }
                this.page++;
                for (Map<String, Object> item : searchEngine) {
                    item.put(HAS_LOAD_KEY, false);
                }
            }
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void addBookToShelf(final SearchBook searchBook) {
        //  Log.e("添加到书架", searchBook.toString());
        final BookShelf bookShelfResult = new BookShelf();
        bookShelfResult.setNoteUrl(searchBook.getNoteUrl());
        bookShelfResult.setFinalDate(0);
        bookShelfResult.setDurChapter(0);
        bookShelfResult.setDurChapterPage(0);
        bookShelfResult.setTag(searchBook.getTag());
        WebBookModelImpl.getInstance().getBookInfo(bookShelfResult)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<>() {
                    @Override
                    public void onNext(BookShelf value) {
                        WebBookModelImpl.getInstance().getChapterList(value).subscribe(new SimpleObserver<>() {
                            @Override
                            public void onNext(WebChapter<BookShelf> bookShelfWebChapter) {
                                saveBookToShelf(bookShelfWebChapter.getData());
                            }

                            @Override
                            public void onError(Throwable e) {
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

    private void saveBookToShelf(final BookShelf bookShelf) {
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

    @Subscribe(thread = EventThread.MAIN_THREAD,
            tags = {
                    @Tag(RxBusTag.HAD_ADD_BOOK)
            }
    )
    public void hadAddBook(BookShelf bookShelf) {
        this.bookShelfList.add(bookShelf);
        List<SearchBook> searchBookList = mView.getSearchBookAdapter().getSearchBooks();
        for (int i = 0; i < searchBookList.size(); i++) {
            if (searchBookList.get(i).getNoteUrl().equals(bookShelf.getNoteUrl())) {
                searchBookList.get(i).setAdd(true);
                mView.updateSearchItem(i);
                break;
            }
        }
    }

    @Subscribe(thread = EventThread.MAIN_THREAD,
            tags = {
                    @Tag(RxBusTag.HAD_REMOVE_BOOK)
            }
    )
    public void hadRemoveBook(BookShelf bookShelf) {
        for (int i = 0; i < this.bookShelfList.size(); i++) {
            if (this.bookShelfList.get(i).getNoteUrl().equals(bookShelf.getNoteUrl())) {
                this.bookShelfList.remove(i);
                break;
            }
        }
        List<SearchBook> searchBookList = mView.getSearchBookAdapter().getSearchBooks();
        for (int i = 0; i < searchBookList.size(); i++) {
            if (searchBookList.get(i).getNoteUrl().equals(bookShelf.getNoteUrl())) {
                searchBookList.get(i).setAdd(false);
                mView.updateSearchItem(i);
                break;
            }
        }
    }

    @Override
    public Boolean getInput() {
        return isInput;
    }

    @Override
    public void setInput(Boolean input) {
        isInput = input;
    }
}
