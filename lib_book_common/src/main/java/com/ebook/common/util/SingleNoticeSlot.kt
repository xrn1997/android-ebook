package com.ebook.common.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一次性**语义结论**的承载槽：宿主（ViewModel / 控制器）往里发结论，页面取走并展示后清零。
 *
 * 这是「宿主只发语义结论、文案归页面」那条分层的机械支点（ADR-0061）：
 * 宿主手里只有异常与数字，句子由页面取字符串资源或沿用异常自带的人话。于是
 * - 切语言不残留旧文案，带参模板留在 `strings.xml` 可译；
 * - 结论可断言：基类的一次性命令通道（`sendToast` 往其中投递的 `uiAction`）在 lib_common 里是
 *   internal，测试观测不到（`SettingViewModelTest` 早记过这条），而本槽的 [notice] 是公开读面，
 *   纯 JVM 就能锁住分类本身；
 * - 「结论 → 文案」的映射在页面里写成不带 `else` 的 `when`，新增一档时编译不过，
 *   不会退化成运行时静默少弹一条。
 *
 * **为什么用 StateFlow 而不是 SharedFlow**：`LaunchedEffect(notice)` 要能在页面重组、
 * 转屏重建后仍读到那条还没展示的结论（SharedFlow 无重放时晚订阅即丢）。代价是**相等值
 * （含同一实例）在被 [consume] 之前重复发射会被 StateFlow 按 `equals` 判等吞掉**——所以消费
 * 必须紧跟展示；一次动作只应发一条待展示结论，连发两条不相等的结论时前一条可能被覆盖。
 *
 * @param T 结论类型（各页面的 sealed interface / enum）
 */
class SingleNoticeSlot<T : Any> {

    private val _notice = MutableStateFlow<T?>(null)

    /** 待页面消费的结论；null = 没有未展示的结论 */
    val notice: StateFlow<T?> = _notice.asStateFlow()

    /** 发一条结论给页面 */
    fun emit(value: T) {
        _notice.value = value
    }

    /** 结论已被页面取走并展示：清零，避免重组时把同一条再弹一遍 */
    fun consume() {
        _notice.value = null
    }
}
