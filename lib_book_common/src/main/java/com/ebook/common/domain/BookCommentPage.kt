package com.ebook.common.domain

/**
 * 评论分页查询结果（领域层页型）。
 *
 * 「响应 DTO 是传输层细节，不出本仓库层」——[com.ebook.api.entity.CommentPage] 的
 * items/total/page/page_size 在此收拢成调用方真正需要的两个字段。
 *
 * @property items 本页评论（未排序；合并与排序归调用方）
 * @property hasMore 是否可能还有下一页。判据是「返回条数达到请求的 pageSize 即视为可能还有」，
 *   **不依赖服务端 total**：精确整页且实际到头时，下一次加载会取回空页并得到 hasMore=false，
 *   多花一次空页请求换来对 total 口径的零信任（列表类接口的越界页软 404 / 计数漂移是见过的病）。
 */
data class BookCommentPage(
    val items: List<BookComment> = emptyList(),
    val hasMore: Boolean = false,
)
