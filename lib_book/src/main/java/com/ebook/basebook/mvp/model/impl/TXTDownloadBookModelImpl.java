package com.ebook.basebook.mvp.model.impl;

import android.content.Context;
import android.util.Log;

import com.ebook.api.service.TXTDownloadBookService;
import com.ebook.basebook.base.impl.MBaseModelImpl;
import com.ebook.basebook.base.manager.ErrorAnalyContentManager;
import com.ebook.basebook.cache.ACache;
import com.ebook.basebook.constant.Url;
import com.ebook.basebook.mvp.model.StationBookModel;
import com.ebook.db.entity.BookContent;
import com.ebook.db.entity.BookInfo;
import com.ebook.db.entity.BookShelf;
import com.ebook.db.entity.ChapterList;
import com.ebook.db.entity.Library;
import com.ebook.db.entity.LibraryKindBookList;
import com.ebook.db.entity.LibraryNewBook;
import com.ebook.db.entity.SearchBook;
import com.ebook.db.entity.WebChapter;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;

/**
 * @author xrn1997
 */
public class TXTDownloadBookModelImpl extends MBaseModelImpl implements StationBookModel {
    private final String TAG = "shuangliusc.com";
    private volatile static TXTDownloadBookModelImpl bookModel;

    public static TXTDownloadBookModelImpl getInstance() {
        if (bookModel == null) {
            synchronized (TXTDownloadBookModelImpl.class) {
                if (bookModel == null) {
                    bookModel = new TXTDownloadBookModelImpl();
                }
            }
        }
        return bookModel;
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////
    @Override
    public Observable<List<SearchBook>> getKindBook(Context context, String url, int page) {
        int type = switch (url) {
            case Url.xh -> 1;
            case Url.xz -> 2;
            case Url.ds -> 3;
            case Url.ls -> 4;
            case Url.wy -> 5;
            case Url.kh -> 6;
            case Url.qt -> 8;
            default -> -1;
        };
        if (type == -1) {
            Log.e(TAG, "getKindBook: 网址错误");
            return null;
        }
        return getRetrofitObject(TXTDownloadBookService.URL)
                .create(TXTDownloadBookService.class)
                .getKindBooks("/list/" + type + "_" + page + ".html")
                .flatMap((Function<String, ObservableSource<List<SearchBook>>>) this::analyzeKindBook);
    }

    private Observable<List<SearchBook>> analyzeKindBook(String s) {
        return Observable.create(e -> {
            Document doc = Jsoup.parse(s);
            //解析分类书籍
            Elements kindBookEs = doc.getElementsByAttributeValue("class", "txt-list txt-list-row5").get(0).getElementsByTag("li");
            List<SearchBook> books = new ArrayList<>();
            for (int i = 0; i < kindBookEs.size(); i++) {
                SearchBook item = new SearchBook();
                item.setTag(TXTDownloadBookService.URL);
                item.setAuthor(kindBookEs.get(i).getElementsByClass("s4").text());
                item.setLastChapter(kindBookEs.get(i).getElementsByTag("a").get(1).text());
                item.setOrigin(TAG);
                item.setName(kindBookEs.get(i).getElementsByTag("a").get(0).text());
                item.setNoteUrl(kindBookEs.get(i).getElementsByTag("a").get(0).attr("href"));
                String[] temp = item.getNoteUrl().split("/");
                char c;
                if (temp[temp.length - 1].length() == 4) {
                    c = temp[temp.length - 1].charAt(0);
                } else {
                    c = '0';
                }
                item.setCoverUrl(TXTDownloadBookService.COVER_URL + "/" + c + "/" + temp[temp.length - 1] + "/" + temp[temp.length - 1] + "s.jpg");
                books.add(item);
            }
            e.onNext(books);
            e.onComplete();
        });
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////
    @Override
    public Observable<Library> getLibraryData(ACache aCache) {
        return getRetrofitObject(TXTDownloadBookService.URL).create(TXTDownloadBookService.class).getLibraryData("").flatMap((Function<String, ObservableSource<Library>>) s -> {
            if (!s.isEmpty() && aCache != null) {
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
            Elements newBookEs = doc
                    .getElementsByAttributeValue("class", "txt-list txt-list-row3")
                    .get(1)
                    .getElementsByClass("s2");
            List<LibraryNewBook> libraryNewBooks = new ArrayList<>();
            for (int i = 0; i < newBookEs.size(); i++) {
                Element itemE = newBookEs.get(i).getElementsByTag("a").get(0);
                LibraryNewBook item = new LibraryNewBook(itemE.text(), itemE.attr("href"), TXTDownloadBookService.URL, TAG);
                libraryNewBooks.add(item);
            }
            result.setLibraryNewBooks(libraryNewBooks);
            //////////////////////////////////////////////////////////////////////
            //解析分类推荐
            List<LibraryKindBookList> kindBooks = new ArrayList<>();
            Elements kindContentEs = doc.getElementsByClass("tp-box");
            Elements kindEs = doc.getElementsByClass("nav");
            for (int i = 0; i < kindContentEs.size(); i++) {
                LibraryKindBookList kindItem = new LibraryKindBookList();
                kindItem.setKindName(kindContentEs.get(i).getElementsByTag("h2").get(0).text());
                kindItem.setKindUrl(TXTDownloadBookService.URL + kindEs.get(0).getElementsByTag("a").get(i + 2).attr("href"));
                List<SearchBook> books = new ArrayList<>();
                Element firstBookE = kindContentEs.get(i).getElementsByClass("top").get(0);
                SearchBook firstBook = new SearchBook();
                firstBook.setTag(TXTDownloadBookService.URL);
                firstBook.setOrigin(TAG);
                firstBook.setName(firstBookE.getElementsByTag("a").get(1).text());
                firstBook.setNoteUrl(firstBookE.getElementsByTag("a").get(0).attr("href"));
//                Log.e(TAG, "analyzeLibraryData: "+ZeroBookService.URL + firstBookE.getElementsByTag("img").get(0).attr("src") );
                firstBook.setCoverUrl(TXTDownloadBookService.URL + firstBookE.getElementsByTag("img").get(0).attr("src"));
                firstBook.setKind(kindItem.getKindName());
                books.add(firstBook);
                Elements otherBookEs = kindContentEs.get(i).getElementsByTag("li");
                for (int j = 0; j < otherBookEs.size(); j++) {
                    SearchBook item = new SearchBook();
                    item.setTag(TXTDownloadBookService.URL);
                    item.setOrigin(TAG);
                    item.setKind(kindItem.getKindName());
                    item.setNoteUrl(otherBookEs.get(j).getElementsByTag("a").get(0).attr("href"));
                    String[] temp = item.getNoteUrl().split("/");
                    //小说URL比较特殊，需要拼凑一下。
                    var bookNum = temp[temp.length - 1].strip();
                    var index = Math.max(bookNum.length() - 3, 0);
                    var c = index == 0 ? "0" : bookNum.substring(0, index);
                    item.setCoverUrl(TXTDownloadBookService.COVER_URL + "/" + c + "/" + temp[temp.length - 1] + "/" + temp[temp.length - 1] + "s.jpg");
//                    Log.e(TAG, "analyzeLibraryData: " + item.getCoverUrl());
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

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////
    @Override
    public Observable<List<SearchBook>> searchBook(String content, int page) {
        try {
            String str = URLEncoder.encode(content, "UTF-8");
            return getRetrofitObject(TXTDownloadBookService.URL)
                    .create(TXTDownloadBookService.class)
                    .searchBook(str)
                    .flatMap((Function<String, ObservableSource<List<SearchBook>>>) this::analyzeSearchBook);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }


    public Observable<List<SearchBook>> analyzeSearchBook(final String s) {
        return Observable.create(e -> {
            try {
                Document doc = Jsoup.parse(s);
                Elements booksE = doc.getElementsByAttributeValue("class", "txt-list txt-list-row5").get(0).getElementsByTag("li");
                //第一个为列表表头，所以如果有书booksE的size必定大于2
                if (null != booksE && booksE.size() >= 2) {
                    List<SearchBook> books = new ArrayList<>();
                    for (int i = 1; i < booksE.size(); i++) {
                        SearchBook item = new SearchBook();
                        item.setTag(TXTDownloadBookService.URL);
                        item.setAuthor(booksE.get(i).getElementsByClass("s4").get(0).text());
                        item.setLastChapter(booksE.get(i).getElementsByClass("s3").get(0).getElementsByTag("a").get(0).text());
                        item.setOrigin(TAG);
                        item.setName(booksE.get(i).getElementsByClass("s2").get(0).getElementsByTag("a").get(0).text());
                        item.setNoteUrl(booksE.get(i).getElementsByClass("s2").get(0).getElementsByTag("a").get(0).attr("href"));
                        String[] temp = item.getNoteUrl().split("/");
                        //小说URL比较特殊，需要拼凑一下。
                        var bookNum = temp[temp.length - 1].strip();
                        var index = Math.max(bookNum.length() - 3, 0);
                        var c = index == 0 ? "0" : bookNum.substring(0, index);
                        item.setCoverUrl(TXTDownloadBookService.COVER_URL + "/" + c + "/" + temp[temp.length - 1] + "/" + temp[temp.length - 1] + "s.jpg");
                        books.add(item);
                    }
                    e.onNext(books);
                } else {
                    e.onNext(new ArrayList<>());
                }
            } catch (Exception ex) {
                Log.e(TAG, "analyzeSearchBook: ", ex);
                e.onNext(new ArrayList<>());
            }
            e.onComplete();
        });
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////
    @Override
    public Observable<BookShelf> getBookInfo(BookShelf bookShelf) {
        return getRetrofitObject(TXTDownloadBookService.URL)
                .create(TXTDownloadBookService.class)
                .getBookInfo(bookShelf.getNoteUrl().replace(TXTDownloadBookService.URL, ""))
                .flatMap((Function<String, ObservableSource<BookShelf>>) s -> analyzeBookInfo(s, bookShelf));
    }

    private Observable<BookShelf> analyzeBookInfo(String s, BookShelf bookShelf) {
        return Observable.create(e -> {
            bookShelf.setTag(TXTDownloadBookService.URL);
            bookShelf.setBookInfo(analyzeBookInfo(s, bookShelf.getNoteUrl()));
            e.onNext(bookShelf);
            e.onComplete();
        });
    }

    private BookInfo analyzeBookInfo(String s, String novelUrl) {
        BookInfo bookInfo = new BookInfo();
        bookInfo.setNoteUrl(novelUrl);   //id
        bookInfo.setTag(TXTDownloadBookService.URL);
        Document doc = Jsoup.parse(s);
        bookInfo.setName(doc.getElementsByClass("info").get(0).getElementsByTag("h1").get(0).text());
        bookInfo.setAuthor(doc.getElementsByClass("info").get(0).getElementsByTag("p").get(0).text().replace("作&nbsp;&nbsp;&nbsp;&nbsp;者：", ""));
        bookInfo.setIntroduce("\u3000\u3000" + doc.getElementsByAttributeValue("class", "desc xs-hidden").get(0).text());
        if (bookInfo.getIntroduce().equals("\u3000\u3000")) {
            bookInfo.setIntroduce("暂无简介");
        }
        String[] temp = novelUrl.split("/");
        char c;
        if (temp[temp.length - 1].length() == 4) {
            c = temp[temp.length - 1].charAt(0);
        } else {
            c = '0';
        }
        bookInfo.setCoverUrl(TXTDownloadBookService.COVER_URL + "/" + c + "/" + temp[temp.length - 1] + "/" + temp[temp.length - 1] + "s.jpg");
        bookInfo.setChapterUrl(novelUrl);
        bookInfo.setOrigin(TAG);

        return bookInfo;
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////
    @Override
    public Observable<WebChapter<BookShelf>> getChapterList(BookShelf bookShelf) {
        return getRetrofitObject(TXTDownloadBookService.URL)
                .create(TXTDownloadBookService.class)
                .getChapterList(bookShelf.getBookInfo().getChapterUrl().replace(TXTDownloadBookService.URL, ""))
                .flatMap((Function<String, ObservableSource<WebChapter<BookShelf>>>) s -> analyzeChapterList(s, bookShelf))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    private Observable<WebChapter<BookShelf>> analyzeChapterList(final String s, final BookShelf bookShelf) {
        return Observable.create(e -> {
            bookShelf.setTag(TXTDownloadBookService.URL);
            WebChapter<List<ChapterList>> temp = analyzeChapterList(s, bookShelf.getNoteUrl());
            bookShelf.getBookInfo().setChapterlist(temp.getData());
            e.onNext(new WebChapter<>(bookShelf, temp.getNext()));
            e.onComplete();
        });
    }

    private WebChapter<List<ChapterList>> analyzeChapterList(String s, String novelUrl) {

        Document doc = Jsoup.parse(s);
        Elements chapterList = doc.getElementById("section-list").getElementsByTag("li");
        List<ChapterList> chapters = new ArrayList<>();
        for (int i = 0; i < chapterList.size(); i++) {
            ChapterList temp = new ChapterList();
            temp.setDurChapterUrl(TXTDownloadBookService.URL + chapterList.get(i).getElementsByTag("a").attr("href"));   //id
            temp.setDurChapterIndex(i);
            temp.setDurChapterName(chapterList.get(i).getElementsByTag("a").text());
            temp.setNoteUrl(novelUrl);
            temp.setTag(TXTDownloadBookService.URL);

            chapters.add(temp);
        }
        return new WebChapter<>(chapters, false);
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////
    @Override
    public Observable<BookContent> getBookContent(String durChapterUrl, int durChapterIndex) {
        return getRetrofitObject(TXTDownloadBookService.URL)
                .create(TXTDownloadBookService.class)
                .getBookContent(durChapterUrl.replace(TXTDownloadBookService.URL, ""))
                .flatMap((Function<String, ObservableSource<BookContent>>) s -> analyzeBookContent(s, durChapterUrl, durChapterIndex));
    }


    private Observable<BookContent> analyzeBookContent(final String s, final String durChapterUrl, final int durChapterIndex) {
        return Observable.create(e -> {
            BookContent bookContent = new BookContent();
            bookContent.setDurChapterIndex(durChapterIndex);
            bookContent.setDurChapterUrl(durChapterUrl);
            bookContent.setTag(TXTDownloadBookService.URL);
            try {
                Document doc = Jsoup.parse(s);
                List<TextNode> contentEs = doc.getElementById("content").textNodes();
                StringBuilder content = new StringBuilder();
                for (int i = 0; i < contentEs.size(); i++) {
                    String temp = contentEs.get(i).text().trim();
                    temp = temp.replaceAll(" ", "").replaceAll(" ", "").replaceAll("\\s*", "");
                    if (!temp.isEmpty()) {
                        content.append("\u3000\u3000").append(temp);
                        if (i < contentEs.size() - 1) {
                            content.append("\r\n");
                        }
                    }
                }
                bookContent.setDurCapterContent(content.toString());
                bookContent.setRight(true);
            } catch (Exception ex) {
                Log.e(TAG, "analyzeBookContent: ", ex);
                ErrorAnalyContentManager.getInstance().writeNewErrorUrl(durChapterUrl);
                bookContent.setDurCapterContent(durChapterUrl.substring(0, durChapterUrl.indexOf('/', 8)) + "站点暂时不支持解析");
                bookContent.setRight(false);
            }
            e.onNext(bookContent);
            e.onComplete();
        });
    }
}
