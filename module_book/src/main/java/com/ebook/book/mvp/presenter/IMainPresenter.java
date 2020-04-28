
package com.ebook.book.mvp.presenter;

import com.ebook.common.mvp.base.IPresenter;

public interface IMainPresenter extends IPresenter{
    void queryBookShelf(Boolean needRefresh);
}
