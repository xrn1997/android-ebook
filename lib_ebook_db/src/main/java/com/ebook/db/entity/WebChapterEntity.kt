package com.ebook.db.entity

/**
 * 书源章节解析结果承载模型（`data` 为解析产物，`next` 标记是否还有下一页/下一章）。
 *
 * 非数据库持久化实体，仅为书源解析链路在内存中传递结果的传输模型。
 */
class WebChapterEntity<T>(
    var data: T,
    var next: Boolean,
)
