
package com.ebook.book.mvp.view;

import com.ebook.common.mvp.base.IView;
import com.ebook.db.entity.BookShelf;

import java.util.List;

public interface IMainView extends IView{

    /**
     * 刷新书架书籍小说信息 更新UI
     * @param bookShelfList
     */
    void refreshBookShelf(List<BookShelf> bookShelfList);

    /**
     * 执行刷新书架小说信息
     */
    void activityRefreshView();

    /**
     * 刷新完成
     */
    void refreshFinish();

    /**
     * 刷新错误
     * @param error
     */
    void refreshError(String error);

    /**
     * 刷新书籍  UI进度修改
     */
    void refreshRecyclerViewItemAdd();

    /**
     * 设置刷新进度条最大值
     * @param x
     */
    void setRecyclerMaxProgress(int x);
}
