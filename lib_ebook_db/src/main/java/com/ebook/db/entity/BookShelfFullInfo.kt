package com.ebook.db.entity

import androidx.room3.Embedded
import androidx.room3.Relation

/**
 * 书架完整信息（扁平化嵌套类）
 *
 * Room `@Relation` 关联查询投影，**非独立表**（不在 [com.ebook.db.AppDatabase] 的 `@Entity` 列表）：
 * 由 `@Embedded` 的书架行 + 关联的书籍信息、章节列表拼装而成。
 *
 * 包含书架基本信息、书籍信息、章节列表。
 * 访问路径：
 * - result.bookShelf.noteUrl — 书架信息
 * - result.info.name — 书名、作者等
 * - result.chapters[0].durChapterName — 章节名
 */
data class BookShelfFullInfo(
    @Embedded val bookShelf: BookShelfEntity,
    @Relation(
        entity = BookInfoEntity::class,
        parentColumns = ["note_url"],
        entityColumns = ["note_url"]
    )
    val info: BookInfoEntity?,
    @Relation(
        entity = ChapterListEntity::class,
        parentColumns = ["note_url"],
        entityColumns = ["note_url"]
    )
    val chapters: List<ChapterListEntity>
)
