package com.ebook.basebook.mvp.model.impl;


import com.ebook.basebook.mvp.model.IWebBookModel;
import com.ebook.basebook.mvp.model.OnGetChapterListListener;
import com.ebook.db.entity.BookContent;
import com.ebook.db.entity.BookShelf;
import com.ebook.db.entity.SearchBook;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;

public class WebBookModelImpl implements IWebBookModel {

    public static WebBookModelImpl getInstance() {
        return new WebBookModelImpl();
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * 网络请求并解析书籍信息
     * return BookShelf
     */
    @Override
    public Observable<BookShelf> getBookInfo(BookShelf bookShelf) {
        if (bookShelf.getTag().equals(GxwztvBookModelImpl.TAG)) {
            return GxwztvBookModelImpl.getInstance().getBookInfo(bookShelf);
        } else {
            return null;
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * 网络解析图书目录
     * return BookShelf
     */
    @Override
    public void getChapterList(final BookShelf bookShelf, OnGetChapterListListener getChapterListListener) {
        if (bookShelf.getTag().equals(GxwztvBookModelImpl.TAG)) {
            GxwztvBookModelImpl.getInstance().getChapterList(bookShelf, getChapterListListener);
        } else {
            if (getChapterListListener != null)
                getChapterListListener.success(bookShelf);
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * 章节缓存
     */
    @Override
    public Observable<BookContent> getBookContent(String durChapterUrl, int durChapterIndex, String tag) {
        if (tag.equals(GxwztvBookModelImpl.TAG)) {
            return GxwztvBookModelImpl.getInstance().getBookContent(durChapterUrl, durChapterIndex);
        } else
            return Observable.create(new ObservableOnSubscribe<BookContent>() {
                @Override
                public void subscribe(ObservableEmitter<BookContent> e) throws Exception {
                    e.onNext(new BookContent());
                    e.onComplete();
                }
            });
    }

    /**
     * 其他站点集合搜索
     */
    @Override
    public Observable<List<SearchBook>> searchOtherBook(String content, int page, String tag) {
        if (tag.equals(GxwztvBookModelImpl.TAG)) {
            return GxwztvBookModelImpl.getInstance().searchBook(content, page);
        } else {
            return Observable.create(new ObservableOnSubscribe<List<SearchBook>>() {
                @Override
                public void subscribe(ObservableEmitter<List<SearchBook>> e) throws Exception {
                    e.onNext(new ArrayList<SearchBook>());
                    e.onComplete();
                }
            });
        }
    }

    /**
     * 获取分类书籍
     */
    @Override
    public Observable<List<SearchBook>> getKindBook(String url, int page) {
        return GxwztvBookModelImpl.getInstance().getKindBook(url, page);
    }
}
