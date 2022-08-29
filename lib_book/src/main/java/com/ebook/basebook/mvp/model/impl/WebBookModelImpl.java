package com.ebook.basebook.mvp.model.impl;


import com.ebook.basebook.cache.ACache;
import com.ebook.basebook.mvp.model.StationBookModel;
import com.ebook.basebook.mvp.model.WebBookModel;
import com.ebook.db.entity.BookContent;
import com.ebook.db.entity.BookShelf;
import com.ebook.db.entity.Library;
import com.ebook.db.entity.SearchBook;
import com.ebook.db.entity.WebChapter;

import java.util.List;

import io.reactivex.Observable;

public class WebBookModelImpl implements WebBookModel {
    private volatile static WebBookModel bookModel;
    private final StationBookModel stationBookModel;

    public WebBookModelImpl(StationBookModel iStationBookModel) {
        this.stationBookModel = iStationBookModel;
    }

    public static WebBookModel getInstance() {
        if (bookModel == null) {
            synchronized (WebBookModelImpl.class) {
                if (bookModel == null) {
                    //更换书源只需要修改这一行
                    bookModel = new WebBookModelImpl(BiQuGeBookModelImpl.getInstance());
                }
            }
        }
        return bookModel;
    }

    @Override
    public Observable<BookShelf> getBookInfo(BookShelf bookShelf) {
        return stationBookModel.getBookInfo(bookShelf);
    }

    @Override
    public Observable<WebChapter<BookShelf>> getChapterList(final BookShelf bookShelf) {
        return stationBookModel.getChapterList(bookShelf);
    }

    @Override
    public Observable<BookContent> getBookContent(String durChapterUrl, int durChapterIndex) {
        return stationBookModel.getBookContent(durChapterUrl, durChapterIndex);
    }

    @Override
    public Observable<List<SearchBook>> getKindBook(String url, int page) {
        return stationBookModel.getKindBook(url, page);
    }

    @Override
    public Observable<Library> getLibraryData(ACache aCache) {
        return stationBookModel.getLibraryData(aCache);
    }

    @Override
    public Observable<Library> analyzeLibraryData(String data) {
        return stationBookModel.analyzeLibraryData(data);
    }

    @Override
    public Observable<List<SearchBook>> searchBook(String content, int page) {
        return stationBookModel.searchBook(content, page);
    }
}
