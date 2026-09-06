package com.ebook.book.repository

import android.app.Application
import android.net.Uri
import com.ebook.common.importer.LocalBookImporter
import com.ebook.db.entity.LocBookShelfEntity
import com.xrn1997.common.mvvm.model.BaseModel
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 书籍导入仓库——将 Uri / File 形式的入口统一收敛到 [LocalBookImporter]，
 * 供 BookImportViewModel 使用。
 *
 * Uri 入口需先把内容拷贝到 cacheDir 下的临时文件（ContentResolver 流不可重放，
 * 而 [LocalBookImporter.import] 需要完整读取源文件做哈希+切分），拷贝完成后无论
 * 成功失败都在 finally 里清掉临时文件。
 */
@Singleton
class BookImportRepository @Inject constructor(
    private val application: Application,
    private val importer: LocalBookImporter,
) : BaseModel() {

    /** 从 Uri 导入：先把 Uri 内容拷贝到临时文件，再走 File 路径。 */
    suspend fun import(uri: Uri, onChapter: (Int) -> Unit = {}): LocBookShelfEntity {
        val temp = File(application.cacheDir, "import-${System.nanoTime()}.tmp")
        return try {
            application.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("无法读取 Uri 内容：$uri")
            importer.import(temp, onChapter)
        } finally {
            temp.delete()
        }
    }

    /** 从 File 导入：直接委托给 [LocalBookImporter]。 */
    suspend fun import(file: File, onChapter: (Int) -> Unit = {}): LocBookShelfEntity {
        return importer.import(file, onChapter)
    }
}
