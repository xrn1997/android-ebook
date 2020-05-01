
package com.ebook.basebook.mvp.model.impl;

import com.ebook.api.config.IGxwztvApi;
import com.ebook.basebook.cache.ACache;
import com.ebook.basebook.base.impl.MBaseModelImpl;
import com.ebook.basebook.base.manager.ErrorAnalyContentManager;
import com.ebook.basebook.mvp.model.IGxwztvBookModel;
import com.ebook.basebook.mvp.model.OnGetChapterListListener;
import com.ebook.basebook.observer.SimpleObserver;
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
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.ObservableSource;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;

public class GxwztvBookModelImpl extends MBaseModelImpl implements IGxwztvBookModel {
    public static final String TAG = "https://www.ztv.la";

    public static GxwztvBookModelImpl getInstance() {
        return new GxwztvBookModelImpl();
    }


    /**
     * 获取主页信息
     */
    @Override
    public Observable<Library> getLibraryData(final ACache aCache) {
        return getRetrofitObject(TAG).create(IGxwztvApi.class).getLibraryData("").flatMap(new Function<String, ObservableSource<Library>>() {
            @Override
            public ObservableSource<Library> apply(String s) throws Exception {
                if (s != null && s.length() > 0 && aCache != null) {
                    aCache.put("cache_library", s);
                }
                return analyLibraryData(s);
            }
        });
    }

    /**
     * 解析主页数据
     */
    @Override
    public Observable<Library> analyLibraryData(final String data) {
        return Observable.create(new ObservableOnSubscribe<Library>() {
            @Override
            public void subscribe(ObservableEmitter<Library> e) throws Exception {
                Library result = new Library();
                Document doc = Jsoup.parse(data);
                Element contentE = doc.getElementsByClass("container").get(0);
                //解析最新书籍
                Elements newBookEs = contentE.getElementsByClass("list-group-item text-nowrap modal-open");
                List<LibraryNewBook> libraryNewBooks = new ArrayList<LibraryNewBook>();
                for (int i = 0; i < newBookEs.size(); i++) {
                    Element itemE = newBookEs.get(i).getElementsByTag("a").get(0);
                    LibraryNewBook item = new LibraryNewBook(itemE.text(), TAG + itemE.attr("href"), TAG, "gxwztv.com");
                    libraryNewBooks.add(item);
                }
                result.setLibraryNewBooks(libraryNewBooks);
                //////////////////////////////////////////////////////////////////////
                List<LibraryKindBookList> kindBooks = new ArrayList<LibraryKindBookList>();
                //解析男频女频
                Elements hotEs = contentE.getElementsByClass("col-xs-12");
                for (int i = 1; i < hotEs.size(); i++) {
                    LibraryKindBookList kindItem = new LibraryKindBookList();
                    kindItem.setKindName(hotEs.get(i).getElementsByClass("panel-title").get(0).text());
                    Elements bookEs = hotEs.get(i).getElementsByClass("panel-body").get(0).getElementsByTag("li");

                    List<SearchBook> books = new ArrayList<SearchBook>();
                    for (int j = 0; j < bookEs.size(); j++) {
                        SearchBook searchBook = new SearchBook();
                        searchBook.setOrigin("gxwztv.com");
                        searchBook.setTag(TAG);
                        searchBook.setName(bookEs.get(j).getElementsByTag("span").get(0).text());
                        searchBook.setNoteUrl(TAG + bookEs.get(j).getElementsByTag("a").get(0).attr("href"));
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
                    kindItem.setKindUrl(TAG + kindEs.get(i).getElementsByClass("listMore").get(0).getElementsByTag("a").get(0).attr("href"));

                    List<SearchBook> books = new ArrayList<SearchBook>();
                    Element firstBookE = kindEs.get(i).getElementsByTag("dl").get(0);
                    SearchBook firstBook = new SearchBook();
                    firstBook.setTag(TAG);
                    firstBook.setOrigin("gxwztv.com");
                    firstBook.setName(firstBookE.getElementsByTag("a").get(1).text());
                    firstBook.setNoteUrl(TAG + firstBookE.getElementsByTag("a").get(0).attr("href"));
                    firstBook.setCoverUrl(firstBookE.getElementsByTag("a").get(0).getElementsByTag("img").get(0).attr("src"));
                    firstBook.setKind(kindItem.getKindName());
                    books.add(firstBook);

                    Elements otherBookEs = kindEs.get(i).getElementsByClass("book_textList").get(0).getElementsByTag("li");
                    for (int j = 0; j < otherBookEs.size(); j++) {
                        SearchBook item = new SearchBook();
                        item.setTag(TAG);
                        item.setOrigin("gxwztv.com");
                        item.setKind(kindItem.getKindName());
                        item.setNoteUrl(TAG + otherBookEs.get(j).getElementsByTag("a").get(0).attr("href"));
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
            }
        });
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////
    @Override
    public Observable<List<SearchBook>> searchBook(String content, int page) {
        return getRetrofitObject(TAG).create(IGxwztvApi.class).searchBook(content, page).flatMap(new Function<String, ObservableSource<List<SearchBook>>>() {
            @Override
            public ObservableSource<List<SearchBook>> apply(String s) throws Exception {
                return analySearchBook(s);
            }
        });
    }

    public Observable<List<SearchBook>> analySearchBook(final String s) {
        return Observable.create(new ObservableOnSubscribe<List<SearchBook>>() {
            @Override
            public void subscribe(ObservableEmitter<List<SearchBook>> e) throws Exception {
                try {
                    Document doc = Jsoup.parse(s);
                    Elements booksE = doc.getElementById("novel-list").getElementsByClass("list-group-item clearfix");
                    if (null != booksE && booksE.size() >= 2) {
                        List<SearchBook> books = new ArrayList<SearchBook>();
                        for (int i = 1; i < booksE.size(); i++) {
                            SearchBook item = new SearchBook();
                            item.setTag(TAG);
                            item.setAuthor(booksE.get(i).getElementsByClass("col-xs-2").get(0).text());
                            item.setKind(booksE.get(i).getElementsByClass("col-xs-1").get(0).text());
                            item.setLastChapter(booksE.get(i).getElementsByClass("col-xs-4").get(0).getElementsByTag("a").get(0).text());
                            item.setOrigin("gxwztv.com");
                            item.setName(booksE.get(i).getElementsByClass("col-xs-3").get(0).getElementsByTag("a").get(0).text());
                            item.setNoteUrl(TAG + booksE.get(i).getElementsByClass("col-xs-3").get(0).getElementsByTag("a").get(0).attr("href"));
                            item.setCoverUrl("noimage");
                            books.add(item);
                        }
                        e.onNext(books);
                    } else {
                        e.onNext(new ArrayList<SearchBook>());
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                    e.onNext(new ArrayList<SearchBook>());
                }
                e.onComplete();
            }
        });
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////
    @Override
    public Observable<BookShelf> getBookInfo(final BookShelf bookShelf) {
        return getRetrofitObject(TAG).create(IGxwztvApi.class).getBookInfo(bookShelf.getNoteUrl().replace(TAG, "")).flatMap(new Function<String, ObservableSource<BookShelf>>() {
            @Override
            public ObservableSource<BookShelf> apply(String s) throws Exception {
                return analyBookInfo(s, bookShelf);
            }
        });
    }

    private Observable<BookShelf> analyBookInfo(final String s, final BookShelf bookShelf) {
        return Observable.create(new ObservableOnSubscribe<BookShelf>() {
            @Override
            public void subscribe(ObservableEmitter<BookShelf> e) throws Exception {
                bookShelf.setTag(TAG);
                bookShelf.setBookInfo(analyBookinfo(s, bookShelf.getNoteUrl()));
                e.onNext(bookShelf);
                e.onComplete();
            }
        });
    }

    private BookInfo analyBookinfo(String s, String novelUrl) {
        BookInfo bookInfo = new BookInfo();
        bookInfo.setNoteUrl(novelUrl);   //id
        bookInfo.setTag(TAG);
        Document doc = Jsoup.parse(s);
        Element resultE = doc.getElementsByClass("panel panel-warning").get(0);
        bookInfo.setCoverUrl(resultE.getElementsByClass("panel-body").get(0).getElementsByClass("img-thumbnail").get(0).attr("src"));
        bookInfo.setName(resultE.getElementsByClass("active").get(0).text());
        bookInfo.setAuthor(resultE.getElementsByClass("col-xs-12 list-group-item no-border").get(0).getElementsByTag("small").get(0).text());
        Element introduceE = resultE.getElementsByClass("panel panel-default mt20").get(0);
        String introduce = "";
        if (introduceE.getElementById("all") != null) {
            introduce = introduceE.getElementById("all").text().replace("[收起]", "");
        } else {
            introduce = introduceE.getElementById("shot").text();
        }
        bookInfo.setIntroduce("\u3000\u3000" + introduce);
        bookInfo.setChapterUrl(TAG + resultE.getElementsByClass("list-group-item tac").get(0).getElementsByTag("a").get(0).attr("href"));
        bookInfo.setOrigin("gxwztv.com");
        return bookInfo;
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////
    @Override
    public void getChapterList(final BookShelf bookShelf, final OnGetChapterListListener getChapterListListener) {
        getRetrofitObject(TAG).create(IGxwztvApi.class).getChapterList(bookShelf.getBookInfo().getChapterUrl().replace(TAG, "")).flatMap(new Function<String, ObservableSource<WebChapter<BookShelf>>>() {
            @Override
            public ObservableSource<WebChapter<BookShelf>> apply(String s) throws Exception {
                return analyChapterList(s, bookShelf);
            }
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<WebChapter<BookShelf>>() {
                    @Override
                    public void onNext(WebChapter<BookShelf> value) {
                        if (getChapterListListener != null) {
                            getChapterListListener.success(value.getData());
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                        if (getChapterListListener != null) {
                            getChapterListListener.error();
                        }
                    }
                });
    }

    private Observable<WebChapter<BookShelf>> analyChapterList(final String s, final BookShelf bookShelf) {
        return Observable.create(new ObservableOnSubscribe<WebChapter<BookShelf>>() {
            @Override
            public void subscribe(ObservableEmitter<WebChapter<BookShelf>> e) throws Exception {
                bookShelf.setTag(TAG);
                WebChapter<List<ChapterList>> temp = analyChapterlist(s, bookShelf.getNoteUrl());
                bookShelf.getBookInfo().setChapterlist(temp.getData());
                e.onNext(new WebChapter<BookShelf>(bookShelf, temp.getNext()));
                e.onComplete();
            }
        });
    }

    private WebChapter<List<ChapterList>> analyChapterlist(String s, String novelUrl) {
        Document doc = Jsoup.parse(s);
        Elements chapterlist = doc.getElementById("chapters-list").getElementsByTag("a");
        List<ChapterList> chapters = new ArrayList<ChapterList>();
        for (int i = 0; i < chapterlist.size(); i++) {
            ChapterList temp = new ChapterList();
            temp.setDurChapterUrl(TAG + chapterlist.get(i).attr("href"));   //id
            temp.setDurChapterIndex(i);
            temp.setDurChapterName(chapterlist.get(i).text());
            temp.setNoteUrl(novelUrl);
            temp.setTag(TAG);

            chapters.add(temp);
        }
        Boolean next = false;
        return new WebChapter<List<ChapterList>>(chapters, next);
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////
    @Override
    public Observable<BookContent> getBookContent(final String durChapterUrl, final int durChapterIndex) {
        return getRetrofitObject(TAG).create(IGxwztvApi.class).getBookContent(durChapterUrl.replace(TAG, "")).flatMap(new Function<String, ObservableSource<BookContent>>() {
            @Override
            public ObservableSource<BookContent> apply(String s) throws Exception {
                return analyBookContent(s, durChapterUrl, durChapterIndex);
            }
        });
    }

    private Observable<BookContent> analyBookContent(final String s, final String durChapterUrl, final int durChapterIndex) {
        return Observable.create(new ObservableOnSubscribe<BookContent>() {
            @Override
            public void subscribe(ObservableEmitter<BookContent> e) throws Exception {
                BookContent bookContent = new BookContent();
                bookContent.setDurChapterIndex(durChapterIndex);
                bookContent.setDurChapterUrl(durChapterUrl);
                bookContent.setTag(TAG);
                try {
                    Document doc = Jsoup.parse(s);
                    List<TextNode> contentEs = doc.getElementById("txtContent").textNodes();
                    StringBuilder content = new StringBuilder();
                    for (int i = 0; i < contentEs.size(); i++) {
                        String temp = contentEs.get(i).text().trim();
                        temp = temp.replaceAll(" ", "").replaceAll(" ", "");
                        if (temp.length() > 0) {
                            content.append("\u3000\u3000" + temp);
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
                    bookContent.setDurCapterContent(durChapterUrl.substring(0, durChapterUrl.indexOf('/', 8)) + "站点暂时不支持解析，请反馈给Monke QQ:1105075896,半小时内解决，超级效率的程序员");
                    bookContent.setRight(false);
                }
                e.onNext(bookContent);
                e.onComplete();
            }
        });
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * 获取分类书籍
     */
    @Override
    public Observable<List<SearchBook>> getKindBook(String url, int page) {
        url = url + page + ".htm";
        return getRetrofitObject(GxwztvBookModelImpl.TAG).create(IGxwztvApi.class).getKindBooks(url.replace(GxwztvBookModelImpl.TAG, "")).flatMap(new Function<String, ObservableSource<List<SearchBook>>>() {
            @Override
            public ObservableSource<List<SearchBook>> apply(String s) throws Exception {
                return analySearchBook(s);
            }
        });
    }
}
