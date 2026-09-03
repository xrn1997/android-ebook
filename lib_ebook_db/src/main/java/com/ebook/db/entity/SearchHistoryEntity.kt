package com.ebook.db.entity

import android.os.Parcelable
import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import kotlinx.parcelize.Parcelize

/**
 * 一条本地搜索词记录（表 search_history），对应 CONTEXT.md 的「搜索历史」。
 *
 * 只服务搜索页的历史面板：按 [type] 分组展示全量、点选即发起快捷搜索，清除按类型整体清
 * （语义见 ADR-0005）。它是流水型数据，故用自增主键 [id] 而非自然键（见 ADR-0003），
 * "同一词条不重复记录"这件事不靠数据库约束，而由 SearchHistoryRepository 先经
 * SearchHistoryDao.findByTypeAndContent 精确查重、再带旧 id 覆盖写回来保证。
 *
 * 与包内其他实体一致实现 [Parcelable]（当前只在页内 Flow 里传递，不过 Intent）。
 */
@Parcelize
@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    /** 自增主键；0 表示由数据库分配新行，带值则是覆盖既有那一行（upsert 用） */
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    var id: Long = 0,
    /** 搜索类型，用于把不同入口的历史互相隔离（当前只有书籍搜索，值取 SearchViewModel.BOOK） */
    @ColumnInfo(name = "type")
    var type: Int = 0,
    /** 搜索词原文；面板展示与 upsert 查重都用它（查重为精确匹配，非子串匹配，见 ADR-0005） */
    @ColumnInfo(name = "content")
    var content: String = String(),
    /** 最近一次搜索该词条的毫秒时间戳：既是面板的排序键（date DESC），也是"重搜只更新时间戳"的落点 */
    @ColumnInfo(name = "date")
    var date: Long = 0,
) : Parcelable {
    /** 新建词条便捷构造：id 留 0，交给数据库分配自增主键 */
    constructor(type: Int, content: String, date: Long) : this(
        id = 0,
        type = type,
        content = content,
        date = date,
    )
}
