
package com.ebook.basebook.mvp.presenter;

import android.app.Activity;

import com.ebook.basebook.base.IPresenter;
import com.ebook.basebook.mvp.presenter.impl.ReadBookPresenterImpl;
import com.ebook.basebook.view.BookContentView;
import com.ebook.db.entity.BookShelf;

public interface IBookReadPresenter extends IPresenter{

    int getOpen_from();

    BookShelf getBookShelf();

    void initContent();

    void loadContent(BookContentView bookContentView, long bookTag, final int chapterIndex, final int page);

    void updateProgress(int chapterIndex, int pageIndex);

    void saveProgress();

    String getChapterTitle(int chapterIndex);

    void setPageLineCount(int pageLineCount);

    void addToShelf(final ReadBookPresenterImpl.OnAddListner addListner);

    Boolean getAdd();

    void initData(Activity activity);

    void openBookFromOther(Activity activity);
}
