package com.ebook.db.entity;

public class LibraryNewBook {
    private String name;
    private String url;
    private String tag;
    private String origin;

    public LibraryNewBook(String name, String url, String tag, String orgin) {
        this.name = name;
        this.url = url;
        this.tag = tag;
        this.origin = orgin;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
