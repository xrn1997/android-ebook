package com.ebook.book.importer

import com.ebook.common.domain.DuplicateBookDetector
import com.ebook.common.domain.ParsedBookMeta
import com.ebook.common.ui.preview.SAMPLE_SOURCE_URL
import com.ebook.common.ui.preview.sampleShelfBook
import java.io.File

/**
 * 本地书籍导入页的预览样例。
 *
 * 与共享 UI 样例分家：导入是 module_book 的单消费者特性，夹具跟着特性走，共享库不再为
 * 一个页面保留导入域的数据结构（见 ADR-0015 决策 2）。
 */

/**
 * 样例存储根路径：[sampleLocalBookFiles] 的假文件都挂在这个前缀下。
 *
 * **不取** `Environment.getExternalStorageDirectory()`：这个门面保持纯 JVM（无 Android 依赖），
 * 于是预览宿主与非设备渲染环境不会因为 Android 静态状态在不同机器上给出不同根路径，
 * 让「路径那一行显示成什么」变成不可比的东西。
 */
const val SAMPLE_LOCAL_STORAGE_ROOT: String = "/storage/emulated/0"

private val sampleLocalFileNames =
    listOf("山海拾遗.txt", "长安小吏.txt", "星轨编年史.txt", "长夜将尽.txt", "雾都手记.txt")

/** 判重处置框的默认书名/作者直接取共享门面的第一条，避免两处硬编码日后漂移 */
private val sampleLocalShelf = sampleShelfBook(1)
private val sampleLocalTitle = sampleLocalShelf.bookInfo?.name.orEmpty()
private val sampleLocalAuthor = sampleLocalShelf.bookInfo?.author.orEmpty()

/**
 * 本地导入列表的样例文件：**只造路径、不落磁盘**。
 *
 * [com.ebook.common.util.formatSize] 读的是 `File.length()`，路径不存在时那一格恒为 `0 B`——
 * 这是预期形态而不是漏填：设计期渲染环境不该做 IO，而「0 字节的空文件」在真实扫描结果里
 * 本来就存在。条目要看的排版是「文件名省略 + 体积贴右 + 路径回落」，三者只由路径决定。
 *
 * **路径那一行在 Windows 宿主的预览里会显示成宿主风格**：`ImportBookItem` 取的是
 * `File.absolutePath`，它在 Windows 上会把 `/storage/emulated/0/…` 补成 `D:\storage\emulated\0\…`，
 * 于是「把存储根换成"存储空间"文案」那一档换不掉。真机上是命中替换的那一档，只能在设备上确认。
 */
fun sampleLocalBookFiles(count: Int = 3): List<File> =
    List(count) { i ->
        File("$SAMPLE_LOCAL_STORAGE_ROOT/download/${sampleLocalFileNames[i % sampleLocalFileNames.size]}")
    }

/**
 * 判重处置框的样例命中项：默认给「一条本地 + 一条网络」两行。
 *
 * 两条都要给的理由：处置框里的「智能合并」只在**存在本地命中**时才出现（补章的载体是本机章
 * 文件，网络书的正文在书源那边）。只造一种就永远看不见那一档的有与无。
 *
 * [hasLocal] 传 false 即「命中项全是网络书」那一档，处置框应只剩三个动作。
 */
fun sampleDuplicateMatches(
    title: String = sampleLocalTitle,
    author: String = sampleLocalAuthor,
    hasLocal: Boolean = true,
): List<DuplicateBookDetector.ImportMatch> = buildList {
    if (hasLocal) {
        add(
            DuplicateBookDetector.ImportMatch(
                // 本地书的定位符就是章文件所在的导入路径，不是 http 地址
                noteUrl = "$SAMPLE_LOCAL_STORAGE_ROOT/download/${sampleLocalFileNames.first()}",
                title = title,
                author = author,
                isLocal = true,
            )
        )
    }
    add(
        DuplicateBookDetector.ImportMatch(
            noteUrl = "$SAMPLE_SOURCE_URL/book/1",
            title = title,
            author = author,
            isLocal = false,
        )
    )
}

/**
 * 判重处置框的完整状态（[ImportDuplicateState.Detected]）：待导入文件 + 解析出的元数据 + 命中清单。
 *
 * 三样缺一不可：处置框的标题句取 `meta.title`、命中行取 `matches`、而「智能合并」那一档由
 * `matches` 里有没有本地命中决定。只在外层拼一半会让预览画出一张缺行的对话框，而据此判断
 * 布局就是错的——所以预览与渲染冒烟测试一律从这里取，不在页面里手搓 `Detected(...)`。
 *
 * @param hasLocalMatch false 即「命中项全是网络书」，处置框不该出现「智能合并」
 */
fun sampleImportDuplicate(
    hasLocalMatch: Boolean = true,
    title: String = sampleLocalTitle,
    author: String = sampleLocalAuthor,
): ImportDuplicateState.Detected = ImportDuplicateState.Detected(
    file = sampleLocalBookFiles(1).first(),
    meta = ParsedBookMeta(title = title, author = author),
    matches = sampleDuplicateMatches(title = title, author = author, hasLocal = hasLocalMatch),
)
