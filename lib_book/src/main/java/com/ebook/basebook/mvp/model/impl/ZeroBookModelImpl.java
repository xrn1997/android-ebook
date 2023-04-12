package com.ebook.basebook.mvp.model.impl;

import static com.github.promeg.pinyinhelper.Pinyin.toPinyin;

import android.util.Log;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ebook.api.entity.BookEntity;
import com.ebook.api.service.ZeroBookService;
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
 * @date 2023/3/27
 */
public class ZeroBookModelImpl extends MBaseModelImpl implements StationBookModel {
    private volatile static ZeroBookModelImpl bookModel;
    private final String TAG = "bbzayy.com";

    public static ZeroBookModelImpl getInstance() {
        if (bookModel == null) {
            synchronized (ZeroBookModelImpl.class) {
                if (bookModel == null) {
                    bookModel = new ZeroBookModelImpl();
                }
            }
        }
        return bookModel;
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////
    @Override
    public Observable<List<SearchBook>> getKindBook(String url, int page) {
        int type = -1;
        switch (url) {
            case Url.xh_qh:
                type = 1;
                break;
            case Url.wx_xx:
                type = 2;
                break;
            case Url.ds_yq:
                type = 3;
                break;
            case Url.ls_js:
                type = 4;
                break;
            case Url.kh_ly:
                type = 5;
                break;
            case Url.wy_jj:
                type = 6;
                break;
            case Url.np:
                type = 7;
                break;
        }
        if (type == -1) {
            Log.e(TAG, "getKindBook: 网址错误");
            return null;
        }
        return getRetrofitObject(ZeroBookService.URL)
                .create(ZeroBookService.class)
                .getKindBooks("/sort/" + type + "/" + page + ".html")
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
                item.setTag(ZeroBookService.URL);
                item.setAuthor(kindBookEs.get(i).getElementsByClass("s4").text());
                item.setLastChapter(kindBookEs.get(i).getElementsByTag("a").get(1).text());
                item.setOrigin(TAG);
                item.setName(kindBookEs.get(i).getElementsByTag("a").get(0).text());
                item.setNoteUrl(kindBookEs.get(i).getElementsByTag("a").get(0).attr("href"));
                item.setCoverUrl(ZeroBookService.COVER_URL + "/" + toPinyin(item.getName(), "").toLowerCase() + ".jpg");
                books.add(item);
            }
            e.onNext(books);
            e.onComplete();
        });
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////
    @Override
    public Observable<Library> getLibraryData(ACache aCache) {
        return getRetrofitObject(ZeroBookService.URL).create(ZeroBookService.class).getLibraryData("").flatMap((Function<String, ObservableSource<Library>>) s -> {
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
            Elements newBookEs = doc
                    .getElementsByAttributeValue("class", "txt-list txt-list-row3")
                    .get(1)
                    .getElementsByClass("s2");
            List<LibraryNewBook> libraryNewBooks = new ArrayList<>();
            for (int i = 0; i < newBookEs.size(); i++) {
                Element itemE = newBookEs.get(i).getElementsByTag("a").get(0);
                LibraryNewBook item = new LibraryNewBook(itemE.text(), itemE.attr("href"), ZeroBookService.URL, TAG);
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
                kindItem.setKindUrl(ZeroBookService.URL + kindEs.get(0).getElementsByTag("a").get(i + 2).attr("href"));
                List<SearchBook> books = new ArrayList<>();
                Element firstBookE = kindContentEs.get(i).getElementsByClass("top").get(0);
                SearchBook firstBook = new SearchBook();
                firstBook.setTag(ZeroBookService.URL);
                firstBook.setOrigin(TAG);
                firstBook.setName(firstBookE.getElementsByTag("a").get(1).text());
                firstBook.setNoteUrl(firstBookE.getElementsByTag("a").get(0).attr("href"));
                firstBook.setCoverUrl(firstBookE.getElementsByTag("img").get(0).attr("src"));
                firstBook.setKind(kindItem.getKindName());
                books.add(firstBook);
                Elements otherBookEs = kindContentEs.get(i).getElementsByTag("li");
                for (int j = 0; j < otherBookEs.size(); j++) {
                    SearchBook item = new SearchBook();
                    item.setTag(ZeroBookService.URL);
                    item.setOrigin(TAG);
                    item.setKind(kindItem.getKindName());
                    item.setNoteUrl(otherBookEs.get(j).getElementsByTag("a").get(0).attr("href"));
                    item.setName(otherBookEs.get(j).getElementsByTag("a").get(0).text());
                    item.setCoverUrl(ZeroBookService.COVER_URL + "/" + toPinyin(item.getName(), "").toLowerCase() + ".jpg");
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
            return getRetrofitObject(ZeroBookService.SEARCH_URL)
                    .create(ZeroBookService.class)
                    .searchBook(str, page, "bbzayy_com")
                    .flatMap((Function<String, ObservableSource<List<SearchBook>>>) this::analyzeSearchBook);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }


    public Observable<List<SearchBook>> analyzeSearchBook(final String s) {
        return Observable.create(e -> {
            try {
                String json = s.substring(1, s.length() - 1);
                JSONObject jsonObject = JSONObject.parseObject(json);
                JSONArray jsonArray = jsonObject.getJSONArray("data");
                List<BookEntity> searchList = jsonArray.toJavaList(BookEntity.class);
                if (searchList.size() != 0) {
                    List<SearchBook> books = new ArrayList<>();
                    for (int i = 0; i < searchList.size(); i++) {
                        SearchBook item = new SearchBook();
                        item.setTag(ZeroBookService.URL);
                        item.setAuthor(searchList.get(i).getAuthor());
                        item.setLastChapter(searchList.get(i).getLastChapter());
                        item.setOrigin(TAG);
                        item.setName(searchList.get(i).getName());
                        item.setNoteUrl(ZeroBookService.URL + "/ldks/" + searchList.get(i).getId() + "/");
                        item.setCoverUrl(ZeroBookService.COVER_URL + "/" + toPinyin(item.getName(), "").toLowerCase() + ".jpg");
                        item.setDesc(searchList.get(i).getDesc());
                        books.add(item);
                    }
                    e.onNext(books);
                } else {
                    e.onNext(new ArrayList<>());
                }
            } catch (Exception ex) {
                ex.printStackTrace();
                e.onNext(new ArrayList<>());
            }
            e.onComplete();
        });
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////
    @Override
    public Observable<BookShelf> getBookInfo(BookShelf bookShelf) {
        return getRetrofitObject(ZeroBookService.URL)
                .create(ZeroBookService.class)
                .getBookInfo(bookShelf.getNoteUrl().replace(ZeroBookService.URL, ""))
                .flatMap((Function<String, ObservableSource<BookShelf>>) s -> analyzeBookInfo(s, bookShelf));
    }

    private Observable<BookShelf> analyzeBookInfo(String s, BookShelf bookShelf) {
        return Observable.create(e -> {
            bookShelf.setTag(ZeroBookService.URL);
            bookShelf.setBookInfo(analyzeBookInfo(s, bookShelf.getNoteUrl()));
            e.onNext(bookShelf);
            e.onComplete();
        });
    }

    private BookInfo analyzeBookInfo(String s, String novelUrl) {
        BookInfo bookInfo = new BookInfo();
        bookInfo.setNoteUrl(novelUrl);   //id
        bookInfo.setTag(ZeroBookService.URL);
        Document doc = Jsoup.parse(s);
        bookInfo.setName(doc.getElementsByClass("info").get(0).getElementsByTag("h1").get(0).text());
        bookInfo.setAuthor(doc.getElementsByClass("info").get(0).getElementsByTag("p").get(0).text().replace("作&nbsp;&nbsp;&nbsp;&nbsp;者：", ""));
        bookInfo.setIntroduce("\u3000\u3000" + doc.getElementsByAttributeValue("class", "desc xs-hidden").get(0).text());
        if (bookInfo.getIntroduce().equals("\u3000\u3000")) {
            bookInfo.setIntroduce("暂无简介");
        }
        bookInfo.setCoverUrl(ZeroBookService.COVER_URL + "/" + toPinyin(bookInfo.getName(), "").toLowerCase() + ".jpg");
        bookInfo.setChapterUrl(novelUrl);
        bookInfo.setOrigin(TAG);

        return bookInfo;
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////
    @Override
    public Observable<WebChapter<BookShelf>> getChapterList(BookShelf bookShelf) {
        return getRetrofitObject(ZeroBookService.URL)
                .create(ZeroBookService.class)
                .getChapterList(bookShelf.getBookInfo().getChapterUrl().replace(ZeroBookService.URL, ""))
                .flatMap((Function<String, ObservableSource<WebChapter<BookShelf>>>) s -> analyzeChapterList(s, bookShelf))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    private Observable<WebChapter<BookShelf>> analyzeChapterList(final String s, final BookShelf bookShelf) {
        return Observable.create(e -> {
            bookShelf.setTag(ZeroBookService.URL);
            WebChapter<List<ChapterList>> temp = analyzeChapterList(s, bookShelf.getNoteUrl());
            bookShelf.getBookInfo().setChapterlist(temp.getData());
            e.onNext(new WebChapter<>(bookShelf, temp.getNext()));
            e.onComplete();
        });
    }

    private WebChapter<List<ChapterList>> analyzeChapterList(String s, String novelUrl) {

        Document doc = Jsoup.parse(s);
        Elements chapterList = doc.getElementsByClass("section-list fix").get(1).getElementsByTag("li");
        List<ChapterList> chapters = new ArrayList<>();
        for (int i = 0; i < chapterList.size(); i++) {
            ChapterList temp = new ChapterList();
            temp.setDurChapterUrl(ZeroBookService.URL + chapterList.get(i).getElementsByTag("a").attr("href"));   //id
            Log.d(TAG, "analyzeChapterList: " + temp.getDurChapterUrl());
            temp.setDurChapterIndex(i);
            temp.setDurChapterName(chapterList.get(i).getElementsByTag("a").text());
            temp.setNoteUrl(novelUrl);
            temp.setTag(ZeroBookService.URL);

            chapters.add(temp);
        }
        return new WebChapter<>(chapters, false);
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////
    @Override
    public Observable<BookContent> getBookContent(String durChapterUrl, int durChapterIndex) {
        return getRetrofitObject(ZeroBookService.URL)
                .create(ZeroBookService.class)
                .getBookContent(durChapterUrl.replace(ZeroBookService.URL, ""))
                .flatMap((Function<String, ObservableSource<BookContent>>) s -> analyzeBookContent(s, durChapterUrl, durChapterIndex));
    }


    private Observable<BookContent> analyzeBookContent(final String s, final String durChapterUrl, final int durChapterIndex) {
        return Observable.create(e -> {
            BookContent bookContent = new BookContent();
            bookContent.setDurChapterIndex(durChapterIndex);
            bookContent.setDurChapterUrl(durChapterUrl);
            bookContent.setTag(ZeroBookService.URL);
            try {
                Document doc = Jsoup.parse(s);
                List<TextNode> contentEs = doc.getElementById("content").textNodes();
                StringBuilder content = new StringBuilder();
                for (int i = 0; i < contentEs.size(); i++) {
                    String temp = contentEs.get(i).text().trim();
                    temp = temp.replaceAll(" ", "").replaceAll(" ", "").replaceAll("\\s*", "");
                    if (temp.length() > 0) {
                        content.append("\u3000\u3000").append(temp);
                        if (i < contentEs.size() - 1) {
                            content.append("\r\n");
                        }
                    }
                }
                bookContent.setDurCapterContent(content.toString());
                bookContent.setRight(true);
            } catch (Exception ex) {
                ex.printStackTrace();
                ErrorAnalyContentManager.getInstance().writeNewErrorUrl(durChapterUrl);
                bookContent.setDurCapterContent(durChapterUrl.substring(0, durChapterUrl.indexOf('/', 8)) + "站点暂时不支持解析");
                bookContent.setRight(false);
            }
            e.onNext(bookContent);
            e.onComplete();
        });
    }
}
