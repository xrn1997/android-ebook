package com.ebook.me.mvvm.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.ebook.me.R
import com.ebook.me.repository.CacheEntry
import com.ebook.me.repository.CacheModel
import com.ebook.me.repository.CacheType
import com.ebook.me.repository.formatSize
import com.xrn1997.common.mvvm.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 缓存管理页 ViewModel。
 *
 * 职责：分类统计 cacheDir 占用（图片缓存/临时文件/其他），
 * 支持按类单独清理与全量清理，每次清理后自动重算明细。
 * 清理动作可安全重入（目录不存在时 deleteRecursively 无副作用）。
 * 清理成功提示经基类 [com.xrn1997.common.mvvm.viewmodel.BaseViewModel.sendToast] 下发（文案走资源）。
 */
@HiltViewModel
class CacheManageViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cacheModel: CacheModel,
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
     * 清理单个分类后重算明细，并关闭该分类的 BottomSheet。
     *
     * 仅由 Sheet 内「清理」按钮触发——用户已看清将删除的内容，无需二次确认。
     * 成功经 [com.xrn1997.common.mvvm.viewmodel.BaseViewModel.sendToast] 提示（含分类名，文案走资源）。
     */
    fun clearCategory(type: CacheType) {
        viewModelScope.launch {
            when (type) {
                CacheType.IMAGE -> cacheModel.clearImageCache()
                CacheType.TEMP -> cacheModel.clearTempFiles()
                CacheType.OTHER -> cacheModel.clearOtherCache()
            }
            dismissDetail()
            refreshInternal()
            sendToast(context.getString(R.string.cache_cleared_category, context.getString(categoryTitleRes(type))))
        }
    }

    /** 清理全部缓存（含未识别目录与根目录松散文件）后重算明细 */
    fun clearAll() {
        viewModelScope.launch {
            cacheModel.clearCache()
            refreshInternal()
            sendToast(context.getString(R.string.cache_cleared_all))
        }
    }

    /** 挂起版重算：供清理流程内联调用，保证事件发出时状态已更新 */
    private suspend fun refreshInternal() {
        val breakdown = cacheModel.cacheBreakdown()
        _cacheState.value = CacheUiState(
            items = listOf(
                CacheItemState(CacheType.IMAGE, formatSize(breakdown.imageBytes)),
                CacheItemState(CacheType.TEMP, formatSize(breakdown.tempBytes)),
                CacheItemState(CacheType.OTHER, formatSize(breakdown.otherBytes)),
            ),
            totalText = formatSize(breakdown.totalBytes),
            totalBytes = breakdown.totalBytes,
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
