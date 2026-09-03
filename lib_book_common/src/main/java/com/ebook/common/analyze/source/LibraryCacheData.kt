package com.ebook.common.analyze.source

import com.ebook.db.entity.LibraryEntity
import com.ebook.db.entity.LibraryKindBookListEntity
import com.ebook.db.entity.SearchBookEntity
import kotlinx.serialization.Serializable

/**
 * 书库缓存数据（用于序列化/反序列化）
 */
@Serializable
data class LibraryCacheData(
    val kindBooks: List<KindBookCacheData> = emptyList()
) {
    /**
     * 转换为 LibraryEntity 实体
     */
    fun toLibrary(): LibraryEntity {
        val library = LibraryEntity()
        library.kindBooks = kindBooks.map { it.toLibraryKindBookList() }
        library.libraryNewBooks = emptyList()
        return library
    }

    companion object {
        /**
         * 从 LibraryEntity 实体创建
         */
        fun fromLibrary(library: LibraryEntity): LibraryCacheData {
            return LibraryCacheData(
                kindBooks = library.kindBooks?.map { KindBookCacheData.fromLibraryKindBookList(it) } ?: emptyList()
            )
        }
    }
}

/**
 * 分类书籍缓存数据
 */
@Serializable
data class KindBookCacheData(
    val kindName: String = "",
    val kindUrl: String = "",
    val books: List<SearchBookCacheData> = emptyList()
) {
    fun toLibraryKindBookList(): LibraryKindBookListEntity {
        return LibraryKindBookListEntity(
            kindName,
            kindUrl,
            books.map { it.toSearchBook() }
        )
    }

    companion object {
        fun fromLibraryKindBookList(kindBook: LibraryKindBookListEntity): KindBookCacheData {
            return KindBookCacheData(
                kindName = kindBook.kindName,
                kindUrl = kindBook.kindUrl,
                books = kindBook.books.map { SearchBookCacheData.fromSearchBook(it) }
            )
        }
    }
}

/**
 * 搜索书籍缓存数据
 */
@Serializable
data class SearchBookCacheData(
    val name: String = "",
    val author: String = "",
    val noteUrl: String = "",
    val coverUrl: String = "",
    val words: Long = 0,
    val state: String = "",
    val lastChapter: String = "",
    val add: Boolean = false,
    val tag: String = "",
    val kind: String = "",
    val origin: String = "",
    val desc: String = ""
) {
    fun toSearchBook(): SearchBookEntity {
        return SearchBookEntity(
            name = this.name,
            author = this.author,
            noteUrl = this.noteUrl,
            coverUrl = this.coverUrl,
            words = this.words,
            state = this.state,
            lastChapter = this.lastChapter,
            add = this.add,
            tag = this.tag,
            kind = this.kind,
            origin = this.origin,
            desc = this.desc
        )
    }

    companion object {
        fun fromSearchBook(book: SearchBookEntity): SearchBookCacheData {
            return SearchBookCacheData(
                name = book.name,
                author = book.author,
                noteUrl = book.noteUrl,
                coverUrl = book.coverUrl,
                words = book.words,
                state = book.state,
                lastChapter = book.lastChapter,
                add = book.add,
                tag = book.tag,
                kind = book.kind,
                origin = book.origin,
                desc = book.desc
            )
        }
    }
}
