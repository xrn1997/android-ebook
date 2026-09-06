package com.ebook.book.repository

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import com.ebook.common.importer.LocalBookImporter
import com.ebook.db.entity.LocBookShelfEntity
import com.xrn1997.common.mvvm.model.BaseModel
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 书籍导入仓库——只服务「外部打开」入口（ACTION_VIEW 送来 Uri）并接到 [LocalBookImporter]。
 *
 * 唯一调用方是 ReadBookActivity 的 Uri 分支；书架导入页那条链路（BookImportViewModel）
 * 直接依赖 [LocalBookImporter] 的 File 入口，不经本类。
 *
 * Uri 入口需先把内容拷贝到 cacheDir 下的暂存文件（ContentResolver 流不可重放，
 * 而 [LocalBookImporter.import] 需要完整读取源文件做哈希+切分），拷贝完成后无论
 * 成功失败都在 finally 里连暂存目录一起清掉。
 */
@Singleton
class BookImportRepository @Inject constructor(
    private val application: Application,
    private val importer: LocalBookImporter,
) : BaseModel() {

    /** 从 Uri 导入：先把 Uri 内容拷进保住源文件真名的暂存目录，再走 File 路径。 */
    suspend fun import(uri: Uri): LocBookShelfEntity {
        // 目录名带 nanoTime 保证并发唯一，目录里的文件名则必须是源文件真名（见 stagingFile）
        val scratch = File(application.cacheDir, "import-${System.nanoTime()}")
        val temp = stagingFile(scratch, uri)
        return try {
            application.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("无法读取 Uri 内容：$uri")
            importer.import(temp)
        } finally {
            scratch.deleteRecursively()
        }
    }

    /**
     * 在暂存目录里给出一个**保住源文件真名**的目标文件。
     *
     * 为什么不能图省事统一叫 `import-xxx.tmp`——两条链路都会断：
     * - [LocalBookImporter] 按 `File.extension` 路由格式（`BookFormat.fromExtension`），
     *   `.tmp` 判不出格式，直接抛「不支持的本地书格式」，外部打开（text/plain 与 EPUB
     *   两个 ACTION_VIEW 入口）无一例外全灭；
     * - TXT 的书名/作者只能从文件名解析（`FileNameMetadata.parse`），名字一丢书名就退化成
     *   暂存文件名，连带 `comment_key = hash(书名 ‖ 作者)` 算错——评论落进一个再也对不上的桶，
     *   而评论不可再生。
     *
     * 用「唯一目录 + 真名文件」而不是「真名 + nanoTime 后缀」：后者会把后缀掺进书名，
     * 同样污染 comment_key。
     *
     * displayName 出自外部 ContentProvider，不可信：只取最后一段并剔掉两种分隔符，
     * 防 `../` 逃出暂存目录写到 cacheDir 之外。
     */
    private fun stagingFile(scratch: File, uri: Uri): File {
        val raw = queryDisplayName(uri) ?: uri.lastPathSegment
        val name = raw?.substringAfterLast('/')?.substringAfterLast('\\')?.trim().orEmpty()
        require(name.isNotEmpty()) { "无法从 Uri 取得文件名：$uri" }
        scratch.mkdirs()
        return File(scratch, name)
    }

    /** content:// 查 DISPLAY_NAME 投影；其余 scheme（file://）返回 null，由调用方回落 lastPathSegment */
    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme != "content") return null
        return application.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    }
}
