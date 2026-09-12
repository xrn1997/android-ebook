package com.ebook.source.analyze

/**
 * 脚本书源的**正文抓取接缝**（lib_book_common 的 `JsoupSourceReader` 跨模块消费）。
 *
 * 为什么是独立接口而不是塞进 `BookParser`：`BookParser` 是「发现面」契约（搜索/详情/目录/分类/书库），
 * 正文住 `ruleContent` 且由**读取器**驱动落盘（章文件缓存、空正文不落盘都是读取器侧的策略）。
 * 原生链路里读取器向下转型 `JsoupBookParser` 拿规则；脚本格式的规则求值件全部 `internal`，
 * 跨模块转型拿不到——于是把「给我这章的文本」立成显式接缝，读取器按本接口分岔。
 *
 * 为什么是 public：读取器与其测试（lib_book_common 的 test source set）都要实现/消费它，
 * internal 跨模块不可见（同 `ChapterPageMatcher` 的先例）。
 */
interface ScriptContentParser {

    /**
     * 抓取一章正文（纯网络解析，不碰存储）：按 `ruleContent.content` 逐页取文、
     * `nextContentUrl` 翻页拼接（§7.2，不套用 `ChapterPageMatcher`）、`replaceRegex` 净化。
     *
     * 返回整章文本，段落以 `\n` 分隔；**未规范化**——缩进/换行统一归读取层的 `TextNormalizer`
     * （`lib_book_source` 不得反向依赖 lib_book_common，与原生路径同一分工）。
     * 空串是合法返回（调用方按失败处置、不落盘）。
     */
    suspend fun fetchChapterText(contentRef: String): String
}
