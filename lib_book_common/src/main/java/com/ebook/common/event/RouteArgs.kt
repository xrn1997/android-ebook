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

    /**
     * 恢复阅读的书籍 noteUrl（module_main 启动页 → module_book 阅读页）。
     *
     * 不复用 [NOTE_URL]：两者虽然值形相同，但所处路由与接收方语义都不是一回事
     * （一个是「打开修键面板、定位条目」，一个是「直接进阅读器、按此回查实体」）。
     * 共用一个 key 后接收方只能靠「自己在哪条路由上」反推含义，再加一处用途就会静默串味。
     *
     * 传 noteUrl 而不是整书实体，两条理由：
     * 1) 暂存区路线走不通——`BitIntentDataManager` 的进程内暂存区在 module_book 内，
     *    启动页（module_main）拿不到它；
     * 2) 即使改走 Bundle 直传 Parcelable 也不划算：`chapterList` 动辄上千条，
     *    撞上 binder 的 1MB 事务上限就是 `TransactionTooLargeException`，
     *    且这类崩溃只在长篇书上复现、短篇测不出来。按 key 现取还顺带拿到
     *    「这本书是否已被用户删掉」的最新事实（阅读器据此决定是开还是收摊）。
     */
    const val RESUME_NOTE_URL = "resumeNoteUrl"
}
