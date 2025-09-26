package com.ebook.db.entity

/**
 * 书城整体Data
 */
class Library {
    var libraryNewBooks: List<LibraryNewBook>? = null

    @JvmField
    var kindBooks: List<LibraryKindBookList>? = null
}
