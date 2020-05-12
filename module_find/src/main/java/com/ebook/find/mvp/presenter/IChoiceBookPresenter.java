
package com.ebook.find.mvp.presenter;

import com.ebook.basebook.base.IPresenter;
import com.ebook.db.entity.SearchBook;

public interface IChoiceBookPresenter extends IPresenter {

    int getPage();

    void initPage();

    void toSearchBooks(String key);

    void addBookToShelf(final SearchBook searchBook);

    String getTitle();
}