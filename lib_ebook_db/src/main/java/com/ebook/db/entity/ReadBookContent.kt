package com.ebook.db.entity

data class ReadBookContent(
    @JvmField var bookContentList: List<BookContent>,
    @JvmField var pageIndex: Int
)
