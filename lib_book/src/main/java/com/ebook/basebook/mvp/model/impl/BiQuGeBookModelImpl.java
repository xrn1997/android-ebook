package com.ebook.basebook.mvp.model.impl;

import android.util.Log;

import com.ebook.api.config.BeQuGeApi;
import com.ebook.basebook.base.impl.MBaseModelImpl;
import com.ebook.basebook.cache.ACache;
import com.ebook.basebook.mvp.model.StationBookModel;
import com.ebook.db.entity.BookContent;
import com.ebook.db.entity.BookShelf;
import com.ebook.db.entity.Library;
import com.ebook.db.entity.SearchBook;
import com.ebook.db.entity.WebChapter;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.List;

import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.functions.Function;

/**
 * @author xrn1997
 * @date 2021/6/19
 */
public class BiQuGeBookModelImpl extends MBaseModelImpl implements StationBookModel {

    private volatile static BiQuGeBookModelImpl bookModel;

    public static BiQuGeBookModelImpl getInstance() {
        if (bookModel == null) {
            synchronized (BiQuGeBookModelImpl.class) {
                if (bookModel== null) {
                    bookModel = new BiQuGeBookModelImpl();
                }
            }
        }
        return bookModel;
    }
    @Override
    public Observable<List<SearchBook>> getKindBook(String url, int page) {
        return null;
    }


    @Override
    public Observable<Library> getLibraryData(ACache aCache) {
        return getRetrofitObject(BeQuGeApi.URL).create(BeQuGeApi.class).getLibraryData("").flatMap((Function<String, ObservableSource<Library>>) s -> {
            if (s.length() > 0 && aCache != null) {
                aCache.put("cache_library", s);
            }
            return analyzeLibraryData(s);
        });
    }

    @Override
    public Observable<Library> analyzeLibraryData(String data) {
        return Observable.create(e -> {
            Library result = new Library();
            Document doc = Jsoup.parse(data);
            //解析最新书籍
            Element contentE = doc.getElementsByClass("r").get(0);
            Log.e("解析结果", String.valueOf(contentE));
        });
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
    public Observable<WebChapter<BookShelf>> getChapterList(BookShelf bookShelf) {

        return null;
    }


    @Override
    public Observable<BookContent> getBookContent(String durChapterUrl, int durChapterIndex) {
        return null;
    }
}
