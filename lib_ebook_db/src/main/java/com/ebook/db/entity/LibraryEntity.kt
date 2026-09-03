package com.ebook.db.entity

import android.os.Parcelable
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

/**
 * 书城整体数据（书源解析后的书库承载模型）。
 *
 * 非数据库持久化实体（不在 [com.ebook.db.AppDatabase] 的 `@Entity` 列表），仅为书城书库数据的内存传输模型。
 */
@Parcelize
class LibraryEntity : Parcelable {
    // 元素类型非 Parcelable（LibraryNewBookEntity/LibraryKindBookListEntity 仅为内存传输模型），
    // 不参与 Parcel 序列化（本实体也从不经 Intent/Bundle 传递，仅在内存使用）
    @IgnoredOnParcel
    var libraryNewBooks: List<LibraryNewBookEntity>? = null

    @IgnoredOnParcel
    var kindBooks: List<LibraryKindBookListEntity>? = null
}
