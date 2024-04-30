package com.ebook.book.mvvm.model;

import android.app.Application;

import com.ebook.db.GreenDaoManager;
import com.ebook.db.entity.BookInfo;
import com.ebook.db.entity.BookInfoDao;
import com.ebook.db.entity.BookShelf;
import com.ebook.db.entity.BookShelfDao;
import com.ebook.db.entity.ChapterListDao;
import com.xrn1997.common.mvvm.model.BaseModel;

import java.util.List;

import io.reactivex.Observable;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class BookListModel extends BaseModel {
    public BookListModel(Application application) {
        super(application);
    }

    public Observable<List<BookShelf>> getBookShelfList() {
        return Observable.create((ObservableOnSubscribe<List<BookShelf>>) e -> {
                    List<BookShelf> bookShelves = GreenDaoManager.getInstance().getmDaoSession().getBookShelfDao().queryBuilder().orderDesc(BookShelfDao.Properties.FinalDate).list();
                    for (int i = 0; i < bookShelves.size(); i++) {
                        List<BookInfo> temp = GreenDaoManager.getInstance().getmDaoSession().getBookInfoDao().queryBuilder().where(BookInfoDao.Properties.NoteUrl.eq(bookShelves.get(i).getNoteUrl())).limit(1).build().list();
                        if (temp != null && !temp.isEmpty()) {
                            BookInfo bookInfo = temp.get(0);
                            bookInfo.setChapterlist(GreenDaoManager.getInstance().getmDaoSession().getChapterListDao().queryBuilder().where(ChapterListDao.Properties.NoteUrl.eq(bookShelves.get(i).getNoteUrl())).orderAsc(ChapterListDao.Properties.DurChapterIndex).build().list());
                            bookShelves.get(i).setBookInfo(bookInfo);
                        } else {
                            GreenDaoManager.getInstance().getmDaoSession().getBookShelfDao().delete(bookShelves.get(i));
                            bookShelves.remove(i);
                            i--;
                        }
                    }
                    e.onNext(bookShelves);
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }
}
