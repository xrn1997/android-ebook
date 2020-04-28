
package com.ebook.book.mvp.presenter;

import com.ebook.common.mvp.base.IPresenter;
import com.ebook.db.entity.SearchBook;

public interface IChoiceBookPresenter extends IPresenter{

    int getPage();

    void initPage();

    void toSearchBooks(String key);

    void addBookToShelf(final SearchBook searchBook);

    String getTitle();
}