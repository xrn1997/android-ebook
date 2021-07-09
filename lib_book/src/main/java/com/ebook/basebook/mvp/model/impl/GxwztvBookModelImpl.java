
package com.ebook.basebook.mvp.model.impl;

import com.ebook.api.service.IGxwztvService;
import com.ebook.basebook.cache.ACache;
import com.ebook.basebook.base.impl.MBaseModelImpl;
import com.ebook.basebook.base.manager.ErrorAnalyContentManager;
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

import java.util.ArrayList;
import java.util.List;

import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;

public class GxwztvBookModelImpl extends MBaseModelImpl implements StationBookModel {
    private volatile static StationBookModel bookModel;

    public static StationBookModel getInstance() {
        if (bookModel == null) {
            synchronized (GxwztvBookModelImpl.class) {
                if (bookModel == null) {
                    bookModel = new GxwztvBookModelImpl();
                }
            }
        }
        return bookModel;
    }

    /**
     * 获取主页信息
     */
    @Override
    public Observable<Library> getLibraryData(final ACache aCache) {
        return getRetrofitObject(IGxwztvService.URL).create(IGxwztvService.class).getLibraryData("").flatMap((Function<String, ObservableSource<Library>>) s -> {
            if (s.length() > 0 && aCache != null) {
                aCache.put("cache_library", s);
            }
            return analyzeLibraryData(s);
        });
    }

    /**
     * 解析主页数据
     */
    @Override
    public Observable<Library> analyzeLibraryData(final String data) {
        return Observable.create(e -> {
            Library result = new Library();
            Document doc = Jsoup.parse(data);
            Element contentE = doc.getElementsByClass("container").get(0);
            //解析最新书籍
            Elements newBookEs = contentE.getElementsByClass("list-group-item text-nowrap modal-open");
            List<LibraryNewBook> libraryNewBooks = new ArrayList<>();
            for (int i = 0; i < newBookEs.size(); i++) {
                Element itemE = newBookEs.get(i).getElementsByTag("a").get(0);
                LibraryNewBook item = new LibraryNewBook(itemE.text(), IGxwztvService.URL + itemE.attr("href"), IGxwztvService.URL, "ztv.la");
                libraryNewBooks.add(item);
            }
            result.setLibraryNewBooks(libraryNewBooks);
            //////////////////////////////////////////////////////////////////////
            List<LibraryKindBookList> kindBooks = new ArrayList<>();
            //解析男频女频
            Elements hotEs = contentE.getElementsByClass("col-xs-12");
            for (int i = 1; i < hotEs.size(); i++) {
                LibraryKindBookList kindItem = new LibraryKindBookList();
                kindItem.setKindName(hotEs.get(i).getElementsByClass("panel-title").get(0).text());
                Elements bookEs = hotEs.get(i).getElementsByClass("panel-body").get(0).getElementsByTag("li");

                List<SearchBook> books = new ArrayList<>();
                for (int j = 0; j < bookEs.size(); j++) {
                    SearchBook searchBook = new SearchBook();
                    searchBook.setOrigin("ztv.la");
                    searchBook.setTag(IGxwztvService.URL);
                    searchBook.setName(bookEs.get(j).getElementsByTag("span").get(0).text());
                    searchBook.setNoteUrl(IGxwztvService.URL + bookEs.get(j).getElementsByTag("a").get(0).attr("href"));
                    searchBook.setCoverUrl(bookEs.get(j).getElementsByTag("img").get(0).attr("src"));
                    books.add(searchBook);
                }
                kindItem.setBooks(books);
                kindBooks.add(kindItem);
            }
            //解析部分分类推荐
            Elements kindEs = contentE.getElementsByClass("panel panel-info index-category-qk");
            for (int i = 0; i < kindEs.size(); i++) {
                LibraryKindBookList kindItem = new LibraryKindBookList();
                kindItem.setKindName(kindEs.get(i).getElementsByClass("panel-title").get(0).text());
                kindItem.setKindUrl(IGxwztvService.URL + kindEs.get(i).getElementsByClass("listMore").get(0).getElementsByTag("a").get(0).attr("href"));

                List<SearchBook> books = new ArrayList<>();
                Element firstBookE = kindEs.get(i).getElementsByTag("dl").get(0);
                SearchBook firstBook = new SearchBook();
                firstBook.setTag(IGxwztvService.URL);
                firstBook.setOrigin("ztv.la");
                firstBook.setName(firstBookE.getElementsByTag("a").get(1).text());
                firstBook.setNoteUrl(IGxwztvService.URL + firstBookE.getElementsByTag("a").get(0).attr("href"));
                firstBook.setCoverUrl(firstBookE.getElementsByTag("a").get(0).getElementsByTag("img").get(0).attr("src"));
                firstBook.setKind(kindItem.getKindName());
                books.add(firstBook);

                Elements otherBookEs = kindEs.get(i).getElementsByClass("book_textList").get(0).getElementsByTag("li");
                for (int j = 0; j < otherBookEs.size(); j++) {
                    SearchBook item = new SearchBook();
                    item.setTag(IGxwztvService.URL);
                    item.setOrigin("ztv.la");
                    item.setKind(kindItem.getKindName());
                    item.setNoteUrl(IGxwztvService.URL + otherBookEs.get(j).getElementsByTag("a").get(0).attr("href"));
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
        return getRetrofitObject(IGxwztvService.URL).create(IGxwztvService.class).searchBook(content, page).flatMap((Function<String, ObservableSource<List<SearchBook>>>) this::analyzeSearchBook);
    }

    public Observable<List<SearchBook>> analyzeSearchBook(final String s) {
        return Observable.create(e -> {
            try {
                Document doc = Jsoup.parse(s);
                Elements booksE = doc.getElementById("novel-list").getElementsByClass("list-group-item clearfix");
                if (null != booksE && booksE.size() >= 2) {
                    List<SearchBook> books = new ArrayList<>();
                    for (int i = 1; i < booksE.size(); i++) {
                        SearchBook item = new SearchBook();
                        item.setTag(IGxwztvService.URL);
                        item.setAuthor(booksE.get(i).getElementsByClass("col-xs-2").get(0).text());
                        item.setKind(booksE.get(i).getElementsByClass("col-xs-1").get(0).text());
                        item.setLastChapter(booksE.get(i).getElementsByClass("col-xs-4").get(0).getElementsByTag("a").get(0).text());
                        item.setOrigin("ztv.la");
                        item.setName(booksE.get(i).getElementsByClass("col-xs-3").get(0).getElementsByTag("a").get(0).text());
                        item.setNoteUrl(IGxwztvService.URL + booksE.get(i).getElementsByClass("col-xs-3").get(0).getElementsByTag("a").get(0).attr("href"));
                        item.setCoverUrl("noimage");
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
    public Observable<BookShelf> getBookInfo(final BookShelf bookShelf) {
        return getRetrofitObject(IGxwztvService.URL)
                .create(IGxwztvService.class)
                .getBookInfo(bookShelf.getNoteUrl().replace(IGxwztvService.URL, ""))
                .flatMap((Function<String, ObservableSource<BookShelf>>) s -> analyzeBookInfo(s, bookShelf));
    }

    private Observable<BookShelf> analyzeBookInfo(final String s, final BookShelf bookShelf) {
        return Observable.create(e -> {
            bookShelf.setTag(IGxwztvService.URL);
            bookShelf.setBookInfo(analyzeBookInfo(s, bookShelf.getNoteUrl()));
            e.onNext(bookShelf);
            e.onComplete();
        });
    }

    private BookInfo analyzeBookInfo(String s, String novelUrl) {
        BookInfo bookInfo = new BookInfo();
        bookInfo.setNoteUrl(novelUrl);   //id
        bookInfo.setTag(IGxwztvService.URL);
        Document doc = Jsoup.parse(s);
        Element resultE = doc.getElementsByClass("panel panel-warning").get(0);
        bookInfo.setCoverUrl(resultE.getElementsByClass("panel-body").get(0).getElementsByClass("img-thumbnail").get(0).attr("src"));
        bookInfo.setName(resultE.getElementsByClass("active").get(0).text());
        bookInfo.setAuthor(resultE.getElementsByClass("col-xs-12 list-group-item no-border").get(0).getElementsByTag("small").get(0).text());
        Element introduceE = resultE.getElementsByClass("panel panel-default mt20").get(0);
        String introduce;
        if (introduceE.getElementById("all") != null) {
            introduce = introduceE.getElementById("all").text().replace("[收起]", "");
        } else {
            introduce = introduceE.getElementById("shot").text();
        }
        bookInfo.setIntroduce("\u3000\u3000" + introduce);
        bookInfo.setChapterUrl(IGxwztvService.URL + resultE.getElementsByClass("list-group-item tac").get(0).getElementsByTag("a").get(0).attr("href"));
        bookInfo.setOrigin("ztv.la");
        return bookInfo;
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////
    @Override
    public Observable<WebChapter<BookShelf>> getChapterList(final BookShelf bookShelf) {
       return getRetrofitObject(IGxwztvService.URL).create(IGxwztvService.class).getChapterList(bookShelf.getBookInfo().getChapterUrl().replace(IGxwztvService.URL, "")).flatMap((Function<String, ObservableSource<WebChapter<BookShelf>>>) s -> analyzeChapterList(s, bookShelf))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    private Observable<WebChapter<BookShelf>> analyzeChapterList(final String s, final BookShelf bookShelf) {
        return Observable.create(e -> {
            bookShelf.setTag(IGxwztvService.URL);
            WebChapter<List<ChapterList>> temp = analyzeChapterList(s, bookShelf.getNoteUrl());
            bookShelf.getBookInfo().setChapterlist(temp.getData());
            e.onNext(new WebChapter<>(bookShelf, temp.getNext()));
            e.onComplete();
        });
    }

    private WebChapter<List<ChapterList>> analyzeChapterList(String s, String novelUrl) {
        Document doc = Jsoup.parse(s);
        Elements chapterList = doc.getElementById("chapters-list").getElementsByTag("a");
        List<ChapterList> chapters = new ArrayList<>();
        for (int i = 0; i < chapterList.size(); i++) {
            ChapterList temp = new ChapterList();
            temp.setDurChapterUrl(IGxwztvService.URL + chapterList.get(i).attr("href"));   //id
            temp.setDurChapterIndex(i);
            temp.setDurChapterName(chapterList.get(i).text());
            temp.setNoteUrl(novelUrl);
            temp.setTag(IGxwztvService.URL);

            chapters.add(temp);
        }
        return new WebChapter<>(chapters, false);
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////
    @Override
    public Observable<BookContent> getBookContent(final String durChapterUrl, final int durChapterIndex) {
        return getRetrofitObject(IGxwztvService.URL)
                .create(IGxwztvService.class)
                .getBookContent(durChapterUrl.replace(IGxwztvService.URL, ""))
                .flatMap((Function<String, ObservableSource<BookContent>>) s -> analyzeBookContent(s, durChapterUrl, durChapterIndex));
    }

    private Observable<BookContent> analyzeBookContent(final String s, final String durChapterUrl, final int durChapterIndex) {
        return Observable.create(e -> {
            BookContent bookContent = new BookContent();
            bookContent.setDurChapterIndex(durChapterIndex);
            bookContent.setDurChapterUrl(durChapterUrl);
            bookContent.setTag(IGxwztvService.URL);
            try {
                Document doc = Jsoup.parse(s);
                List<TextNode> contentEs = doc.getElementById("txtContent").textNodes();
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

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * 获取分类书籍
     */
    @Override
    public Observable<List<SearchBook>> getKindBook(String url, int page) {
        url = url + page + ".htm";
        return getRetrofitObject(IGxwztvService.URL).create(IGxwztvService.class).getKindBooks(url.replace(IGxwztvService.URL, "")).flatMap((Function<String, ObservableSource<List<SearchBook>>>) this::analyzeSearchBook);
    }
}
