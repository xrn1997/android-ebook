package com.ebook.db.entity;

public class WebChapter<T> {
    private T data;

    private Boolean next;

    public WebChapter(T data, Boolean next) {
        this.data = data;
        this.next = next;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public Boolean getNext() {
        return next;
    }

    public void setNext(Boolean next) {
        this.next = next;
    }
}
