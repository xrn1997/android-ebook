package com.ebook.basebook.mvp.model.impl;

import android.util.Log;

import com.ebook.api.config.BeQuGeApi;
import com.ebook.api.config.IGxwztvApi;
import com.ebook.basebook.base.impl.MBaseModelImpl;
import com.ebook.basebook.cache.ACache;
import com.ebook.basebook.mvp.model.StationBookModel;
import com.ebook.db.entity.BookContent;
import com.ebook.db.entity.BookShelf;
import com.ebook.db.entity.Library;
import com.ebook.db.entity.LibraryKindBookList;
import com.ebook.db.entity.LibraryNewBook;
import com.ebook.db.entity.SearchBook;
import com.ebook.db.entity.WebChapter;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.functions.Function;

/**
 * @author xrn1997
 * @date 2021/6/19
 */
public class BiQuGeBookModelImpl extends MBaseModelImpl implements StationBookModel {
    private final String TAG = "xbiquge.la";
    private volatile static BiQuGeBookModelImpl bookModel;

    public static BiQuGeBookModelImpl getInstance() {
        if (bookModel == null) {
            synchronized (BiQuGeBookModelImpl.class) {
                if (bookModel == null) {
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
            Elements newBookEs = doc.getElementsByClass("r").get(1).getElementsByClass("s2");
            List<LibraryNewBook> libraryNewBooks = new ArrayList<>();
            for (int i = 0; i < newBookEs.size(); i++) {
                Element itemE = newBookEs.get(i).getElementsByTag("a").get(0);
                LibraryNewBook item = new LibraryNewBook(itemE.text(), itemE.attr("href"), BeQuGeApi.URL, TAG);
                libraryNewBooks.add(item);
            }
            result.setLibraryNewBooks(libraryNewBooks);
            //////////////////////////////////////////////////////////////////////
            //解析分类推荐
            List<LibraryKindBookList> kindBooks = new ArrayList<>();
            Elements kindContentEs = doc.getElementsByClass("content");
            Elements kindEs = doc.getElementsByClass("nav");
            for (int i = 0; i < kindContentEs.size(); i++) {
                LibraryKindBookList kindItem = new LibraryKindBookList();
                kindItem.setKindName(kindContentEs.get(i).getElementsByTag("h2").get(0).text());
                kindItem.setKindUrl(BeQuGeApi.URL + kindEs.get(0).getElementsByTag("a").get(i + 2).attr("href"));

                List<SearchBook> books = new ArrayList<>();
                Element firstBookE = kindContentEs.get(i).getElementsByClass("top").get(0);
                SearchBook firstBook = new SearchBook();
                firstBook.setTag(BeQuGeApi.URL);
                firstBook.setOrigin(TAG);
                firstBook.setName(firstBookE.getElementsByTag("a").get(0).text());
                firstBook.setNoteUrl(firstBookE.getElementsByTag("a").get(0).attr("href"));
                firstBook.setCoverUrl(firstBookE.getElementsByTag("img").get(0).attr("src"));
                firstBook.setKind(kindItem.getKindName());
                books.add(firstBook);

                Elements otherBookEs = kindContentEs.get(i).getElementsByTag("li");
                for (int j = 0; j < otherBookEs.size(); j++) {
                    SearchBook item = new SearchBook();
                    item.setTag(BeQuGeApi.URL);
                    item.setOrigin(TAG);
                    item.setKind(kindItem.getKindName());
                    item.setNoteUrl(otherBookEs.get(j).getElementsByTag("a").get(0).attr("href"));
                    String[] temp = item.getNoteUrl().split("/");
                    item.setCoverUrl(BeQuGeApi.URL + "/files/article/image/" + temp[temp.length - 2] + "/" + temp[temp.length - 1] + "/" + temp[temp.length - 1] + "s.jpg");
                    item.setName(otherBookEs.get(j).getElementsByTag("a").get(0).text());
                    books.add(item);
                }
                kindItem.setBooks(books);
                kindBooks.add(kindItem);
            }
            //////////////
            result.setKindBooks(kindBooks);
            e.onNext(result);
            e.onComplete();
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
