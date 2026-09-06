package com.ebook.common.analyze.local

/**
 * 章节正文读取的最小接缝（spec §7）。
 *
 * 从 [SourceReader] 拆出来的理由：网络书没有"源文件"，实现不了 `readMetadata` / `buildChapters`，
 * 但读取正文的 `readChapter` 与本地书完全同构。把"读一章"提成独立接口后，
 * `Map<BookFormat, ChapterReader>` 同时路由本地与网络来源，`SourceReader` 只在导入链路出现。
 */
interface ChapterReader {
    /**
     * 读取一章的正文内容。
     *
     * @param entry 章节索引（含 title 与 contentRef）
     * @param location 该书的内容仓库定位
     * @return 规范化后的段落数据，缺章时返回空段落列表
     */
    suspend fun readChapter(entry: ChapterEntry, location: BookLocation): ChapterContent
}
