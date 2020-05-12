package com.ebook.db.entity;

import java.util.List;

public class ReadBookContent {
    private List<BookContent> bookContentList;
    private int pageIndex;

    public ReadBookContent(List<BookContent> bookContentList, int pageIndex) {
        this.bookContentList = bookContentList;
        this.pageIndex = pageIndex;
    }

    public List<BookContent> getBookContentList() {
        return bookContentList;
    }

    public void setBookContentList(List<BookContent> bookContentList) {
        this.bookContentList = bookContentList;
    }

    public int getPageIndex() {
        return pageIndex;
    }

    public void setPageIndex(int pageIndex) {
        this.pageIndex = pageIndex;
    }
}
