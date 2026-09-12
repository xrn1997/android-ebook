package com.ebook.book.mvvm.viewmodel

import android.os.Environment
import com.xrn1997.common.util.Logger
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.ebook.book.R
import com.ebook.common.importer.ImportBatchOutcome
import com.ebook.common.importer.ImportDuplicateState
import com.ebook.common.importer.ImportNotice
import com.ebook.common.importer.LocalImportCoordinator
import com.xrn1997.common.BaseApplication.Companion.context
import com.xrn1997.common.mvvm.model.NoOpModel
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 本地书籍导入页 ViewModel——页面侧的薄桥：扫描文件 + 把导入循环的观察转发给 UI。
 *
 * 导入循环本体住在进程级的 [LocalImportCoordinator]（自有作用域，页面销毁不中断），
 * 本类不再持有循环与判重门：spec §6 要求"点完导入即可继续操作，该书在书架上显示解析中"，
 * 循环挂在 viewModelScope 上做不到这一点（页面一退整批被取消）。页面需要的能力全部是
 * coordinator 状态的投影：[duplicateState]（处置框）、[importProgress]（进度文案）、
 * [isImporting]（遮罩）以及成功/失败事件与合并处置 Toast。
 *
 * 文件扫描仍归本类：那是纯页面交互（扫描按钮/取消/结果列表），与导入执行无关。
 */
@HiltViewModel
class BookImportViewModel @Inject constructor(
    private val coordinator: LocalImportCoordinator,
) : BaseViewModel<NoOpModel>(NoOpModel()) {
    val mImportBookList = MutableLiveData<List<File>>()
    val searchFinishEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val addSuccessEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val addErrorEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 导入进度（已处理完的文件数）：遮罩文案用，0 表示尚未处理完任何一本 */
    val importProgress: StateFlow<Int> = coordinator.progress
        .map { it.done }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** 批量是否在跑：遮罩显隐由它驱动，重进页面也能正确恢复（批次属于进程而非页面） */
    val isImporting: StateFlow<Boolean> = coordinator.progress
        .map { it.running }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 判重处置状态：UI 收集后弹处置框（页面销毁后门仍在，重进页面可继续处置） */
    val duplicateState: StateFlow<ImportDuplicateState> = coordinator.duplicateState

    init {
        // 合并/覆盖处置的结果 → Toast（文案在 module_book，协调器只发语义事件）
        viewModelScope.launch {
            coordinator.notices.collect { notice ->
                when (notice) {
                    is ImportNotice.MergeAppended -> sendToast(
                        context.getString(
                            if (notice.appendedChapters > 0) R.string.import_merge_appended
                            else R.string.import_merge_equivalent,
                            notice.appendedChapters,
                        )
                    )
                    ImportNotice.MergeEquivalent -> toast(R.string.import_merge_equivalent)
                    ImportNotice.MergeDiverged -> toast(R.string.import_merge_diverged)
                    ImportNotice.MergeTargetNotLocal -> toast(R.string.import_merge_target_network)
                    ImportNotice.MergeEntryMissing -> toast(R.string.import_merge_entry_gone)
                }
            }
        }
        // 整批收尾 → 成功事件（轻提示）或失败事件 + 结果文案
        viewModelScope.launch {
            coordinator.batchFinished.collect { outcome ->
                onBatchFinished(outcome)
            }
        }
    }

    private fun onBatchFinished(outcome: ImportBatchOutcome) {
        if (outcome.failCount == 0) {
            addSuccessEvent.tryEmit(Unit)
        } else {
            addErrorEvent.tryEmit(Unit)
            sendToast(context.getString(R.string.import_result_format, outcome.successCount, outcome.failCount))
        }
    }

    //停止扫描
    @Volatile
    private var isCancel: Boolean = false

    /**
     * 扫描本机可导入的书籍文件。
     *
     * 结果**整体替换**而非追加：扫描范围恒为整个外部存储根目录，两次调用的结果集完全重叠，
     * 追加会让重复扫描把同一批文件在列表里堆两遍（基线行为即如此）。先清空再填，顺带给出
     * 「扫描已开始」的即时反馈。
     *
     * 随之而来的取舍：扫描抛错时列表停在空态、不恢复上一次的结果（catch 只记日志）。
     * 这是替换语义的代价——要保住旧结果就得把清空挪到成功分支，代价是失去即时反馈。
     */
    fun searchLocationBook() {
        isCancel = false
        mImportBookList.value = emptyList()
        viewModelScope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    val result = mutableListOf<File>()
                    if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                        searchBook(result, File(Environment.getExternalStorageDirectory().absolutePath))
                    }
                    result
                }
                mImportBookList.value = files
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
                    if (childFile.isFile) {
                        val ext = childFile.name.substringAfterLast('.', "").lowercase()
                        val isEligible = when (ext) {
                            "epub" -> true
                            "txt" -> childFile.length() > 100 * 1024
                            else -> false
                        }
                        if (isEligible) {
                            result.add(childFile)
                            continue
                        }
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

    /** 加入书架：把选中文件交给进程级协调器，批量在自有作用域里推进，页面可以随意离开 */
    fun importBooks(books: List<File>) {
        coordinator.submit(books)
    }

    private fun toast(resId: Int) {
        sendToast(context.getString(resId))
    }

    /** 继续添加：两个来源共存（同键，评论按并集共享） */
    fun resolveKeepBoth() = coordinator.resolveKeepBoth()

    /** 智能合并：补章进旧条目，新条目退场 */
    fun resolveMerge() = coordinator.resolveMerge()

    /** 覆盖：新条目替换旧条目，旧条目的并集键先吸收 */
    fun resolveOverwrite() = coordinator.resolveOverwrite()

    /** 跳过本文件 */
    fun resolveCancel() = coordinator.resolveCancel()

    fun scanCancel() {
        isCancel = true
    }

    private companion object {
        const val TAG = "BookImportViewModel"
    }
}
