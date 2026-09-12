package com.ebook.api.service.comment

import com.xrn1997.common.dto.RespDTO
import com.ebook.api.entity.Comment
import com.ebook.api.entity.CommentMigrateResponse
import com.ebook.api.entity.CommentPage

/**
 * 评论数据源接口（真实后端与 mock 的共同抽象）。
 *
 * M2 改动：
 * - [getComments] 替代旧 `getChapterComments`：按聚合键列表查询（并集）
 * - 新增 [migrateMyComments]：用户迁移自己的旧键评论到新键
 */
interface CommentDataSource {
    //添加评论（登录；comment_key 必填，章节字段可选）
    suspend fun addComment(comment: Comment): RespDTO<Comment>

    //删除评论（登录，仅本人或管理员）
    suspend fun deleteComment(id: Long): RespDTO<Unit>

    //我的评论列表（登录，身份取自 token）
    suspend fun getMyComments(page: Int, pageSize: Int): RespDTO<CommentPage>

    //查询评论（M2：多键并集，替代旧 chapter_url 过滤）
    suspend fun getComments(
        commentKeys: List<String>,
        page: Int,
        pageSize: Int
    ): RespDTO<CommentPage>

    //迁移我的评论（M2：将当前用户的 oldKey 评论批量改到 newKey）
    suspend fun migrateMyComments(oldKey: String, newKey: String): RespDTO<CommentMigrateResponse>
}
