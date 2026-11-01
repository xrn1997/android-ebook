package com.ebook.find.mvvm.viewmodel

import com.ebook.common.util.SingleNoticeSlot
import com.ebook.source.analyze.BookSourceNotFoundException
import kotlinx.coroutines.flow.StateFlow

/**
 * module_find 两个列表页（搜索页 / 分类选书页）的**一次性失败结论**。
 *
 * 宿主只发语义结论、不拼句子（ADR-0061）：文案由页面解析
 * （唯一映射处见 `com.ebook.find.view.findNoticeText`），页面句子优先进 `strings.xml`；
 * 通用失败档沿用异常自带的人话，取不到才用资源兜底。于是
 * - 结论可断言：基类的一次性命令通道（`sendToast` 往其中投递的 `uiAction`）在 lib_common 里是
 *   internal，测试观测不到，而这条流是公开读面，`AddBookFailureTest` 能在纯 JVM 上锁住分类本身；
 * - 带参/兜底模板都留在 `strings.xml` 里可译。
 *
 * **为什么两个页面共用一套结论与一个槽**：`SearchViewModel` 与 `ChoiceBookViewModel`
 * 走的是同一条加书架链路（`BookShelfManager.addFromSearch`），同一个失败在两个页面
 * 必须说同一句话。这段「异常类型 → 结论」的分支原先在两个 VM 里逐字重复过（收成一个
 * 函数正是为了防它漂移），分类逻辑因此仍只有一份主人，只是**产出的是结论而不是文案**。
 *
 * 上报口径不变：失败到页面后仍然经共享的 `reportFailure` 落地——「A0230 会话过期已由
 * 网络层全局处置、这里只记日志不重复提示」那条不变量由它收口，本模块不自己判会话过期，
 * 也不得手写 `isSessionExpiredHandled`。故每条结论都带 [cause]。
 */
internal sealed interface FindNotice {

    /** 结论对应的失败，页面据此走 `reportFailure` 的共享上报口径 */
    val cause: Throwable

    /**
     * 这本书（或这一页整段会话）绑定的书源已不在清单里，或那一行规则读不出。
     *
     * 单独一档而不是混进 [AddBookFailed]：它的 message 形如「书源已失效：<URL>」，
     * 直接上屏等于把内部标识甩给用户，且没告诉他该做什么——这条要的是**重导或换源**，
     * 重试无效。
     */
    data class SourceInvalid(override val cause: Throwable) : FindNotice

    /**
     * 其余加书架失败：沿用异常自身消息，消息为 null 时由页面兜底一句网络超时。
     *
     * [cause] 原样带着异常而不是在这里就把消息取走，是为了让页面的上报出口仍能拿到异常
     * （`reportFailure` 要先判会话过期）；文案取 `message` 这件事也归页面。
     */
    data class AddBookFailed(override val cause: Throwable) : FindNotice
}

/**
 * 异常 → 结论：纯函数、不碰 Context，可 JVM 直测（见 `AddBookFailureTest`）。
 *
 * 只有 [BookSourceNotFoundException] 需要换说法，其余一律 [FindNotice.AddBookFailed]。
 */
internal fun findNoticeOf(e: Throwable): FindNotice =
    if (e is BookSourceNotFoundException) FindNotice.SourceInvalid(e) else FindNotice.AddBookFailed(e)

/**
 * 一次性结论槽：VM 发射、页面取走并展示后清零。
 *
 * 承载机制复用共享的 [SingleNoticeSlot]（为什么是状态流而不是基类命令通道，见它）；
 * 本包装只多一件事——**加书架失败的分类口径**（[reportAddBookFailure]）由它单一主人，
 * 两个 VM 不再各写一遍分支（这正是本文件抽出来的原始动机）。
 */
internal class FindNoticeSlot {

    private val slot = SingleNoticeSlot<FindNotice>()

    /** 待页面消费的结论；null = 没有未展示的结论 */
    val notice: StateFlow<FindNotice?> = slot.notice

    /** 报告一次「把搜索结果加入书架」的失败：只分类，不选文案 */
    fun reportAddBookFailure(e: Throwable) {
        slot.emit(findNoticeOf(e))
    }

    /** 报告一条已经分类好的结论（分类列表那条路径的源失效直接给 [FindNotice.SourceInvalid]） */
    fun emit(notice: FindNotice) {
        slot.emit(notice)
    }

    /** 结论已被页面取走并展示：清零，避免重组时重复弹同一条 */
    fun consume() = slot.consume()
}
