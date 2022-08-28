package com.ebook.find.mvp.presenter;


import com.ebook.basebook.base.IPresenter;
import com.ebook.db.entity.SearchBook;

public interface ISearchPresenter extends IPresenter {

    Boolean getHasSearch();

    void setHasSearch(Boolean hasSearch);

    void insertSearchHistory();

    void querySearchHistory();

    void cleanSearchHistory();

    int getPage();

    void initPage();

    void toSearchBooks(String key, Boolean fromError);

    void addBookToShelf(final SearchBook searchBook);

    Boolean getInput();

    void setInput(Boolean input);
}
