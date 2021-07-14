package com.ebook.main.entity;


public enum MainChannel {
    BOOKSHELF(0, "BOOKSHELF"), BOOKSTORE(1, "BOOKSTORE"), ME(2, "ME");
    public int id;
    public String name;

    MainChannel(int id, String name) {
        this.id = id;
        this.name = name;
    }
}
