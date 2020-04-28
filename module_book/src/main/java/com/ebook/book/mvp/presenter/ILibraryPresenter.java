
package com.ebook.book.mvp.presenter;

import com.ebook.common.mvp.base.IPresenter;

import java.util.LinkedHashMap;

public interface ILibraryPresenter extends IPresenter{

    LinkedHashMap<String, String> getKinds();

    void getLibraryData();
}
