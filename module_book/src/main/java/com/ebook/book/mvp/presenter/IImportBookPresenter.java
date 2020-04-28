
package com.ebook.book.mvp.presenter;

import com.ebook.common.mvp.base.IPresenter;

import java.io.File;
import java.util.List;

public interface IImportBookPresenter extends IPresenter{
    void searchLocationBook();

    void importBooks(List<File> books);
}
