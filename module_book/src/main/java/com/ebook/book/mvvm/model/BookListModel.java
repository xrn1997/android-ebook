package com.ebook.book.mvvm.model;

import android.app.Application;

import com.ebook.common.mvvm.model.BaseModel;
import com.ebook.db.GreenDaoManager;
import com.ebook.db.entity.BookInfo;
import com.ebook.db.entity.BookInfoDao;
import com.ebook.db.entity.BookShelf;
import com.ebook.db.entity.BookShelfDao;
import com.ebook.db.entity.ChapterListDao;

import java.util.List;

import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class BookListModel extends BaseModel {
    public BookListModel(Application application) {
        super(application);
    }

    public Observable<List<BookShelf>> getBookShelfList(){
        return Observable.create(new ObservableOnSubscribe<List<BookShelf>>() {
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
                e.onNext(bookShelfes);
            }
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }
}
