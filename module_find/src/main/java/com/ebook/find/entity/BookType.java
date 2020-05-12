package com.ebook.find.entity;

/**
 * 书籍类型类
 */
public class BookType {
    private String bookType;
    private String url;

    public BookType() {
    }

    public BookType(String bookType, String url) {
        this.bookType = bookType;
        this.url = url;
    }

    public String getBookType() {
        return bookType;
    }

    public void setBookType(String bookType) {
        this.bookType = bookType;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    @Override
    public String toString() {
        return "BookType{" +
                "bookType='" + bookType + '\'' +
                ", url='" + url + '\'' +
                '}';
    }
}
