
package com.ebook.book.mvp.model;

import com.ebook.db.entity.BookContent;
import com.ebook.db.entity.BookShelf;
import com.ebook.db.entity.SearchBook;


import java.util.List;

import io.reactivex.Observable;

public interface IStationBookModel {

    /**
     * 搜索书籍
     */
    Observable<List<SearchBook>> searchBook(String content, int page);

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
    Observable<BookContent> getBookContent(final String durChapterUrl, final int durChapterIndex);
}
