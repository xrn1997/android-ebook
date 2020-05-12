package com.ebook.main.entity;


public enum MainChannel {
    BOOKSHELF(0, "BOOKSHELF"), FiINDBOOK(1, "FINDBOOK"), ME(2, "ME");
    public int id;
    public String name;

    MainChannel(int id, String name) {
        this.id = id;
        this.name = name;
    }
}
