package com.ebook.book.reader

import com.ebook.common.repository.BookAlreadyOnShelfException
import com.ebook.source.analyze.BookSourceNotFoundException

/**
 * 换源面板的纯判定：把「分数 → 标签档」「换源现场 → 反馈句」「异常 → 提示档」三张映射表从
 * Compose 里拆出来（ADR-0016 决策 8，P3-d）。
 *
 * 拆出来只有一个理由：这三条决定**上屏说哪句话**，说错一句就会误导用户（把「新源一章都没解出来」
 * 报成「已跳到第 N 章」等于把坏消息藏起来）。它们全是纯函数，直接 JVM 单测即可锁住，
 * 不必为几条 if 去写 Compose UI 测试（本仓 Compose 页面不做装机级单测，见 AGENTS.md 测试约定）。
 *
 * 返回值刻意用枚举而不是 `@StringRes` 整型 id：id 要在 Robolectric 下才取到（`SourceSwitchViewModelTest`
 * 即为取资源文案而挂 Robolectric），枚举在纯 JVM 就能断言；枚举与文案的配对由 UI 里
 * **穷尽 when** 保证——漏一档编译不过，所以配对不会被测漏。
 */

/** 候选与当前书的匹配度标签档，依据 `SourceSwitchViewModel.matchScore` 的五档分数。 */
enum class MatchBadge {
    /** 书名 + 作者都相同（100 分） */
    Exact,

    /** 书名相同或互相包含（80 / 50 分） */
    Partial,

    /** 只有作者相同（20 分） */
    AuthorOnly,
}

/**
 * 分数 → 标签档。
 *
 * 用区间而不是等值比较：分档只在 ViewModel 那一处定义，UI 不该跟着它一起改。将来加一档（如 90）
 * 时它落在正确的区间里，不至于掉进「仅作者匹配」这种更弱的说法（等值比较的失败方向是「把好的
 * 说成差的」，区间比较的失败方向是「把新的并进相邻档」——后者顶多标签偏宽，前者会让人误弃好候选）。
 * 低于 20 的分数根本不会进候选（0 分在 VM 里已被滤掉），兜底走 [MatchBadge.AuthorOnly]。
 */
internal fun matchBadgeOf(score: Int): MatchBadge = when {
    score >= 100 -> MatchBadge.Exact
    score >= 50 -> MatchBadge.Partial
    else -> MatchBadge.AuthorOnly
}

/** 换源成功后要说给人看的三句话。 */
enum class SwitchFeedback {

    /** 正常：按旧进度落到新源的某一章 */
    Moved,

    /** 旧进度超出新目录长度，已截到新源最后一章 */
    Clamped,

    /** 新源一章都没解出来：库里换源已成功，但阅读器翻不出内容 */
    EmptyCatalog,
}

/**
 * 换源现场 → 反馈句。
 *
 * **先判 [chapterCount] 再判 [clamped]**，顺序是这条映射的全部信息量所在：
 * `clamped` 的定义要求 `chapterCount > 0`（旧进度只有在新目录非空时才谈得上「被截断」），
 * 目录为空时它恒为 false。先判 clamped 就等于让「一本都翻不出来的书」走进 [SwitchFeedback.Moved]，
 * 用户看到的是一句报喜的话和一页空白。
 */
internal fun switchFeedbackOf(chapterCount: Int, clamped: Boolean): SwitchFeedback = when {
    chapterCount <= 0 -> SwitchFeedback.EmptyCatalog
    clamped -> SwitchFeedback.Clamped
    else -> SwitchFeedback.Moved
}

/** 换源失败时在面板内提示的档位。 */
enum class SwitchFailureHint {

    /** 目标书源已失效 / 不存在：用户能自己处置（另选一本，或回书源管理页重导） */
    SourceInvalid,

    /**
     * 目标那条书**本来就在书架上**（换源被仓库挡下）：动作不是「另选一本」，而是去读架上那本。
     *
     * 不能并入 [Generic]：那句是「换源失败，书架上的书未受影响，可换个源再试」，
     * 用户照做就会在同一堆候选里再找一次这本书，而真答案是他已经有了这一本。
     */
    AlreadyOnShelf,

    /** 其余失败（网络、解析、写库）：兜底那句要交代「书架未受影响」 */
    Generic,

    /**
     * 候选那一轮整条聚合流出问题（如查书源清单失败）：一笔换源都没发生，那句「书架上的书未受影响」
     * 在这种场景里答非所问，故单列一档。单源失败不走这里（它被收敛成事件、只记日志）。
     */
    SearchFailed,
}

/**
 * 异常 → 提示档。
 *
 * 为什么面板要自己显示失败而不是靠 ViewModel 的 `reportFailure`：后者经基类的一次性命令通道
 * （`sendToast`）出口，而该通道只有**被 `MvvmBinder` 绑定的那个 ViewModel** 会被收集
 * （本宿主绑的是 `BookReadViewModel`，`SourceSwitchViewModel` 不在其中）——它的命令只会堆在
 * Channel 里随 VM 销毁丢弃，即 `DownloadManageViewModel` 注释里点过的同一条陷阱。
 * **面板内联因此是本条链路上唯一的提示出口**：`SourceSwitchViewModel` 里那条 `reportFailure`
 * 已于 2026-09-08 删除（写了等于没写），失败原因一律经 VM 状态与 `Result` 交到这里。
 * 另：`SearchFailed` 不走本函数——它由「候选那一轮出了失败状态」这一事实直接给出，
 * 与异常类型无关（能整流出问题的异常种类不值得为它分文案）。
 */
internal fun switchFailureHintOf(e: Throwable): SwitchFailureHint = when (e) {
    is BookSourceNotFoundException -> SwitchFailureHint.SourceInvalid
    // 必须先于兜底分支：这一档的动作是「去读架上那本」，混进 Generic 就等于指错路
    is BookAlreadyOnShelfException -> SwitchFailureHint.AlreadyOnShelf
    else -> SwitchFailureHint.Generic
}
