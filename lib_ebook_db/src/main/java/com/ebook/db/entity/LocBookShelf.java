package com.ebook.db.entity;

public class LocBookShelf {
    private Boolean isNew;
    private BookShelf bookShelf;

    public LocBookShelf(Boolean isNew, BookShelf bookShelf){
        this.isNew = isNew;
        this.bookShelf = bookShelf;
    }

    public Boolean getNew() {
        return isNew;
    }

    public void setNew(Boolean aNew) {
        isNew = aNew;
    }

    public BookShelf getBookShelf() {
        return bookShelf;
    }

    public void setBookShelf(BookShelf bookShelf) {
        this.bookShelf = bookShelf;
    }
}
