package com.ebook.book.repository

import android.app.Application
import androidx.core.net.toUri
import com.ebook.book.util.BookImportManager
import com.ebook.db.entity.LocBookShelfEntity
import com.xrn1997.common.mvvm.model.BaseModel
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 书籍导入仓库——唯一入口统一走 [BookImportManager]（避免 ViewModel 直接依赖
 * Application/URI 转换细节），供 BookImportViewModel 使用。
 */
@Singleton
class BookImportRepository @Inject constructor(
    private val application: Application,
    private val bookImportManager: BookImportManager
) : BaseModel() {

    suspend fun importBook(file: File): LocBookShelfEntity {
        return bookImportManager.importBook(application.applicationContext, file.toUri())
    }
}
