package com.ebook.source.analyze

/**
 * 按 `tag`（书源归属 URL）找不到 parser 时抛出：书源已被用户删除，或该 tag 本就是脏数据。
 *
 * 继承 [IllegalStateException] 而不是新建异常体系：调用方（书架刷新、正文获取、下载）本来就
 * 在「拿不到可用书源就无法继续」的分支上，异常类型只用于把根因说清楚，不参与控制流分发。
 *
 * 与 `lib_book_common` 的 `BookSourceManager.getParserFor` 返回 null 的关系：null 是「查不到」这个事实，抛不抛由调用方定；
 * 本地书的 `tag`（`loc_book`）永远查不到行，**不是**本异常的场景，调用方应在拿到 null 前先排除本地书。
 *
 * [sourceUrl] 为空白时语义不同：那不是「源被删了」，而是「这本书压根没有书源信息」（脏数据），
 * 消息因此换成一句读得懂的人话——照旧拼「书源已失效：」会在冒号后空无一物，日志里看不出根因。
 * 消息只是排查线索，从不上屏：它带着内部 URL，用户可见文案一律由各模块的字符串资源经
 * `reportFailure` 给出（如 `R.string.book_source_invalid`）。
 *
 * @param sourceUrl 这本书在书架上的归属（`book_shelf.tag`）；空白表示没有归属（脏数据）。
 * @param detail 可选的现场描述（如 `bookId=xxx`）。空白 [sourceUrl] 本身指不出是哪本书，
 *   日志需要锚点；已有调用方不传该参数时，消息与行为与之前**逐字一致**。
 */
class BookSourceNotFoundException(
    val sourceUrl: String,
    detail: String? = null,
) : IllegalStateException(
    (if (sourceUrl.isBlank()) "这本书没有书源信息" else "书源已失效：$sourceUrl") +
        if (detail == null) "" else "（$detail）"
)
