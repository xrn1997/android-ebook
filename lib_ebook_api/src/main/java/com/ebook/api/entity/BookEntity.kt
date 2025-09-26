package com.ebook.api.entity


@Suppress("unused")
data class BookEntity(
    var id: String? = null,
    var name: String? = null,
    var author: String? = null,
    var img: String? = null,
    var desc: String? = null,
    var bookStatus: String? = null,
    var lastChapterId: String? = null,
    var lastChapter: String? = null,
    var cName: String? = null,
    var updateTime: String? = null
) {
    override fun toString(): String {
        return "BookEntity{" +
                "Id='" + id + '\'' +
                ", Name='" + name + '\'' +
                ", Author='" + author + '\'' +
                ", Img='" + img + '\'' +
                ", Desc='" + desc + '\'' +
                ", BookStatus='" + bookStatus + '\'' +
                ", LastChapterId='" + lastChapterId + '\'' +
                ", LastChapter='" + lastChapter + '\'' +
                ", CName='" + cName + '\'' +
                ", UpdateTime='" + updateTime + '\'' +
                '}'
    }
}
