
package com.ebook.book.mvp.view;

import com.ebook.common.mvp.base.IView;
import com.ebook.db.entity.Library;

public interface ILibraryView extends IView{

    /**
     * 书城书籍获取成功  更新UI
     * @param library
     */
    void updateUI(Library library);

    /**
     * 书城数据刷新成功 更新UI
     */
    void finishRefresh();
}
