package com.ebook.api.entity

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 评论 API 实体（对齐服务端评论契约：蛇形字段 + 章节冗余快照 + 分页包裹）。
 *
 * 双用途：创建评论请求体（[content] 必填，章节字段可选）+ 评论响应项。
 * 服务端载荷为蛇形命名，Kotlin 属性保持驼峰，边界翻译由 [SerialName] 完成：
 * - [commentKey] ↔ comment_key（评论聚合键，M2 新增）
 * - [chapterUrl]/[chapterName]/[bookName] ↔ chapter_url/chapter_name/book_name
 * - [content] ↔ content（内容字段，与旧契约的 comment 键不同）
 * - [addTime] ↔ add_time（Asia/Shanghai，yyyy-MM-dd HH:mm:ss）
 *
 * [user] 复用 [User] 实体解析服务端的评论用户视图（uid/username/nickname/avatar），
 * 服务端不返回 email，走默认值兜底。
 */
@Serializable
@Parcelize
data class Comment(
    var id: Long = 0L,
    @JvmField
    var user: User = User(),
    @SerialName("comment_key")
    var commentKey: String? = null, // 评论聚合键（M2）：ck1:hash 或 ck1:hash#章序号
    @SerialName("chapter_url")
    var chapterUrl: String? = null, //对应BookInfo noteUrl（已废弃，保留兼容）;
    @SerialName("chapter_name")
    var chapterName: String? = null, //当前章节名称
    @SerialName("book_name")
    var bookName: String? = null,
    @SerialName("content")
    var content: String? = null, //评论内容（创建评论必填）
    @SerialName("add_time")
    var addTime: String = ""
) : Parcelable
