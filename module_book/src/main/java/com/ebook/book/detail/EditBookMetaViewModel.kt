package com.ebook.book.detail

import androidx.lifecycle.viewModelScope
import com.ebook.common.repository.CommentKeys
import com.ebook.common.repository.CommentRepository
import com.ebook.common.repository.ShelfRepository
import com.ebook.common.util.SingleNoticeSlot
import com.xrn1997.common.mvvm.model.NoOpModel
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import com.xrn1997.common.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 修键面板的一次性**语义结论**：只带语义与数字，句子归 [EditBookMetaActivity]（ADR-0061）。
 *
 * 两类出口都在页面按分支分流（映射不带 `else`，新增一档编译不过）：
 * - [LoadFailed] / [SaveFailed] / [RemoveFailed] 是**失败**，页面仍走共享的
 *   `reportFailure(cause, 文案)`——「A0230 会话过期已由网络层全局处置、这里只记日志
 *   不重复提示」那条不变量由它收口，本类不自己判会话过期；
 * - [CommentsMigrated] / [Saved] 是「做完了什么」的回显，[MigrateFailed] 是无 cause 的半成功；
 *   页面一律走基类 `sendToast` 命令通道，不进失败上报。
 *
 * @param cause 只在失败三档带着：文案可以换，异常必须是同一个（`reportFailure` 要先判类型）
 */
sealed interface EditBookNotice {

    /** 匹配信息与关联键没读出来（Room / 仓库异常） */
    data class LoadFailed(val cause: Throwable) : EditBookNotice

    /** 键已变化、本人评论迁移成功：[count] 是迁移条数（`edit_book_meta_migrated_toast` 的占位） */
    data class CommentsMigrated(val count: Int) : EditBookNotice

    /**
     * 键已切过去、评论还留在旧桶。
     *
     * 这一档刻意**不**并进失败三档、也不带 cause：它不是「动作没生效」，而是「一半生效」，
     * 用户必须知道这个不一致（再改回去或手动合并）；改动前它也是无条件出一条提示，
     * 换成 `reportFailure` 会让会话过期那一路变成静默——那是行为变更，不在本次范围内。
     */
    data object MigrateFailed : EditBookNotice

    /** 键等价（只更新元数据、没有迁移发生）：面板会静默关闭，得有一条确认 */
    data object Saved : EditBookNotice

    /** 保存流程整体失败（键没动、数据没改，面板保持打开） */
    data class SaveFailed(val cause: Throwable) : EditBookNotice

    /** 移除关联键失败 */
    data class RemoveFailed(val cause: Throwable) : EditBookNotice
}

/**
 * 修键面板 ViewModel（spec §9.3）。
 *
 * 展示当前书的匹配名/作者/主键/已关联键列表。用户编辑匹配名/作者后：
 * 1. 重算评论键（CommentKey.compute）
 * 2. 旧主键降级、新键成为主键
 * 3. 迁移本人旧评论到新键桶
 *
 * 带 cause 的失败三档（[EditBookNotice.LoadFailed] / [EditBookNotice.SaveFailed] /
 * [EditBookNotice.RemoveFailed]）交回 [reportFailure] 的上报口径（不是只记日志）；半成功/回显走
 * 命令通道（`sendToast`），关页由页面在提示之后发起。本页每个动作都会改动落库数据，
 * 静默失败时用户看到的是「点了没反应、数据其实没动」，比任何一次提示都难排查。
 * 本类因此只发 [EditBookNotice]（语义 + cause），**文案由页面拼**；上报出口仍在失败路径上，
 * 只是从本类移到了页面（ADR-0061）。同时保留一条带栈的日志——提示只是给用户的，排查要靠日志。
 *
 * Model 位用 [NoOpModel] 占位（无一次性命令门面需求，见 docs/architecture.md「Hilt 与 Application」）。
 */
@HiltViewModel
class EditBookMetaViewModel @Inject constructor(
    private val commentKeys: CommentKeys,
    private val bookShelfRepository: ShelfRepository,
    private val commentRepository: CommentRepository,
) : BaseViewModel<NoOpModel>(NoOpModel()) {

    /** 页面状态流。不叫 `uiState`——那是基类覆盖层专用（见 docs/architecture.md「Hilt 与 Application」） */
    private val _editMetaState = MutableStateFlow(EditBookMetaState())
    val editMetaState: StateFlow<EditBookMetaState> = _editMetaState.asStateFlow()

    /** 本面板的一次性结论（文案与出口在页面，见 [EditBookNotice]） */
    private val notices = SingleNoticeSlot<EditBookNotice>()

    /** 待页面消费的结论；页面展示后经 [consumeNotice] 清零 */
    val notice: StateFlow<EditBookNotice?> = notices.notice

    /** 结论已被页面取走并展示 */
    fun consumeNotice() = notices.consume()

    /** 当前书的 noteUrl，由 Activity initData 从路由参数写入 */
    var noteUrl: String = ""

    /**
     * 加载当前书的匹配信息与关联键列表。
     *
     * 从 book_shelf 取 matchName/matchAuthor（展示输入框的初始值），
     * 从 book_group 取全部键行（区分主键与 secondary 供列表展示）。
     */
    fun loadState() {
        viewModelScope.launch {
            try {
                val rows = commentKeys.getBookGroupRows(noteUrl)
                val shelf = bookShelfRepository.getBookByUrl(noteUrl)
                val primary = rows.firstOrNull { it.isPrimary }?.commentKey
                _editMetaState.value = EditBookMetaState(
                    matchName = shelf?.matchName ?: shelf?.bookInfo?.name ?: "",
                    matchAuthor = shelf?.matchAuthor ?: shelf?.bookInfo?.author ?: "",
                    primaryKey = primary ?: "",
                    associatedKeys = rows.filter { !it.isPrimary }.map { it.commentKey },
                )
            } catch (e: Exception) {
                Logger.e(TAG, "loadState 失败", e)
                notices.emit(EditBookNotice.LoadFailed(e))
            }
        }
    }

    /**
     * 保存：重算键 → 切主键 → 迁移本人评论。
     *
     * 调用 CommentKeys.updateMatchMeta 重算键（旧主键降级，新键成为主键）。
     * 若键发生变化，再调 CommentRepository.migrateMyComments 迁移本人旧评论。
     * 键变化时发 [EditBookNotice.CommentsMigrated] 带迁移条数，键等价（仅元数据落库）时发
     * [EditBookNotice.Saved] 确认。面板的关闭由页面在展示完结论之后发起（提示必须先于关页，
     * 原因见 [EditBookMetaActivity] 的消费处）。
     *
     * @param newMatchName 用户编辑后的主匹配名
     * @param newMatchAuthor 用户编辑后的匹配作者（可空）
     */
    fun save(newMatchName: String, newMatchAuthor: String) {
        viewModelScope.launch {
            try {
                val (oldKey, newKey) = commentKeys.updateMatchMeta(
                    noteUrl, newMatchName, newMatchAuthor
                )
                if (oldKey != newKey) {
                    val result = commentRepository.migrateMyComments(oldKey, newKey)
                    result.onSuccess { migratedCount ->
                        notices.emit(EditBookNotice.CommentsMigrated(migratedCount))
                    }
                    // 键已经切过去了、评论还留在旧桶：这个不一致只有用户能补救（再改回去或手动
                    // 合并），不提示就是把它藏起来。
                    result.onFailure {
                        notices.emit(EditBookNotice.MigrateFailed)
                    }
                } else {
                    // 键等价：迁移必然是零条、无迁移提示，页面静默关闭会被当成「没保存成功」——
                    // 补一条确认（元数据此时也已落库，见 CommentKeys.updateMatchMeta）
                    notices.emit(EditBookNotice.Saved)
                }
                loadState()
                // 不 sendFinish：结论要等页面下一帧重组才展示，而命令通道在 STARTED 里由
                // MvvmBinder 同步消费——在这里发关页会抢在提示之前，提示随页面一起消失
                // （SettingViewModel 的登出路径记过同型陷阱）。关页改由页面在提示之后发起。
            } catch (e: Exception) {
                Logger.e(TAG, "save 失败", e)
                // 失败不关页（页面只对成功/半成功发 sendFinish）：关掉面板会让用户以为改成功了
                // （数据其实没动）
                notices.emit(EditBookNotice.SaveFailed(e))
            }
        }
    }

    /**
     * 拆分：从并集里删掉一个 secondary 键。
     *
     * 不允许删主键（CommentKeys.splitBook 内部有保护）。
     * 删完后重新加载状态以刷新 UI 列表。
     *
     * @param keyToRemove 要移除的 secondary 评论键
     */
    fun removeAssociatedKey(keyToRemove: String) {
        viewModelScope.launch {
            try {
                commentKeys.splitBook(noteUrl, keyToRemove)
                loadState()
            } catch (e: Exception) {
                Logger.e(TAG, "removeAssociatedKey 失败", e)
                notices.emit(EditBookNotice.RemoveFailed(e))
            }
        }
    }
}

/** 修键面板 UI 状态 */
data class EditBookMetaState(
    /** 主匹配名（输入框初始值） */
    val matchName: String = "",
    /** 匹配作者（输入框初始值，可空） */
    val matchAuthor: String = "",
    /** 当前主键（只读展示） */
    val primaryKey: String = "",
    /** 已关联的 secondary 键列表（合并历史，可移除） */
    val associatedKeys: List<String> = emptyList(),
)
