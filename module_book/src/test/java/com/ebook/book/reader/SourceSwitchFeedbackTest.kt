package com.ebook.book.reader

import com.ebook.common.repository.BookAlreadyOnShelfException
import com.ebook.source.analyze.BookSourceNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 换源面板三张纯映射表的单元测试（ADR-0016 决策 8，P3-d）。
 *
 * 锁的是「上屏说哪句话」的判据，而不是句子本身：
 * - `matchBadgeOf`：分数落在哪一档标签（错档会把「书名相同」的候选说成「仅作者匹配」，
 *   用户就把它当不相干的书跳过了）；
 * - `switchFeedbackOf`：**判定顺序**——目录为空必须先于 clamped 命中，否则「一本都翻不出来」
 *   会被报成「已跳到第 N 章」这种报喜的话；
 * - `switchFailureHintOf`：书源失效与其余失败分两句（前者用户能自己处置：另选一本）。
 *
 * 纯 JVM 即可跑：三个函数都不碰 Android 资源（返回枚举而非资源 id 的理由见 SourceSwitchFeedback.kt）。
 */
class SourceSwitchFeedbackTest {

    @Test
    fun `书名与作者都相同的分数打完全匹配标签`(): Unit =
        assertEquals(MatchBadge.Exact, matchBadgeOf(100))

    @Test
    fun `同名与书名互相包含两档都归入部分匹配`(): Unit = listOf(80, 50).forEach { score ->
        assertEquals("分数 ${score} 应归入部分匹配", MatchBadge.Partial, matchBadgeOf(score))
    }

    @Test
    fun `仅作者相同打仅作者匹配标签`(): Unit =
        assertEquals(MatchBadge.AuthorOnly, matchBadgeOf(20))

    @Test
    fun `将来插入的新分数档落在相邻标签而不是最弱一档`(): Unit = listOf(90, 60, 30).forEach { score ->
        // 等值比较会让未知分数掉进 else（把好的说成差的），区间比较只会并进相邻档（标签偏宽）
        val expected = if (score >= 50) MatchBadge.Partial else MatchBadge.AuthorOnly
        assertEquals("分数 ${score} 的落档", expected, matchBadgeOf(score))
    }

    @Test
    fun `新目录一章都没有时报可疑结果`(): Unit =
        assertEquals(SwitchFeedback.EmptyCatalog, switchFeedbackOf(chapterCount = 0, clamped = false))

    @Test
    fun `目录为空时即使截断标记为真也先报可疑结果`(): Unit =
        // SourceSwitchOutcome.clamped 的定义要求 chapterCount > 0，故真机上不会出现这个组合；
        // 锁的是判定顺序：把 clamped 放在前面写，这条就会退成 Moved（对空目录报喜）
        assertEquals(SwitchFeedback.EmptyCatalog, switchFeedbackOf(chapterCount = 0, clamped = true))

    @Test
    fun `旧进度超出新目录长度时报落到最后一章`(): Unit =
        assertEquals(SwitchFeedback.Clamped, switchFeedbackOf(chapterCount = 120, clamped = true))

    @Test
    fun `进度照常落章时报已跳到第几章`(): Unit =
        assertEquals(SwitchFeedback.Moved, switchFeedbackOf(chapterCount = 120, clamped = false))

    @Test
    fun `目标书源不存在时报书源失效`(): Unit = assertEquals(
        SwitchFailureHint.SourceInvalid,
        switchFailureHintOf(BookSourceNotFoundException("https://a.example.com", detail = "换源目标"))
    )

    @Test
    fun `没有书源信息也归入书源失效一档`(): Unit = assertEquals(
        SwitchFailureHint.SourceInvalid,
        // tag 为空白的脏数据同样抛这个异常（消息是「这本书没有书源信息」），处置动作相同
        switchFailureHintOf(BookSourceNotFoundException(""))
    )

    @Test
    fun `网络与解析类失败归入兜底那句`(): Unit = assertEquals(
        SwitchFailureHint.Generic,
        switchFailureHintOf(IllegalStateException("读取超时"))
    )

    /**
     * 「目标那条本就在书架上」必须单独一档。
     *
     * 混进 [SwitchFailureHint.Generic] 的话上屏是「换源失败，书架上的书未受影响，可换个源再试」——
     * 用户照着去另选一本，而真答案是「这本你早有了，去读那一本」。仓库侧之所以要挡，
     * 是因为放行会 REPLACE 掉那本已有书的进度（见 [BookAlreadyOnShelfException]）。
     */
    @Test
    fun `目标已在书架上报独立那句而不是兜底失败`(): Unit = assertEquals(
        SwitchFailureHint.AlreadyOnShelf,
        switchFailureHintOf(BookAlreadyOnShelfException("https://b.example/book/99.html"))
    )
}
