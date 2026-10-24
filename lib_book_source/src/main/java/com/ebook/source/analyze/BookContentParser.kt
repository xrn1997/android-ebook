package com.ebook.source.analyze

/**
 * 书源正文的**取文接缝**：两种出身都实现它，读取器只认这一面。
 *
 * 早先它只为脚本书源存在（原 `ScriptContentParser`）：脚本的求值件全是 `internal`，读取器
 * 跨模块转型拿不到，只好先给脚本一条显式接口；原生侧继续下转型 `JsoupBookParser` 并掏
 * `rule` 字段自己拼请求。于是同一个「取一章文本」在两侧各有一套：脚本可假、可测，原生
 * 必须真网络 + 真 Context，长期零测试，存储策略还在读取器里写了两份。
 *
 * 现在原生也满足同一面——[JsoupBookParser.fetchChapterText] 内部走 PageWalker 与同章分页判定，
 * 读取器只负责「存」（空正文不落盘、`\n` 切段、缩进归读取层），两种出身共用一份存储策略。
 *
 * 为什么是 public：读取器与其测试（`lib_book_common` 的 test source set）都要实现/消费它，
 * internal 跨模块不可见（同 `ChapterPageMatcher` 的先例）。
 *
 * **error mode 不合并**（ADR-0046）：实现方可抛任意类型化异常——原生把抓取失败裹成
 * 「章节内容解析失败」，脚本把 `ScriptRuleException` 子类原样抛出；读取器只记排查线索后
 * 原样上抛，不把「规则解不动」折成「该源已失效」。
 */
interface BookContentParser {

    /**
     * 抓取一章正文（纯网络解析，不碰存储）：按各自的翻页规则取全文并净化。
     *
     * 返回整章文本，段落以 `\n` 分隔；**未规范化**——缩进/换行统一归读取层的 `TextNormalizer`
     * （`lib_book_source` 不得反向依赖 lib_book_common）。
     * 空串是合法返回（调用方按失败处置、不落盘）。
     */
    suspend fun fetchChapterText(contentRef: String): String
}
