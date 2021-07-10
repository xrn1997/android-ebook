
package com.ebook.basebook.mvp.presenter;

import com.ebook.basebook.base.IPresenter;

import java.io.File;
import java.util.List;

public interface IImportBookPresenter extends IPresenter {
    void searchLocationBook();

    void importBooks(List<File> books);

    /**
     * 停止扫描
     */
    void ScanCancel();

}
