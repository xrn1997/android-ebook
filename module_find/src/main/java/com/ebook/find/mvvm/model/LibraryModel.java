package com.ebook.find.mvvm.model;

import android.app.Application;

import com.ebook.basebook.cache.ACache;
import com.ebook.basebook.mvp.model.impl.GxwztvBookModelImpl;
import com.ebook.common.mvvm.model.BaseModel;
import com.ebook.db.GreenDaoManager;
import com.ebook.db.entity.BookShelf;
import com.ebook.db.entity.Library;
import com.ebook.db.entity.SearchHistory;
import com.ebook.db.entity.SearchHistoryDao;
import com.ebook.find.entity.BookType;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.ObservableSource;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;

import static com.ebook.find.mvp.presenter.impl.LibraryPresenterImpl.LIBRARY_CACHE_KEY;

public class LibraryModel extends BaseModel {

    public LibraryModel(Application application) {
        super(application);
    }
    //获得书库信息
    public Observable<Library> getLibraryData(ACache mCache){
        return Observable.create(new ObservableOnSubscribe<String>() {
            @Override
            public void subscribe(ObservableEmitter<String> e) throws Exception {
                String cache = mCache.getAsString(LIBRARY_CACHE_KEY);
                e.onNext(cache);
                e.onComplete();
            }
        }).flatMap(new Function<String, ObservableSource<Library>>() {
            @Override
            public ObservableSource<Library> apply(String s) throws Exception {
                return GxwztvBookModelImpl.getInstance().analyLibraryData(s);
            }
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }
    //获取书架书籍列表信息
    public Observable<List<BookShelf>> getBookShelfList() {
        return Observable.create(new ObservableOnSubscribe<List<BookShelf>>() {
            @Override
            public void subscribe(ObservableEmitter<List<BookShelf>> e) throws Exception {
                List<BookShelf> temp = GreenDaoManager.getInstance().getmDaoSession().getBookShelfDao().queryBuilder().list();
                if (temp == null)
                    temp = new ArrayList<BookShelf>();
                e.onNext(temp);
                e.onComplete();
            }
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }
    //将书籍信息存入书架书籍列表
    public Observable<BookShelf> saveBookToShelf(BookShelf bookShelf) {
        return Observable.create(new ObservableOnSubscribe<BookShelf>() {
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
    public Observable<Integer>  cleanSearchHistory(int  type,String content){
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
    public Observable<List<SearchHistory>> querySearchHistory(int type ,String content){
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
    public List<BookType> getBookTypeList(){
        List<BookType> bookTypeList=new ArrayList<>();
        bookTypeList.add(new BookType("东方玄幻","http://www.gxwztv.com/xuanhuanxiaoshuo/"));
        bookTypeList.add(new BookType("西方奇幻","http://www.gxwztv.com/qihuanxiaoshuo/"));
        bookTypeList.add(new BookType("热血修真","http://www.gxwztv.com/xiuzhenxiaoshuo/"));
        bookTypeList.add(new BookType("武侠仙侠","http://www.gxwztv.com/wuxiaxiaoshuo/"));
        bookTypeList.add(new BookType("都市爽文","http://www.gxwztv.com/dushixiaoshuo/"));
        bookTypeList.add(new BookType("言情暧昧","http://www.gxwztv.com/yanqingxiaoshuo/"));
        bookTypeList.add(new BookType("灵异悬疑","http://www.gxwztv.com/lingyixiaoshuo/"));
        bookTypeList.add(new BookType("运动竞技","http://www.gxwztv.com/jingjixiaoshuo/"));
        bookTypeList.add(new BookType("历史架空","http://www.gxwztv.com/lishixiaoshuo/"));
        bookTypeList.add(new BookType("耽美","http://www.gxwztv.com/danmeixiaoshuo/"));
        bookTypeList.add(new BookType("科幻迷航","http://www.gxwztv.com/kehuanxiaoshuo/"));
        bookTypeList.add(new BookType("游戏人生","http://www.gxwztv.com/youxixiaoshuo/"));
        bookTypeList.add(new BookType("军事斗争","http://www.gxwztv.com/junshixiaoshuo/"));
        bookTypeList.add(new BookType("商战人生","http://www.gxwztv.com/shangzhanxiaoshuo/"));
        bookTypeList.add(new BookType("校园爱情","http://www.gxwztv.com/xiaoyuanxiaoshuo/"));
        bookTypeList.add(new BookType("官场仕途","http://www.gxwztv.com/guanchangxiaoshuo/"));
        bookTypeList.add(new BookType("娱乐明星","http://www.gxwztv.com/zhichangxiaoshuo/"));
        bookTypeList.add(new BookType("其他","http://www.gxwztv.com/qitaxiaoshuo/"));
        return bookTypeList;
    }

}
