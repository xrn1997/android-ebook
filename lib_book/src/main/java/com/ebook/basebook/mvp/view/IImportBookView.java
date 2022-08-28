package com.ebook.basebook.mvp.view;

import com.ebook.basebook.base.IView;

import java.io.File;

public interface IImportBookView extends IView {

    /**
     * 新增书籍
     */
    void addNewBook(File newFile);

    /**
     * 书籍搜索完成
     */
    void searchFinish();

    /**
     * 添加成功
     */
    void addSuccess();

    /**
     * 添加失败
     */
    void addError();

}