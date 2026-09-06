package com.ebook.common.analyze.local

import com.ebook.common.text.TextNormalizer
import java.io.File
import kotlinx.coroutines.flow.Flow

/**
 * 书籍格式枚举。
 *
 * [TXT] / [EPUB] 是本地文件格式，可由 `fromExtension` 解析；[NETWORK] 是网络书的虚拟标记，
 * 没有对应的文件扩展名，`fromExtension` 对 "network" 返回 null——调用方不应把网络书当成
 * "未知格式"报错。[isFileBased] 区分这两种语义。
 *
 * 三个值各有对应 reader，装配见 `ContentStoreModule`：[TXT]→`TxtSourceReader`、
 * [EPUB]→`EpubSourceReader`、[NETWORK]→`JsoupSourceReader`。
 * `book_shelf.book_format` 存的是枚举名字，加枚举值不需要迁移。
 */
enum class BookFormat(val extension: String, val isFileBased: Boolean) {
    TXT("txt", true),
    EPUB("epub", true),
    NETWORK("network", false);

    companion object {
        /**
         * 按扩展名解析**本地文件格式**，未知扩展名或 `"network"` 返回 null，
         * 交给调用方报错而不是硬猜。网络书不走文件扩展名路由。
         */
        fun fromExtension(ext: String): BookFormat? =
            entries.firstOrNull { it.isFileBased && it.extension.equals(ext, ignoreCase = true) }
    }
}

/**
 * 一本书内容仓库的定位值类型（spec §4）。
 *
 * reader 只认它、不认 `File` 也不认 `Uri`：§13 的 SAF 迁移将来只换这个类型的构造方式，
 * 读取路径一行不动。[bookId] 同时是 `book_shelf.note_url`（本地书即内容 md5）与目录名。
 */
data class BookLocation(val bookId: String, val format: BookFormat)

/** 待导入的源文件：路径 + 已探测出的编码（探测一次即固化，见 spec §7） */
data class BookSourceFile(val file: File, val charset: String)

/**
 * 章节索引行，与 `chapter_list` 一一对应。
 * [contentRef] 是"正文在哪"：本地书为 `books/<bookId>/c00042.txt` 相对路径，
 * 网络书（M1b）为章节 URL（spec §4 §5 决定 3）。
 */
data class ChapterEntry(val index: Int, val title: String, val contentRef: String)

/**
 * 一章的内容。
 *
 * [paragraphs] 从 reader 出来时是**章文件里的原文**（存储层不清洗，spec §4）；
 * 规范化由读取管线在入缓存之前做一次（`BookRepository.loadChapter` → `TextNormalizer`），
 * 所以下游（分页渲染、段评锚点）拿到的始终是「规范化后的纯段落」，不含缩进等表现层字符——
 * 段评锚点建立在它之上（spec §9.1），掺进表现层会让锚点随渲染规则漂移。
 * [displayText] 才是给分页与渲染共用的文本（spec §8）。
 */
data class ChapterContent(val title: String, val paragraphs: List<String>) {

    val displayText: String get() = TextNormalizer.toDisplayText(paragraphs)

    /**
     * 段评锚点（spec §9.1）：`pa1:<下标>:<该段规范化后前 16 字的 sha256 前 12 位>`。
     *
     * `pa1:` 版本前缀与 `ck1:` 同理——锚点建立在规范化规则之上，规则一改所有锚点全变、
     * 段评整体散桶，而评论不可再生。哈希命中优先、失败回落下标，是跨来源切分不一致时
     * 唯一的补救手段。
     */
    fun anchorFor(paragraphIndex: Int): String {
        val text = paragraphs.getOrNull(paragraphIndex).orEmpty()
        return "pa1:$paragraphIndex:" + shortHash(text.take(PARAGRAPH_ANCHOR_PREFIX_CHARS))
    }

    private fun shortHash(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(12)

    private companion object {
        /** 锚点取样长度：太短易撞、太长则一处排版差异即失配 */
        const val PARAGRAPH_ANCHOR_PREFIX_CHARS = 16
    }
}

/** 源文件能提供的书级元数据（作者为 null 表示未知，由显示层填占位词） */
data class LocalBookMeta(val title: String, val author: String?, val coverFile: File?)

/**
 * 章节正文的落盘出口，由 `BookStore` 实现。
 *
 * 为什么 spec §7 的 `buildChapters` 到这里多了这个参数：TXT 导入是"扫一遍边切边写"，
 * 章文件必须随 Flow 元素一起落盘，否则要么整本进内存、要么扫两遍。返回的 Flow 因此
 * 是**冷流**，收集时才真正推进切分与写入。
 */
interface ChapterSink {
    /** 写一章正文，返回该章的 content_ref */
    suspend fun write(index: Int, paragraphs: List<String>): String
}

/**
 * 一个来源 = 一本书的内容从哪来、怎么解析（spec §7）。
 *
 * 继承 [ChapterReader]：本地书导入后读正文与网络书读正文走同一个 `readChapter` 接缝，
 * `SourceReader` 本身只在**导入链路**出现（`readMetadata` + `buildChapters`）。
 *
 * 与 `BookParser` 的分界：本接口只管**内容**（元数据、目录、正文）；发现类能力
 * （搜索、分类、书城）不属于它，M1b 会把 `BookParser` 拆成「发现」与「内容」两半。
 */
interface SourceReader : ChapterReader {
    /** 从源文件提取书级元数据（标题、作者、封面），不涉及正文 */
    suspend fun readMetadata(source: BookSourceFile): LocalBookMeta

    /**
     * 扫一遍源文件，切分章节并逐章写入 [sink]，冷流收集时才推进。
     * 返回的 [Flow] 元素与写入顺序一致。
     */
    fun buildChapters(source: BookSourceFile, sink: ChapterSink): Flow<ChapterEntry>

    /** 读取一章正文，继承自 [ChapterReader]，本地与网络来源共享同一签名 */
    override suspend fun readChapter(entry: ChapterEntry, location: BookLocation): ChapterContent

    /** 探测源文件编码。TXT 需要猜，容器格式自带声明，故默认 UTF-8 */
    fun probeCharset(file: File): String = "UTF-8"

    /**
     * 从源文件提取封面图片到目标目录，无封面时不写任何文件。默认无操作（TXT 无封面）。
     *
     * 与 [readMetadata] 分离是因为封面提取涉及二进制 I/O 且需要目标目录，
     * 而 [readMetadata] 只读文本元数据、不需要知道暂存目录位置。
     * 调用方在 `beginImport` 之后、`commitImport` 之前调用。
     */
    fun extractCover(source: BookSourceFile, targetDir: File) {}
}
