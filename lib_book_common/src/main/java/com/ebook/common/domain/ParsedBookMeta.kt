package com.ebook.common.domain

/**
 * 待导入作品的标题与作者——`comment_key` 的两个输入项（spec §9.1）。
 *
 * 放在域层而不是导入器包里，是因为它同时是导入侧（`LocalBookImporter.parseMetadata` 的产物）
 * 与判重侧（`DuplicateBookDetector.findMatchesFor` 的入参）的共同词汇：判重属于"这本书是谁"，
 * 不属于"文件怎么读"。
 *
 * 不含章节信息——切章是导入流水线的重活，判重只需要算出键。作者解不出时由导入侧填显示用
 * 占位词，占位词在 [CommentKey] 里归空，因此不会把「同一本无作者的书」拆成两个键。
 */
data class ParsedBookMeta(val title: String, val author: String)
