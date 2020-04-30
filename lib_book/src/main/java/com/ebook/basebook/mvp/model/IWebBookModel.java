package com.ebook.basebook.mvp.model;

import com.ebook.db.entity.BookContent;
import com.ebook.db.entity.BookShelf;
import com.ebook.db.entity.SearchBook;

import java.util.List;

import io.reactivex.Observable;

public interface IWebBookModel {
    /**
     * 网络请求并解析书籍信息
     */
    Observable<BookShelf> getBookInfo(final BookShelf bookShelf);

    /**
     * 网络解析图书目录
     */
    void getChapterList(final BookShelf bookShelf, OnGetChapterListListener getChapterListListener);

    /**
     * 章节缓存
     */
    Observable<BookContent> getBookContent(final String durChapterUrl, final int durChapterIndex, String tag);

    /**
     * 获取分类书籍
     */
    Observable<List<SearchBook>> getKindBook(String url, int page);
    /**
     * 其他站点资源整合搜索
     */
    Observable<List<SearchBook>> searchOtherBook(String content, int page, String tag);
}
