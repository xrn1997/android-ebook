
package com.ebook.book.mvp.model;


import com.ebook.db.entity.BookShelf;

public interface OnGetChapterListListener {
    public void success(BookShelf bookShelf);
    public void error();
}
