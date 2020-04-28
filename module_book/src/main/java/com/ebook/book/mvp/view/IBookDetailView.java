
package com.ebook.book.mvp.view;

import com.ebook.common.mvp.base.IView;

public interface IBookDetailView extends IView{
    /**
     * 更新书籍详情UI
     */
    void updateView();

    /**
     * 数据获取失败
     */
    void getBookShelfError();
}
