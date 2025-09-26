package com.ebook.db

import android.content.Context
import com.ebook.db.entity.BookContent
import com.ebook.db.entity.BookInfo
import com.ebook.db.entity.BookShelf
import com.ebook.db.entity.ChapterList
import com.ebook.db.entity.DownloadChapter
import com.ebook.db.entity.MyObjectBox
import com.ebook.db.entity.SearchHistory
import io.objectbox.Box
import io.objectbox.BoxStore

/**
 * db manager
 * @author xrn1997
 * @date 2021/6/14
 */
object ObjectBoxManager {
    lateinit var store: BoxStore
        private set

    /**
     * Application处初始化
     */
    @JvmStatic
    fun init(context: Context) {
        store = MyObjectBox.builder()
            .androidContext(context.applicationContext)
            .build()
    }

    /**
     * 泛型方法获取 Box 实例
     */
    inline fun <reified T> getBox(): Box<T> {
        return store.boxFor(T::class.java)
    }

    val bookShelfBox get() = getBox<BookShelf>()
    val bookContentBox get() = getBox<BookContent>()
    val bookInfoBox get() = getBox<BookInfo>()
    val chapterListBox get() = getBox<ChapterList>()
    val searchHistoryBox get() = getBox<SearchHistory>()
    val downloadChapterBox get() = getBox<DownloadChapter>()
}