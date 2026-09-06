package com.ebook.book.mvvm.viewmodel

import androidx.lifecycle.viewModelScope
import com.ebook.book.R
import com.ebook.common.repository.BookRepository
import com.ebook.common.repository.CommentRepository
import com.xrn1997.common.BaseApplication.Companion.context
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
 * 修键面板 ViewModel（spec §9.3）。
 *
 * 展示当前书的匹配名/作者/主键/已关联键列表。用户编辑匹配名/作者后：
 * 1. 重算评论键（CommentKey.compute）
 * 2. 旧主键降级、新键成为主键
 * 3. 迁移本人旧评论到新键桶
 *
 * Model 位用 [NoOpModel] 占位（无一次性命令门面需求，见 AGENTS.md MVVM 约定）。
 */
@HiltViewModel
class EditBookMetaViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val commentRepository: CommentRepository,
) : BaseViewModel<NoOpModel>(NoOpModel()) {

    /** 页面状态流。不叫 `uiState`——那是基类覆盖层专用（见 AGENTS.md MVVM 约定） */
    private val _editMetaState = MutableStateFlow(EditBookMetaState())
    val editMetaState: StateFlow<EditBookMetaState> = _editMetaState.asStateFlow()

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
                val rows = bookRepository.getBookGroupRows(noteUrl)
                val shelf = bookRepository.getBookByUrl(noteUrl)
                val primary = rows.firstOrNull { it.isPrimary }?.commentKey
                _editMetaState.value = EditBookMetaState(
                    matchName = shelf?.matchName ?: shelf?.bookInfo?.name ?: "",
                    matchAuthor = shelf?.matchAuthor ?: shelf?.bookInfo?.author ?: "",
                    primaryKey = primary ?: "",
                    associatedKeys = rows.filter { !it.isPrimary }.map { it.commentKey },
                )
            } catch (e: Exception) {
                Logger.e(TAG, "loadState 失败", e)
            }
        }
    }

    /**
     * 保存：重算键 → 切主键 → 迁移本人评论。
     *
     * 调用 BookRepository.updateMatchMeta 重算键（旧主键降级，新键成为主键）。
     * 若键发生变化，再调 CommentRepository.migrateMyComments 迁移本人旧评论。
     * 完成后发 toast 提示迁移条数，然后 sendFinish 关闭面板。
     *
     * @param newMatchName 用户编辑后的主匹配名
     * @param newMatchAuthor 用户编辑后的匹配作者（可空）
     */
    fun save(newMatchName: String, newMatchAuthor: String) {
        viewModelScope.launch {
            try {
                val (oldKey, newKey) = bookRepository.updateMatchMeta(
                    noteUrl, newMatchName, newMatchAuthor
                )
                if (oldKey != newKey) {
                    val result = commentRepository.migrateMyComments(oldKey, newKey)
                    result.onSuccess { migratedCount ->
                        sendToast(
                            context.getString(R.string.edit_book_meta_migrated_toast, migratedCount)
                        )
                    }
                    // 键已经切过去了、评论还留在旧桶：这个不一致只有用户能补救（再改回去或手动
                    // 合并），不提示就是把它藏起来。
                    result.onFailure {
                        sendToast(context.getString(R.string.edit_book_meta_migrate_failed))
                    }
                }
                loadState()
                sendFinish()
            } catch (e: Exception) {
                Logger.e(TAG, "save 失败", e)
            }
        }
    }

    /**
     * 拆分：从并集里删掉一个 secondary 键。
     *
     * 不允许删主键（BookRepository.splitBook 内部有保护）。
     * 删完后重新加载状态以刷新 UI 列表。
     *
     * @param keyToRemove 要移除的 secondary 评论键
     */
    fun removeAssociatedKey(keyToRemove: String) {
        viewModelScope.launch {
            try {
                bookRepository.splitBook(noteUrl, keyToRemove)
                loadState()
            } catch (e: Exception) {
                Logger.e(TAG, "removeAssociatedKey 失败", e)
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
