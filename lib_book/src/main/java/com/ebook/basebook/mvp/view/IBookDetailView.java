
package com.ebook.basebook.mvp.view;

import com.ebook.basebook.base.IView;

public interface IBookDetailView extends IView {
    /**
     * 更新书籍详情UI
     */
    void updateView();

    /**
     * 数据获取失败
     */
    void getBookShelfError();
}
