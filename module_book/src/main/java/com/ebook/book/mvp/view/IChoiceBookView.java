
package com.ebook.book.mvp.view;

import com.ebook.common.mvp.base.IView;
import com.ebook.db.entity.SearchBook;
import com.ebook.book.mvp.view.adapter.ChoiceBookAdapter;

import java.util.List;

public interface IChoiceBookView extends IView{

    void refreshSearchBook(List<SearchBook> books);

    void loadMoreSearchBook(List<SearchBook> books);

    void refreshFinish(Boolean isAll);

    void loadMoreFinish(Boolean isAll);

    void searchBookError();

    void addBookShelfSuccess(List<SearchBook> searchBooks);

    void addBookShelfFailed(int code);

    ChoiceBookAdapter getSearchBookAdapter();

    void updateSearchItem(int index);

    void startRefreshAnim();
}
