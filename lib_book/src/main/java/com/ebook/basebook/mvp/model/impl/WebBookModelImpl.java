package com.ebook.basebook.mvp.model.impl;


import com.ebook.api.config.IGxwztvApi;
import com.ebook.basebook.mvp.model.StationBookModel;
import com.ebook.basebook.mvp.model.WebBookModel;
import com.ebook.basebook.mvp.model.OnGetChapterListListener;
import com.ebook.db.entity.BookContent;
import com.ebook.db.entity.BookShelf;
import com.ebook.db.entity.SearchBook;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.Observable;

public class WebBookModelImpl implements WebBookModel {
    private final StationBookModel stationBookModel;

    public WebBookModelImpl(StationBookModel iStationBookModel) {
        this.stationBookModel = iStationBookModel;
    }

    public static WebBookModelImpl getInstance() {
        return new WebBookModelImpl(GxwztvBookModelImpl.getInstance());
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * 网络请求并解析书籍信息
     * return BookShelf
     */
    @Override
    public Observable<BookShelf> getBookInfo(BookShelf bookShelf) {
        if (bookShelf.getTag().equals(IGxwztvApi.URL)) {
            return stationBookModel.getBookInfo(bookShelf);
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
        if (bookShelf.getTag().equals(IGxwztvApi.URL)) {
            stationBookModel.getChapterList(bookShelf, getChapterListListener);
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
        if (tag.equals(IGxwztvApi.URL)) {
            return stationBookModel.getBookContent(durChapterUrl, durChapterIndex);
        } else
            return Observable.create(e -> {
                e.onNext(new BookContent());
                e.onComplete();
            });
    }

    /**
     * 其他站点集合搜索
     */
    @Override
    public Observable<List<SearchBook>> searchOtherBook(String content, int page, String tag) {
        if (tag.equals(IGxwztvApi.URL)) {
            return stationBookModel.searchBook(content, page);
        } else {
            return Observable.create(e -> {
                e.onNext(new ArrayList<>());
                e.onComplete();
            });
        }
    }

    /**
     * 获取分类书籍
     */
    @Override
    public Observable<List<SearchBook>> getKindBook(String url, int page) {
        return stationBookModel.getKindBook(url, page);
    }
}
