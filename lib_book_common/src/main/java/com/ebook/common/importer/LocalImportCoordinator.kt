package com.ebook.common.importer

import com.ebook.common.domain.DuplicateBookDetector
import com.ebook.common.domain.ParsedBookMeta
import com.ebook.common.repository.BookRepository
import com.ebook.common.repository.ImportMergeResult
import com.ebook.db.entity.LocBookShelfEntity
import com.xrn1997.common.util.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 导入判重命中的处置状态。
 *
 * 导入循环逐文件解析元数据→算 `comment_key`→查书架主键，命中时暂停循环、推 [Detected] 给 UI
 * 弹处置框；用户选完经 [LocalImportCoordinator] 的 resolve 系列方法回写，循环恢复。
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

/** 一批导入的收尾结果，供 UI 提示（成功走轻提示，有失败走结果文案） */
data class ImportBatchOutcome(val successCount: Int, val failCount: Int)

/** 一批导入的实时进度：[done] 是已处理完的文件数（含失败与跳过） */
data class ImportBatchProgress(val running: Boolean, val done: Int, val total: Int)

/** 正在解析中的一本书：书架据此渲染"解析中"占位行（书架行要等导入结束才落库） */
data class ParsingBook(val id: String, val title: String)

/** 智能合并/覆盖处置的运行结果，文案资源归 UI 层，协调器只发语义事件 */
sealed interface ImportNotice {
    /** 补章成功，[appendedChapters] 可为 0（两份内容等价） */
    data class MergeAppended(val appendedChapters: Int) : ImportNotice

    /** 补章成功但没有多出章（两份内容等价） */
    data object MergeEquivalent : ImportNotice

    /** 归一化章名序列分叉，整笔放弃，两本共存 */
    data object MergeDiverged : ImportNotice

    /** 目标不是本地书：正文不在本机，没有可补的载体 */
    data object MergeTargetNotLocal : ImportNotice

    /** 处置期间条目被删（如用户手动清理），合并没有落点 */
    data object MergeEntryMissing : ImportNotice
}

/**
 * 本地书籍导入的**进程级**协调器（spec §6）。
 *
 * 为什么导入循环不能住在导入页的 ViewModel 里：spec §6 要求"点完导入即可继续操作，该书在
 * 书架上显示解析中"——批量导入挂在 `viewModelScope` 上，用户一离开导入页整批就被取消，
 * 书架上的"解析中"从何谈起。上移到单例 + 自有作用域后，页面只是它的一个观察者；
 * 生命周期归 [scope]，页面进出不再影响导入。
 *
 * 职责：
 * - 批量推进导入循环（逐文件 解析元数据 → 判重 → 命中暂停等处置 → 导入 → 落库）
 * - 判重处置门（[duplicateState] + resolve 系列）：UI（导入页，未来也可以是书架）收集后弹框，
 *   用户选择经 resolve 回写；页面销毁不影响门的状态，重进页面即可继续处置
 * - 进行态广播：[progress]（批量进度）、[parsingBooks]（书架"解析中"行）、[notices]（处置结果）、
 *   [batchFinished]（整批收尾）
 */
@Singleton
class LocalImportCoordinator @Inject constructor(
    private val importer: LocalBookImporter,
    private val duplicateBookDetector: DuplicateBookDetector,
    private val bookRepository: BookRepository,
) {
    /** 自有作用域：批量导入的生命周期与任何页面解耦（进程活着它就活着） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 同一时刻只允许一批导入：扫描出多份重复文件重复提交没有意义，后到的直接拒绝并留痕 */
    private val running = AtomicBoolean(false)

    /**
     * 导入循环的暂停门。
     *
     * 用 [AtomicReference] 而不是普通可空字段：门由 IO 线程的导入循环置入、由主线程的按钮
     * 回调取出并 complete，普通 `var` 在两线程间没有可见性保证。`getAndSet(null)` 同时解决
     * 重复点击——第二次取到 null，天然幂等，不会把上一个文件的决策错灌给下一个门。
     */
    private val duplicateGate = AtomicReference<CompletableDeferred<DuplicateResolution>?>(null)

    /** 判重处置状态：UI 收集后弹处置框 */
    private val _duplicateState = MutableStateFlow<ImportDuplicateState>(ImportDuplicateState.Idle)
    val duplicateState: StateFlow<ImportDuplicateState> = _duplicateState.asStateFlow()

    private val _progress = MutableStateFlow(ImportBatchProgress(running = false, done = 0, total = 0))
    val progress: StateFlow<ImportBatchProgress> = _progress.asStateFlow()

    /** 正在解析的书（书架"解析中"行）；以 id 增删，避免同名书互相误删 */
    private val _parsingBooks = MutableStateFlow<List<ParsingBook>>(emptyList())
    val parsingBooks: StateFlow<List<ParsingBook>> = _parsingBooks.asStateFlow()

    /** 处置结果语义事件，文案归 UI 层 */
    val notices = MutableSharedFlow<ImportNotice>(extraBufferCapacity = 8)

    /** 整批收尾事件 */
    val batchFinished = MutableSharedFlow<ImportBatchOutcome>(extraBufferCapacity = 1)

    /**
     * 提交一批待导入文件，在自有作用域里逐个处理。
     *
     * 已有一批在跑时拒绝新批次（记日志、不排队）：并发两批会在判重与书架写侧互相踩，
     * 用户也不会预期"两次点加入书架"变成两批交错。
     */
    fun submit(files: List<File>) {
        if (files.isEmpty()) return
        if (!running.compareAndSet(false, true)) {
            Logger.w(TAG, "已有一批导入在跑，忽略新提交的 ${files.size} 个文件")
            return
        }
        scope.launch {
            try {
                runBatch(files)
            } finally {
                running.set(false)
                _progress.value = ImportBatchProgress(running = false, done = 0, total = 0)
            }
        }
    }

    private suspend fun runBatch(files: List<File>) {
        _progress.value = ImportBatchProgress(running = true, done = 0, total = files.size)
        var successCount = 0
        var failCount = 0
        for ((i, file) in files.withIndex()) {
            try {
                if (importWithDuplicateCheck(file)) successCount++ else Logger.i(TAG, "用户跳过: ${file.name}")
            } catch (e: Exception) {
                Logger.e(TAG, "导入失败: ${file.name}", e)
                failCount++
            }
            _progress.value = _progress.value.copy(done = i + 1)
        }
        batchFinished.tryEmit(ImportBatchOutcome(successCount, failCount))
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

        val entry = ParsingBook(id = UUID.randomUUID().toString(), title = meta.title)
        _parsingBooks.update { it + entry }
        try {
            val imported = importer.import(file)
            Logger.i(TAG, "导入完成（新书=${imported.new}）")
            when (resolution) {
                DuplicateResolution.MERGE -> applyMerge(imported, matches)
                DuplicateResolution.OVERWRITE -> applyOverwrite(imported, matches)
                DuplicateResolution.KEEP_BOTH, DuplicateResolution.CANCEL -> Unit
            }
        } finally {
            // 成功（书架行已落库、事件已发）与失败（抛给上层计数）都要摘掉占位行；
            // 取消（页面不再可能，但作用域被外部取消时）同样不能把"解析中"留在架上
            _parsingBooks.update { list -> list.filterNot { it.id == entry.id } }
        }
        return true
    }

    /**
     * 暂停导入循环等用户处置，返回处置选择。
     *
     * `finally` 里复位状态：处置完（或作用域被取消）都不能让框留在架上，否则下一个文件的
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
            is ImportMergeResult.Merged ->
                notices.tryEmit(
                    if (outcome.appendedChapters > 0) ImportNotice.MergeAppended(outcome.appendedChapters)
                    else ImportNotice.MergeEquivalent
                )
            ImportMergeResult.Diverged -> notices.tryEmit(ImportNotice.MergeDiverged)
            ImportMergeResult.TargetNotLocal -> notices.tryEmit(ImportNotice.MergeTargetNotLocal)
            ImportMergeResult.EntryMissing -> notices.tryEmit(ImportNotice.MergeEntryMissing)
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

    private companion object {
        const val TAG = "LocalImportCoordinator"
    }
}
