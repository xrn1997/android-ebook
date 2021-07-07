package com.ebook.basebook.mvp.model.impl;

import com.ebook.basebook.base.impl.MBaseModelImpl;
import com.ebook.basebook.cache.ACache;
import com.ebook.basebook.mvp.model.OnGetChapterListListener;
import com.ebook.basebook.mvp.model.StationBookModel;
import com.ebook.db.entity.BookContent;
import com.ebook.db.entity.BookShelf;
import com.ebook.db.entity.Library;
import com.ebook.db.entity.SearchBook;

import java.util.List;

import io.reactivex.Observable;

/**
 * @author xrn1997
 * @date 2021/6/19
 */
public class BiQuGeBookModelImpl extends MBaseModelImpl implements StationBookModel {

    public static BiQuGeBookModelImpl getInstance(){
        return new BiQuGeBookModelImpl();
    }

    @Override
    public Observable<List<SearchBook>> getKindBook(String url, int page) {
        return null;
    }

    @Override
    public Observable<Library> getLibraryData(ACache aCache) {
        return null;
    }

    @Override
    public Observable<Library> analyzeLibraryData(String data) {
        return null;
    }

    @Override
    public Observable<List<SearchBook>> searchBook(String content, int page) {
        return null;
    }

    @Override
    public Observable<BookShelf> getBookInfo(BookShelf bookShelf) {
        return null;
    }

    @Override
    public void getChapterList(BookShelf bookShelf, OnGetChapterListListener getChapterListListener) {

    }

    @Override
    public Observable<BookContent> getBookContent(String durChapterUrl, int durChapterIndex) {
        return null;
    }
}
