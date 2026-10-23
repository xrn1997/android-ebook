package com.ebook.source.analyze

/**
 * 按 `tag`（书源归属 URL）找不到可用 parser 时抛出：书源已被用户删除、tag 本就是脏数据，
 * 或原生规则行解不出来。
 *
 * 继承 [IllegalStateException] 而不是新建异常体系：调用方（书架刷新、正文获取、下载）本来就
 * 在「拿不到可用书源就无法继续」的分支上；该类型用于把根因说清楚，并让需要展示提示的调用方
 * 把它与其他解析失败分开。
 *
 * 与 `lib_book_common` 的 `SourceResolver.resolveSource` 的关系：`Missing`/`Broken` 是「查不到/行坏了」
 * 这个事实，抛不抛由调用方定；
 * 本地书的 `tag`（`loc_book`）由 `SourceBinding.Local` 单独表示，**不是**本异常的场景；
 * 经 `parserOrThrow` 时那一档会抛 `IllegalStateException`，调用方不必自己排除本地书。
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
