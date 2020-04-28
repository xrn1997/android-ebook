package com.ebook.db.entity;

import java.util.List;

/**
 * 书城整体Data
 */
public class Library {
    private List<LibraryNewBook> libraryNewBooks;
    private List<LibraryKindBookList> kindBooks;

    public List<LibraryNewBook> getLibraryNewBooks() {
        return libraryNewBooks;
    }

    public void setLibraryNewBooks(List<LibraryNewBook> libraryNewBooks) {
        this.libraryNewBooks = libraryNewBooks;
    }

    public List<LibraryKindBookList> getKindBooks() {
        return kindBooks;
    }

    public void setKindBooks(List<LibraryKindBookList> kindBooks) {
        this.kindBooks = kindBooks;
    }
}
