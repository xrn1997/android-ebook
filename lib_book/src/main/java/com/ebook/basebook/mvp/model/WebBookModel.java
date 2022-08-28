package com.ebook.basebook.mvp.model;

import android.content.Context;

import com.ebook.basebook.cache.ACache;
import com.ebook.db.entity.BookContent;
import com.ebook.db.entity.BookShelf;
import com.ebook.db.entity.Library;
import com.ebook.db.entity.SearchBook;
import com.ebook.db.entity.WebChapter;

import java.util.List;

import io.reactivex.Observable;

public interface WebBookModel {
    /**
     * 网络请求并解析书籍信息
     */
    Observable<BookShelf> getBookInfo(final BookShelf bookShelf);

    /**
     * 网络解析图书目录
     */
    Observable<WebChapter<BookShelf>> getChapterList(final BookShelf bookShelf);

    /**
     * 章节缓存
     */
    Observable<BookContent> getBookContent(final String durChapterUrl, final int durChapterIndex);

    /**
     * 获取分类书籍
     */
    Observable<List<SearchBook>> getKindBook(Context context, String url, int page);

    /**
     * 获取主页信息
     */
    Observable<Library> getLibraryData(ACache aCache);

    /**
     * 解析主页数据
     */
    Observable<Library> analyzeLibraryData(String data);

    /**
     * 搜索书籍
     */
    Observable<List<SearchBook>> searchBook(String content, int page);

}
