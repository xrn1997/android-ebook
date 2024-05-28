package com.ebook.find.mvvm.model;

import android.app.Application;

import com.ebook.basebook.cache.ACache;
import com.ebook.basebook.constant.Url;
import com.ebook.basebook.mvp.model.impl.WebBookModelImpl;
import com.ebook.db.GreenDaoManager;
import com.ebook.db.entity.BookShelf;
import com.ebook.db.entity.Library;
import com.ebook.db.entity.SearchHistory;
import com.ebook.db.entity.SearchHistoryDao;
import com.ebook.find.entity.BookType;
import com.xrn1997.common.mvvm.model.BaseModel;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.ObservableSource;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;


public class LibraryModel extends BaseModel {
    public final static String LIBRARY_CACHE_KEY = "cache_library";

    public LibraryModel(Application application) {
        super(application);
    }

    //获得书库信息
    public Observable<Library> getLibraryData(ACache mCache) {
        return Observable.create((ObservableOnSubscribe<String>) e -> {
                    String cache = mCache.getAsString(LIBRARY_CACHE_KEY);
                    e.onNext(cache);
                    e.onComplete();
                }).flatMap((Function<String, ObservableSource<Library>>) s -> WebBookModelImpl.getInstance().analyzeLibraryData(s))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    //获取书架书籍列表信息
    public Observable<List<BookShelf>> getBookShelfList() {
        return Observable.create((ObservableOnSubscribe<List<BookShelf>>) e -> {
                    List<BookShelf> temp = GreenDaoManager.getInstance().getmDaoSession().getBookShelfDao().queryBuilder().list();
                    if (temp == null)
                        temp = new ArrayList<>();
                    e.onNext(temp);
                    e.onComplete();
                }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    //将书籍信息存入书架书籍列表
    public Observable<BookShelf> saveBookToShelf(BookShelf bookShelf) {
        return Observable.create((ObservableOnSubscribe<BookShelf>) e -> {
                    GreenDaoManager.getInstance().getmDaoSession().getChapterListDao().insertOrReplaceInTx(bookShelf.getBookInfo().getChapterlist());
                    GreenDaoManager.getInstance().getmDaoSession().getBookInfoDao().insertOrReplace(bookShelf.getBookInfo());
                    //网络数据获取成功  存入BookShelf表数据库
                    GreenDaoManager.getInstance().getmDaoSession().getBookShelfDao().insertOrReplace(bookShelf);
                    e.onNext(bookShelf);
                    e.onComplete();
                }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    //保存查询记录
    public Observable<SearchHistory> insertSearchHistory(int type, String content) {
        return Observable.create(new ObservableOnSubscribe<SearchHistory>() {
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
                .observeOn(AndroidSchedulers.mainThread());
    }

    //删除查询记录
    public Observable<Integer> cleanSearchHistory(int type, String content) {
        return Observable.create(new ObservableOnSubscribe<Integer>() {
                    @Override
                    public void subscribe(ObservableEmitter<Integer> e) throws Exception {
                        int a = GreenDaoManager.getInstance().getDb().delete(SearchHistoryDao.TABLENAME, SearchHistoryDao.Properties.Type.columnName + "=? and " + SearchHistoryDao.Properties.Content.columnName + " like ?", new String[]{String.valueOf(type), "%" + content + "%"});
                        e.onNext(a);
                    }
                }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    //获得查询记录
    public Observable<List<SearchHistory>> querySearchHistory(int type, String content) {
        return Observable.create(new ObservableOnSubscribe<List<SearchHistory>>() {
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
                .observeOn(AndroidSchedulers.mainThread());
    }

    //获取书籍类型信息，此处用本地数据。
    public List<BookType> getBookTypeList() {
        List<BookType> bookTypeList = new ArrayList<>();
        bookTypeList.add(new BookType("玄幻小说", Url.xh));
        bookTypeList.add(new BookType("修真小说", Url.xz));
        bookTypeList.add(new BookType("都市小说", Url.ds));
        bookTypeList.add(new BookType("历史小说", Url.ls));
        bookTypeList.add(new BookType("网游小说", Url.wy));
        bookTypeList.add(new BookType("科幻小说", Url.kh));
        bookTypeList.add(new BookType("其他小说", Url.qt));
        return bookTypeList;
    }

}
