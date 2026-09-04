package com.ebook.book.mvvm.viewmodel

import android.os.Environment
import com.xrn1997.common.util.Logger
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.ebook.book.R
import com.ebook.book.repository.BookImportRepository
import com.ebook.common.repository.BookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.xrn1997.common.BaseApplication.Companion.context
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject

@HiltViewModel
class BookImportViewModel @Inject constructor(
    private val bookImportRepository: BookImportRepository,
    private val bookRepository: BookRepository
) : BaseViewModel<BookImportRepository>(bookImportRepository) {
    val mImportBookList = MutableLiveData<List<File>>()
    val searchFinishEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val addSuccessEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val addErrorEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    //停止扫描
    @Volatile
    private var isCancel: Boolean = false

    fun searchLocationBook() {
        isCancel = false
        viewModelScope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    val result = mutableListOf<File>()
                    if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                        searchBook(result, File(Environment.getExternalStorageDirectory().absolutePath))
                    }
                    result
                }
                val list = mImportBookList.value ?: emptyList()
                mImportBookList.value = list + files
                searchFinishEvent.tryEmit(Unit)
            } catch (e: Exception) {
                Logger.e(TAG, "onError: ", e)
            }
        }
    }

    private fun searchBook(result: MutableList<File>, parentFile: File) {
        if (isCancel) return
        if (!parentFile.listFiles().isNullOrEmpty()) {
            val childFiles = parentFile.listFiles()
            if (childFiles != null) {
                for (childFile in childFiles) {
                    if (childFile.isFile && childFile.name.substring(childFile.name.lastIndexOf(".") + 1)
                            .equals("txt", ignoreCase = true)
                        && childFile.length() > 100 * 1024
                    ) {   //100kb
                        result.add(childFile)
                        continue
                    }
                    if (childFile.absolutePath == "/storage/emulated/0/Android/data"
                        || childFile.absolutePath == "/storage/emulated/0/Android/obb"
                    ) {
                        //这个两个路径没有权限，不扫
                        continue
                    }
                    if (childFile.isDirectory && !childFile.listFiles().isNullOrEmpty()) {
                        //进入文件夹中继续扫
                        searchBook(result, childFile)
                    }
                }
            }
        }
    }

    fun importBooks(books: List<File>) {
        viewModelScope.launch {
            var successCount = 0
            var failCount = 0
            for (file in books) {
                try {
                    val value = bookImportRepository.importBook(file)
                    Logger.i(TAG, "导入完成（新书=${value.new}）")
                    if (value.new) {
                        bookRepository.addToShelf(value.bookShelf)
                    }
                    successCount++
                } catch (e: Exception) {
                    Logger.e(TAG, "导入失败: ${file.name}", e)
                    failCount++
                }
            }
            if (failCount == 0) {
                addSuccessEvent.tryEmit(Unit)
            } else {
                addErrorEvent.tryEmit(Unit)
                sendToast(context.getString(R.string.import_result_format, successCount, failCount))
            }
        }
    }

    fun scanCancel() {
        isCancel = true
    }
}