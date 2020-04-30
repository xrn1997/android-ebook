
package com.ebook.basebook.mvp.presenter;

import com.ebook.basebook.base.IPresenter;
import com.ebook.db.entity.BookShelf;
import com.ebook.db.entity.SearchBook;


public interface IBookDetailPresenter extends IPresenter {

    int getOpenfrom();

    SearchBook getSearchBook();

    BookShelf getBookShelf();

    Boolean getInBookShelf();

    void getBookShelfInfo();

    void addToBookShelf();

    void removeFromBookShelf();
}
