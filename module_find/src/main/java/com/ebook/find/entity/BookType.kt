package com.ebook.find.entity

/**
 * 书籍类型类
 */
data class BookType(
    @JvmField
    var bookType: String? = null,
    var url: String? = null
) {
    override fun toString(): String {
        return "BookType{" +
                "bookType='" + bookType + '\'' +
                ", url='" + url + '\'' +
                '}'
    }
}
