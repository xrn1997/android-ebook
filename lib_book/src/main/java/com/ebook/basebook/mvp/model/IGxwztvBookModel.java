
package com.ebook.basebook.mvp.model;

import com.ebook.basebook.cache.ACache;
import com.ebook.db.entity.Library;
import com.ebook.db.entity.SearchBook;


import java.util.List;

import io.reactivex.Observable;

public interface IGxwztvBookModel extends IStationBookModel {

    Observable<List<SearchBook>> getKindBook(String url, int page);

    /**
     * 获取主页信息
     */
    Observable<Library>getLibraryData(ACache aCache);

    /**
     * 解析主页数据
     */
    Observable<Library> analyLibraryData(String data);
}
