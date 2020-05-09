
package com.ebook.find.mvp.presenter.impl;


import com.ebook.api.config.API;
import com.ebook.basebook.mvp.model.OnGetChapterListListener;
import com.ebook.basebook.mvp.model.impl.WebBookModelImpl;
import com.ebook.basebook.utils.NetworkUtil;
import com.ebook.common.event.RxBusTag;
import com.ebook.basebook.observer.SimpleObserver;
import com.ebook.db.GreenDaoManager;

import com.ebook.find.mvp.presenter.ISearchPresenter;
import com.ebook.find.mvp.view.ISearchView;
import com.hwangjr.rxbus.RxBus;
import com.hwangjr.rxbus.annotation.Subscribe;
import com.hwangjr.rxbus.annotation.Tag;
import com.hwangjr.rxbus.thread.EventThread;
import com.ebook.basebook.base.IView;
import com.ebook.basebook.base.impl.BasePresenterImpl;
import com.ebook.db.entity.BookShelf;
import com.ebook.db.entity.SearchBook;
import com.ebook.db.entity.SearchHistory;
import com.ebook.db.entity.SearchHistoryDao;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import androidx.annotation.NonNull;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class SearchPresenterImpl extends BasePresenterImpl<ISearchView> implements ISearchPresenter {
    public static final String TAG_KEY = "tag";
    public static final String HASMORE_KEY = "hasMore";
    public static final String HASLOAD_KEY = "hasLoad";
    public static final String DURREQUESTTIME = "durRequestTime";    //当前搜索引擎失败次数  成功一次会重新开始计数
    public static final String MAXREQUESTTIME = "maxRequestTime";   //最大连续请求失败次数

    public static final int BOOK = 2;

    private Boolean hasSearch = false;   //判断是否搜索过

    private int page = 1;
    private List<Map> searchEngine;
    private long startThisSearchTime;
    private String durSearchKey;

    private List<BookShelf> bookShelfs = new ArrayList<>();   //用来比对搜索的书籍是否已经添加进书架

    private Boolean isInput = false;

    public SearchPresenterImpl() {
        Observable.create(new ObservableOnSubscribe<List<BookShelf>>() {
            @Override
            public void subscribe(ObservableEmitter<List<BookShelf>> e) throws Exception {
                List<BookShelf> temp = GreenDaoManager.getInstance().getmDaoSession().getBookShelfDao().queryBuilder().list();
                if (temp == null)
                    temp = new ArrayList<BookShelf>();
                e.onNext(temp);
                e.onComplete();
            }
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<List<BookShelf>>() {
                    @Override
                    public void onNext(List<BookShelf> value) {
                        bookShelfs.addAll(value);
                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                    }
                });

        //搜索引擎初始化
        searchEngine = new ArrayList<>();

        Map gxwztvMap = new HashMap();
        gxwztvMap.put(TAG_KEY, "https://www.ztv.la");
        gxwztvMap.put(HASMORE_KEY, true);
        gxwztvMap.put(HASLOAD_KEY, false);
        gxwztvMap.put(DURREQUESTTIME, 1);
        gxwztvMap.put(MAXREQUESTTIME, 3);
        searchEngine.add(gxwztvMap);
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
        Observable.create(new ObservableOnSubscribe<SearchHistory>() {
            @Override
            public void subscribe(ObservableEmitter<SearchHistory> e) throws Exception {
                List<SearchHistory> datas = GreenDaoManager.getInstance().getmDaoSession().getSearchHistoryDao()
                        .queryBuilder()
                        .where(SearchHistoryDao.Properties.Type.eq(type), SearchHistoryDao.Properties.Content.eq(content))
                        .limit(1)
                        .build().list();
                SearchHistory searchHistory = null;
                if (null != datas && datas.size() > 0) {
                    searchHistory = datas.get(0);
                    searchHistory.setDate(System.currentTimeMillis());
                    GreenDaoManager.getInstance().getmDaoSession().getSearchHistoryDao().update(searchHistory);
                } else {
                    searchHistory = new SearchHistory(type, content, System.currentTimeMillis());
                    GreenDaoManager.getInstance().getmDaoSession().getSearchHistoryDao().insert(searchHistory);
                }
                e.onNext(searchHistory);
            }
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<SearchHistory>() {
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
        final int type = SearchPresenterImpl.BOOK;
        final String content = mView.getEdtContent().getText().toString().trim();
        Observable.create(new ObservableOnSubscribe<Integer>() {
            @Override
            public void subscribe(ObservableEmitter<Integer> e) throws Exception {
                int a = GreenDaoManager.getInstance().getDb().delete(SearchHistoryDao.TABLENAME, SearchHistoryDao.Properties.Type.columnName + "=? and " + SearchHistoryDao.Properties.Content.columnName + " like ?", new String[]{String.valueOf(type), "%" + content + "%"});
                e.onNext(a);
            }
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<Integer>() {
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
        final int type = SearchPresenterImpl.BOOK;
        final String content = mView.getEdtContent().getText().toString().trim();
        Observable.create(new ObservableOnSubscribe<List<SearchHistory>>() {
            @Override
            public void subscribe(ObservableEmitter<List<SearchHistory>> e) throws Exception {
                List<SearchHistory> datas = GreenDaoManager.getInstance().getmDaoSession().getSearchHistoryDao()
                        .queryBuilder()
                        .where(SearchHistoryDao.Properties.Type.eq(type), SearchHistoryDao.Properties.Content.like("%" + content + "%"))
                        .orderDesc(SearchHistoryDao.Properties.Date)
                        .limit(20)
                        .build().list();
                e.onNext(datas);
            }
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<List<SearchHistory>>() {
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
                searchEngine.get(i).put(HASMORE_KEY, true);
                searchEngine.get(i).put(HASLOAD_KEY, false);
                searchEngine.get(i).put(DURREQUESTTIME, 1);
            }
        }
        searchBook(durSearchKey, startThisSearchTime, fromError);
    }

    private void searchBook(final String content, final long searchTime, Boolean fromError) {
        if (searchTime == startThisSearchTime) {
            Boolean canLoad = false;
            for (Map temp : searchEngine) {
                if ((Boolean) temp.get(HASMORE_KEY) && (int) temp.get(DURREQUESTTIME) <= (int) temp.get(MAXREQUESTTIME)) {
                    canLoad = true;
                    break;
                }
            }
            if (canLoad) {
                int searchEngineIndex = -1;
                for (int i = 0; i < searchEngine.size(); i++) {
                    if (!(Boolean) searchEngine.get(i).get(HASLOAD_KEY) && (int) searchEngine.get(i).get(DURREQUESTTIME) <= (int) searchEngine.get(i).get(MAXREQUESTTIME)) {
                        searchEngineIndex = i;
                        break;
                    }
                }
                if (searchEngineIndex == -1) {
                    this.page++;
                    for (Map item : searchEngine) {
                        item.put(HASLOAD_KEY, false);
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
                    WebBookModelImpl.getInstance().searchOtherBook(content, page, (String) searchEngine.get(searchEngineIndex).get(TAG_KEY))
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribeOn(Schedulers.io())
                            .subscribe(new SimpleObserver<List<SearchBook>>() {
                                @Override
                                public void onNext(List<SearchBook> value) {
                                    if (searchTime == startThisSearchTime) {
                                        searchEngine.get(finalSearchEngineIndex).put(HASLOAD_KEY, true);
                                        searchEngine.get(finalSearchEngineIndex).put(DURREQUESTTIME, 1);
                                        if (value.size() == 0) {
                                            searchEngine.get(finalSearchEngineIndex).put(HASMORE_KEY, false);
                                        } else {
                                            for (SearchBook temp : value) {
                                                for (BookShelf bookShelf : bookShelfs) {
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
                                            if (value != null && value.size() > 0 && !mView.checkIsExist(value.get(0)))
                                                mView.loadMoreSearchBook(value);
                                            else {
                                                searchEngine.get(finalSearchEngineIndex).put(HASMORE_KEY, false);
                                            }
                                        }
                                        searchBook(content, searchTime, false);
                                    }
                                }

                                @Override
                                public void onError(Throwable e) {
                                    e.printStackTrace();
                                    if (searchTime == startThisSearchTime) {
                                        searchEngine.get(finalSearchEngineIndex).put(HASLOAD_KEY, false);
                                        searchEngine.get(finalSearchEngineIndex).put(DURREQUESTTIME, ((int) searchEngine.get(finalSearchEngineIndex).get(DURREQUESTTIME)) + 1);
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
                for (Map item : searchEngine) {
                    item.put(HASLOAD_KEY, false);
                }
            }
        }
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

    @Subscribe(thread = EventThread.MAIN_THREAD,
            tags = {
                    @Tag(RxBusTag.HAD_REMOVE_BOOK)
            }
    )
    public void hadRemoveBook(BookShelf bookShelf) {
        if (bookShelfs != null) {
            for (int i = 0; i < bookShelfs.size(); i++) {
                if (bookShelfs.get(i).getNoteUrl().equals(bookShelf.getNoteUrl())) {
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

    @Override
    public Boolean getInput() {
        return isInput;
    }

    @Override
    public void setInput(Boolean input) {
        isInput = input;
    }
}
