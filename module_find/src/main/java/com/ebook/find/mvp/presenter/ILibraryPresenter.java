
package com.ebook.find.mvp.presenter;

import com.ebook.basebook.base.IPresenter;

import java.util.LinkedHashMap;

public interface ILibraryPresenter extends IPresenter{

    LinkedHashMap<String, String> getKinds();

    void getLibraryData();
}
