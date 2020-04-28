package com.ebook.db.entity;

import java.util.List;

/**
 * 书城 书籍分类推荐列表
 */
public class LibraryKindBookList {
    private String kindName;
    private String kindUrl;
    private List<SearchBook> books;

    public String getKindName() {
        return kindName;
    }

    public void setKindName(String kindName) {
        this.kindName = kindName;
    }

    public List<SearchBook> getBooks() {
        return books;
    }

    public void setBooks(List<SearchBook> books) {
        this.books = books;
    }

    public String getKindUrl() {
        return kindUrl;
    }

    public void setKindUrl(String kindUrl) {
        this.kindUrl = kindUrl;
    }
}
