package com.ebook.me.mvvm.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.ebook.common.store.BookStore
import com.ebook.me.R
import com.ebook.me.repository.CacheEntry
import com.ebook.me.repository.CacheModel
import com.ebook.me.repository.CacheType
import com.ebook.me.repository.formatSize
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import com.xrn1997.common.mvvm.viewmodel.Overlay
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 缓存管理页 ViewModel。
 *
 * 职责：分类统计 cacheDir 占用（图片缓存/临时文件/其他），
 * 支持按类单独清理与全量清理，每次清理后自动重算明细。
 * 另单列**书籍内容**占用与册数（[BookStore.storageUsage]，一次遍历）：它住在 `filesDir/books`，往往比缓存多一个
 * 量级，不显示用户会以为本页就是全部占用。它**不参与本类的任何清理编排**（既不计入可清理总量，
 * 本页也不删书——删书的唯一入口是书架长按），路由可达时那一行只是把人送到书架。
 * 同一时刻只放行一笔清理（[clearCategory] 与 [clearAll] 共用一道闸门）——
 * 目录删第二次是无害的，有害的是重复的编排与重复的成功提示。
 * 清理成功提示经基类 [com.xrn1997.common.mvvm.viewmodel.BaseViewModel.sendToast] 下发（文案走资源）。
 */
@HiltViewModel
class CacheManageViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cacheModel: CacheModel,
    private val bookStore: BookStore,
) : BaseViewModel<CacheModel>(cacheModel) {

    /** 分类条目 UI 状态：类型 + 可读大小（展示文案在 Screen 层按类型映射） */
    data class CacheItemState(val type: CacheType, val sizeText: String)

    /**
     * 页面整体状态：分类明细 + 总占用。
     *
     * totalText 为空串表示「计算中」，占位文案（--）由 UI 层经资源解析；
     * totalBytes 驱动「清理全部」按钮可用态。
     */
    data class CacheUiState(
        val items: List<CacheItemState> = emptyList(),
        val totalText: String = "",
        val totalBytes: Long = 0L,
        /** 书籍内容占用（不参与清理、不计入 [totalBytes]：删书只在书架长按；这一行可点回书架） */
        val booksSizeText: String = "",
        /** 藏书数（与 [booksSizeText] 同一行呈现；不含导入中断的 `.tmp` 半成品） */
        val bookCount: Int = 0,
    )

    /**
     * 分类明细 BottomSheet 状态：null 表示关闭。
     *
     * 打开时先置 loading 态（IO 列目录耗时），列表到达后展示文件/目录明细，
     * 用户在看清内容后通过底部按钮显式触发清理。
     */
    data class CacheDetailState(
        val type: CacheType,
        val entries: List<CacheEntry> = emptyList(),
        val sizeText: String = "",
        val loading: Boolean = true,
    )

    // 命名避开基类的 uiState（BaseViewModel.uiState 驱动加载/错误覆盖层，语义不同）
    private val _cacheState = MutableStateFlow(CacheUiState())
    val cacheState: StateFlow<CacheUiState> = _cacheState.asStateFlow()

    private val _detailState = MutableStateFlow<CacheDetailState?>(null)
    val detailState: StateFlow<CacheDetailState?> = _detailState.asStateFlow()

    init {
        refresh()
    }

    /** 重算分类明细（进入页面 / 外部触发刷新时调用） */
    fun refresh() {
        viewModelScope.launch { refreshInternal() }
    }

    /** 打开某分类的明细 BottomSheet（条目在 IO 线程加载） */
    fun openDetail(type: CacheType) {
        _detailState.value = CacheDetailState(type = type, sizeText = currentSizeText(type))
        viewModelScope.launch {
            val entries = cacheModel.cacheEntries(type)
            // 用户可能已关闭 Sheet，仅仍在查看该分类时更新，避免覆盖新打开的分类
            if (_detailState.value?.type == type) {
                _detailState.value = CacheDetailState(
                    type = type,
                    entries = entries,
                    sizeText = formatSize(entries.sumOf { it.sizeBytes }),
                    loading = false,
                )
            }
        }
    }

    /** 关闭明细 BottomSheet */
    fun dismissDetail() {
        _detailState.value = null
    }

    /** 当前分类在列表页的大小文案（Sheet 打开瞬间先用它，列表加载后替换为精确合计） */
    private fun currentSizeText(type: CacheType): String =
        _cacheState.value.items.firstOrNull { it.type == type }?.sizeText ?: ""

    /**
     * 清理在途闸门：连点「清理」/「清理全部」只放行一次。
     *
     * 文件删除本身幂等（目录已不在时 `deleteRecursively` 无副作用），要挡的是**重复的编排**：
     * 两笔清理各发一条成功提示，第二笔还会在状态已刷新之后把结果再推一遍。
     */
    private var clearInProgress = false

    /**
     * 清理单个分类后重算明细，并关闭该分类的 BottomSheet。
     *
     * 仅由 Sheet 内「清理」按钮触发——用户已看清将删除的内容，无需二次确认。
     * 成功经 [com.xrn1997.common.mvvm.viewmodel.BaseViewModel.sendToast] 提示（含分类名，文案走资源）。
     * 清理期间挂 [Overlay.Loading]：大缓存的递归删除要跑上一阵，没有等待态就是「按了没反应」。
     */
    fun clearCategory(type: CacheType) {
        if (clearInProgress) return
        clearInProgress = true
        viewModelScope.launch {
            updateOverlay(Overlay.Loading)
            try {
                when (type) {
                    CacheType.IMAGE -> cacheModel.clearImageCache()
                    CacheType.TEMP -> cacheModel.clearTempFiles()
                    CacheType.OTHER -> cacheModel.clearOtherCache()
                }
                dismissDetail()
                refreshInternal()
                sendToast(context.getString(R.string.cache_cleared_category, context.getString(categoryTitleRes(type))))
            } finally {
                updateOverlay(Overlay.None)
                clearInProgress = false
            }
        }
    }

    /** 清理全部缓存（含未识别目录与根目录松散文件）后重算明细；闸门与等待态同 [clearCategory] */
    fun clearAll() {
        if (clearInProgress) return
        clearInProgress = true
        viewModelScope.launch {
            updateOverlay(Overlay.Loading)
            try {
                cacheModel.clearCache()
                refreshInternal()
                sendToast(context.getString(R.string.cache_cleared_all))
            } finally {
                updateOverlay(Overlay.None)
                clearInProgress = false
            }
        }
    }

    /** 挂起版重算：供清理流程内联调用，保证事件发出时状态已更新 */
    private suspend fun refreshInternal() {
        val breakdown = cacheModel.cacheBreakdown()
        // 书籍的占用与册数由内容仓库一次遍历给出（它才知道目录形状）
        val usage = withContext(Dispatchers.IO) { bookStore.storageUsage() }
        _cacheState.value = CacheUiState(
            items = listOf(
                CacheItemState(CacheType.IMAGE, formatSize(breakdown.imageBytes)),
                CacheItemState(CacheType.TEMP, formatSize(breakdown.tempBytes)),
                CacheItemState(CacheType.OTHER, formatSize(breakdown.otherBytes)),
            ),
            totalText = formatSize(breakdown.totalBytes),
            totalBytes = breakdown.totalBytes,
            booksSizeText = formatSize(usage.bytes),
            bookCount = usage.bookCount,
        )
    }
}

/**
 * 分类标题文案资源（VM 与 UI 共用，单一事实）。
 * 原实现位于 CacheManageActivity，收编事件总线后上移。
 */
internal fun categoryTitleRes(type: CacheType): Int = when (type) {
    CacheType.IMAGE -> R.string.cache_category_image
    CacheType.TEMP -> R.string.cache_category_temp
    CacheType.OTHER -> R.string.cache_category_other
}
