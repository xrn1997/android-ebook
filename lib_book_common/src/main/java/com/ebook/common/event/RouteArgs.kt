package com.ebook.common.event

/**
 * 跨模块路由传参 key 常量。
 *
 * TheRouter 的 with(Bundle) 传参依赖字符串 key；跨模块共享时若各端写各自的
 * 字面量，改 key 编译期无感知、运行时静默丢参。所有跨模块 Bundle key 统一在此声明，
 * 发送方与接收方引用同一常量（如 module_me → module_book 的评论页跳转）。
 */
object RouteArgs {

    /** 章节 URL（module_book 评论区定位章节用，已废弃，保留兼容） */
    const val CHAPTER_URL = "chapterUrl"

    /** 章节名（module_book 评论区展示用） */
    const val CHAPTER_NAME = "chapterName"

    /** 书名（module_book 评论区展示用） */
    const val BOOK_NAME = "bookName"

    /** 评论聚合键（M2：章键或书键，评论区按此做并集查询） */
    const val COMMENT_KEY = "commentKey"

    /**
     * 写入用评论聚合键（M2，spec §9.2）：`is_primary` 那行的键。
     *
     * 与 [COMMENT_KEY]（读并集）分开传：并集列表的顺序不保证，新评论的归属键必须显式给出。
     */
    const val PRIMARY_COMMENT_KEY = "primaryCommentKey"

    /** 书籍 noteUrl（修键面板定位书架条目用，见 EditBookMetaActivity） */
    const val NOTE_URL = "noteUrl"
}
