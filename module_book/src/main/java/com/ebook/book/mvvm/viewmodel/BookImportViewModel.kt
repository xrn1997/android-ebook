package com.ebook.book.mvvm.viewmodel

import android.os.Environment
import com.xrn1997.common.util.Logger
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.ebook.book.R
import com.ebook.common.domain.DuplicateBookDetector
import com.ebook.common.importer.LocalBookImporter
import com.ebook.common.domain.ParsedBookMeta
import com.ebook.common.repository.BookRepository
import com.ebook.common.repository.ImportMergeResult
import com.ebook.db.entity.LocBookShelfEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.xrn1997.common.BaseApplication.Companion.context
import com.xrn1997.common.mvvm.model.NoOpModel
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

/**
 * 导入判重命中的处置状态。
 *
 * 导入循环逐文件解析元数据→算 `comment_key`→查书架主键，命中时暂停循环、推 [Detected] 给 UI
 * 弹处置框；用户选完经 [BookImportViewModel.settle] 回写，循环恢复。
 */
sealed class ImportDuplicateState {
    /** 空闲：未在等待用户决策 */
    data object Idle : ImportDuplicateState()

    /**
     * 命中已有条目：UI 应弹处置框。
     *
     * [matches] 必须整列展示——同一键下可能挂着多个条目（此前重复导入攒下的），只取第一条
     * 会让用户在不知情的情况下被删掉另外几本。
     */
    data class Detected(
        val file: File,
        val meta: ParsedBookMeta,
        val matches: List<DuplicateBookDetector.ImportMatch>,
    ) : ImportDuplicateState()
}

/**
 * 用户对判重命中的处置选择。
 *
 * [KEEP_BOTH] 是 spec §6 要求的非破坏默认项（"要看现有条目还是继续添加"）：同键的两个条目
 * 读评论时天然取并集，共存本身就是这套模型支持的正常形态，不是需要修补的缺陷。
 */
private enum class DuplicateResolution { KEEP_BOTH, MERGE, OVERWRITE, CANCEL }

/**
 * 本地书籍导入页 ViewModel。
 *
 * 导入直接走 [LocalBookImporter]（已在内部完成哈希→切章→DB 写入→事件发布），
 * 不再需要 BookImportRepository 做中间转发，也不再需要 BookRepository.addToShelf()。
 * Model 位用 [NoOpModel] 占位（无一次性命令门面需求，见 AGENTS.md MVVM 约定）。
 *
 * 导入时点判重（spec §6，取代原书架侧提示，见 ADR-0023）：每文件先
 * [LocalBookImporter.parseMetadata] 取标题+作者算 `comment_key`，再经
 * [DuplicateBookDetector.findMatchesFor] 与书架各条目当前主键比对。命中时暂停循环、弹四选一
 * 处置框（继续添加 / 智能合并 / 覆盖 / 跳过），选完恢复循环处理下一个文件。
 */
@HiltViewModel
class BookImportViewModel @Inject constructor(
    private val importer: LocalBookImporter,
    private val duplicateBookDetector: DuplicateBookDetector,
    private val bookRepository: BookRepository,
) : BaseViewModel<NoOpModel>(NoOpModel()) {
    val mImportBookList = MutableLiveData<List<File>>()
    val searchFinishEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val addSuccessEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val addErrorEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 导入进度：0 表示未在导入；>0 表示当前正在导入第几本。 */
    val importProgress = MutableStateFlow(0)

    /** 判重处置状态：UI 收集后弹处置框 */
    private val _duplicateState = MutableStateFlow<ImportDuplicateState>(ImportDuplicateState.Idle)
    val duplicateState: StateFlow<ImportDuplicateState> = _duplicateState.asStateFlow()

    /**
     * 导入循环的暂停门。
     *
     * 用 [AtomicReference] 而不是普通可空字段：门由 IO 线程的导入循环置入、由主线程的按钮
     * 回调取出并 complete，普通 `var` 在两线程间没有可见性保证（同文件 `isCancel` 就是为此
     * 加了 `@Volatile`）。`getAndSet(null)` 同时解决重复点击——第二次取到 null，天然幂等，
     * 不会把上一个文件的决策错灌给下一个门。
     *
     * 用户始终不选时的悬挂由 `viewModelScope` 兜住：页面销毁 → 协程取消 → `await()` 抛
     * 取消异常 → 循环整体退出，不会留下跑不动也停不下的导入。
     */
    private val duplicateGate = AtomicReference<CompletableDeferred<DuplicateResolution>?>(null)

    //停止扫描
    @Volatile
    private var isCancel: Boolean = false

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

    /**
     * 导入选中文件：逐文件解析元数据 → 判重 → 无命中直接导入，有命中暂停等用户处置。
     *
     * 循环在 IO 上下文里跑（解析/导入/库写都是重操作），命中时经 [CompletableDeferred] 暂停——
     * 主线程的 resolve 方法了结门后循环自动恢复，不需要额外的状态机或回调链。
     */
    fun importBooks(books: List<File>) {
        viewModelScope.launch {
            importProgress.value = 0
            var successCount = 0
            var failCount = 0
            withContext(Dispatchers.IO) {
                for ((i, file) in books.withIndex()) {
                    try {
                        if (importWithDuplicateCheck(file)) successCount++ else Logger.i(TAG, "用户跳过: ${file.name}")
                    } catch (e: Exception) {
                        Logger.e(TAG, "导入失败: ${file.name}", e)
                        failCount++
                    }
                    importProgress.value = i + 1
                }
            }
            importProgress.value = 0
            if (failCount == 0) {
                addSuccessEvent.tryEmit(Unit)
            } else {
                addErrorEvent.tryEmit(Unit)
                sendToast(context.getString(R.string.import_result_format, successCount, failCount))
            }
        }
    }

    /**
     * 导入单个文件，返回是否真的落了库（用户选择跳过时 false）。
     *
     * 顺序是**先导入、后处置**：三种处置都需要新条目已作为一份完整的书存在——补章要读它的
     * 章文件，覆盖要在删旧之前确认新的确实在架。反过来「先删旧再导新」一旦导入抛异常
     * （切不出章节、严格解码失败），旧条目已被删、新条目没进来，用户两头空且只看到一行日志。
     */
    private suspend fun importWithDuplicateCheck(file: File): Boolean {
        val meta = importer.parseMetadata(file)
        val matches = duplicateBookDetector.findMatchesFor(meta)
        val resolution = if (matches.isEmpty()) {
            DuplicateResolution.KEEP_BOTH
        } else {
            awaitDisposition(file, meta, matches)
        }
        if (resolution == DuplicateResolution.CANCEL) return false

        val imported = importer.import(file)
        Logger.i(TAG, "导入完成（新书=${imported.new}）")
        when (resolution) {
            DuplicateResolution.MERGE -> applyMerge(imported, matches)
            DuplicateResolution.OVERWRITE -> applyOverwrite(imported, matches)
            DuplicateResolution.KEEP_BOTH, DuplicateResolution.CANCEL -> Unit
        }
        return true
    }

    /**
     * 暂停导入循环等用户处置，返回处置选择。
     *
     * `finally` 里复位状态：处置完（或协程被取消）都不能让框留在架上，否则下一个文件的
     * 检测状态会被上一个残留的 Detected 盖住。
     */
    private suspend fun awaitDisposition(
        file: File,
        meta: ParsedBookMeta,
        matches: List<DuplicateBookDetector.ImportMatch>,
    ): DuplicateResolution {
        val gate = CompletableDeferred<DuplicateResolution>()
        duplicateGate.set(gate)
        _duplicateState.value = ImportDuplicateState.Detected(file, meta, matches)
        return try {
            gate.await()
        } finally {
            duplicateGate.compareAndSet(gate, null)
            _duplicateState.value = ImportDuplicateState.Idle
        }
    }

    /**
     * 智能合并：把新条目多出的尾部章节补进旧条目，新条目退场。
     *
     * [LocBookShelfEntity.new] 为 false 时 importer 是幂等命中（这份文件的 md5 已在架上，
     * 没建新条目），无章可补、也没有该删的多余条目，直接返回。
     * 补章目标取首个本地命中条目（UI 只在存在本地命中时才给这个选项）；同键的其余条目继续
     * 共存——它们读的是同一批键并集，评论不受影响。
     */
    private suspend fun applyMerge(
        imported: LocBookShelfEntity,
        matches: List<DuplicateBookDetector.ImportMatch>,
    ) {
        if (!imported.new) return
        val target = matches.firstOrNull { it.isLocal } ?: return
        when (val outcome = bookRepository.mergeTailChapters(imported.bookShelf.noteUrl, target.noteUrl)) {
            is ImportMergeResult.Merged -> sendToast(
                context.getString(
                    if (outcome.appendedChapters > 0) R.string.import_merge_appended
                    else R.string.import_merge_equivalent,
                    outcome.appendedChapters,
                )
            )
            ImportMergeResult.Diverged -> toast(R.string.import_merge_diverged)
            ImportMergeResult.TargetNotLocal -> toast(R.string.import_merge_target_network)
            ImportMergeResult.EntryMissing -> toast(R.string.import_merge_entry_gone)
        }
    }

    /**
     * 覆盖：新条目替换掉命中的旧条目。
     *
     * 删每一本之前先把它的 `book_group` 行吸收进新条目（[BookRepository.absorbGroupKeys]）——
     * 旧条目身上可能挂着它自己历次合并攒下的 secondary 键，`book_group` 随书删，不先吸收就
     * 连读并集一起丢了。
     *
     * 过滤掉与新条目同 noteUrl 的那条：md5 幂等命中时 importer 没建新条目，"新条目"就是
     * 被命中的旧条目本身，不能自己删自己。
     */
    private suspend fun applyOverwrite(
        imported: LocBookShelfEntity,
        matches: List<DuplicateBookDetector.ImportMatch>,
    ) {
        val newNoteUrl = imported.bookShelf.noteUrl
        matches.filter { it.noteUrl != newNoteUrl }.forEach { old ->
            bookRepository.absorbGroupKeys(newNoteUrl, old.noteUrl)
            bookRepository.getBookByUrl(old.noteUrl)?.let { bookRepository.removeFromShelf(it) }
        }
    }

    private fun toast(resId: Int) {
        sendToast(context.getString(resId))
    }

    /**
     * UI 的处置回调：取出并了结当前门。
     *
     * `getAndSet(null)` 让一次决策只生效一次——连点第二次拿到 null 直接无操作，也就不会把
     * 上一个文件的决策错灌给下一个文件的门。
     */
    private fun settle(resolution: DuplicateResolution) {
        duplicateGate.getAndSet(null)?.complete(resolution)
    }

    /** 继续添加：两个来源共存（同键，评论按并集共享） */
    fun resolveKeepBoth() = settle(DuplicateResolution.KEEP_BOTH)

    /** 智能合并：补章进旧条目，新条目退场 */
    fun resolveMerge() = settle(DuplicateResolution.MERGE)

    /** 覆盖：新条目替换旧条目，旧条目的并集键先吸收 */
    fun resolveOverwrite() = settle(DuplicateResolution.OVERWRITE)

    /** 跳过本文件 */
    fun resolveCancel() = settle(DuplicateResolution.CANCEL)

    fun scanCancel() {
        isCancel = true
    }
}