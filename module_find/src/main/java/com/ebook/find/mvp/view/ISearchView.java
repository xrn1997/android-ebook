
package com.ebook.find.mvp.view;

import android.widget.EditText;

import com.ebook.basebook.base.IView;
import com.ebook.db.entity.SearchBook;
import com.ebook.db.entity.SearchHistory;
import com.ebook.find.mvp.view.adapter.SearchBookAdapter;


import java.util.List;

public interface ISearchView extends IView {

    /**
     * 成功 新增查询记录
     *
     * @param searchHistory
     */
    void insertSearchHistorySuccess(SearchHistory searchHistory);

    /**
     * 成功搜索 搜索记录
     *
     * @param datas
     */
    void querySearchHistorySuccess(List<SearchHistory> datas);

    /**
     * 首次查询成功 更新UI
     *
     * @param books
     */
    void refreshSearchBook(List<SearchBook> books);

    /**
     * 加载更多书籍成功 更新UI
     *
     * @param books
     */
    void loadMoreSearchBook(List<SearchBook> books);

    /**
     * 刷新成功
     *
     * @param isAll
     */
    void refreshFinish(Boolean isAll);

    /**
     * 加载成功
     *
     * @param isAll
     */
    void loadMoreFinish(Boolean isAll);

    /**
     * 搜索失败
     *
     * @param isRefresh
     */
    void searchBookError(Boolean isRefresh);

    /**
     * 获取搜索内容EditText
     *
     * @return
     */
    EditText getEdtContent();

    /**
     * 添加书籍失败
     *
     * @param code
     */
    void addBookShelfFailed(int code);

    SearchBookAdapter getSearchBookAdapter();

    void updateSearchItem(int index);

    /**
     * 判断书籍是否已经在书架上
     *
     * @param searchBook
     * @return
     */
    Boolean checkIsExist(SearchBook searchBook);
}
