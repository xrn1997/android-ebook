package com.ebook.book.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ChapterSelection.kt` 的纯逻辑用例：分组切分、组头三态、整组勾选、下载软上限。
 *
 * 这些语义算错既不会编译失败也不会闪退——只会静默给出错的内容（错位的组范围、少勾的一百章、
 * 该弹的确认没弹），所以集中在纯 JVM 上锁住（本仓 Compose 页面不做装机级单测，见 AGENTS.md）。
 */
class ChapterSelectionTest {

    @Test
    fun `分组按粒度整除时恰好切满`() {
        val groups = chapterGroups(3000)

        assertEquals(30, groups.size)
        assertEquals(ChapterGroup(index = 0, first = 0, last = 99), groups.first())
        assertEquals(ChapterGroup(index = 29, first = 2900, last = 2999), groups.last())
    }

    @Test
    fun `末组不足一组时如实收尾`() {
        val last = chapterGroups(3050).last()

        assertEquals(ChapterGroup(index = 30, first = 3000, last = 3049), last)
        assertEquals(50, last.count)
    }

    @Test
    fun `不足一组时只有一组`() {
        val groups = chapterGroups(37)

        assertEquals(1, groups.size)
        assertEquals(0, groups.single().first)
        assertEquals(36, groups.single().last)
        assertEquals(37, groups.single().count)
    }

    @Test
    fun `空目录不产出空组`() {
        // 空目录若产出「第 1-0 章」这种组，组头会渲染出无意义的范围文案
        assertTrue(chapterGroups(0).isEmpty())
        assertTrue(chapterGroups(-1).isEmpty())
    }

    @Test
    fun `组头三态按实际章数判定`() {
        val group = ChapterGroup(index = 0, first = 0, last = 2)

        assertEquals(GroupState.NONE, groupState(group, emptySet()))
        assertEquals(GroupState.PARTIAL, groupState(group, setOf(1)))
        assertEquals(GroupState.ALL, groupState(group, setOf(0, 1, 2)))
        assertEquals(GroupState.NONE, groupState(group, setOf(7)))
    }

    @Test
    fun `点未选或半选的组头是补齐整组`() {
        val group = ChapterGroup(index = 0, first = 0, last = 2)

        assertEquals(setOf(0, 1, 2), toggleGroup(group, emptySet()))
        // 半选态点击必须是「补齐」而不是「清空」：按清空处理会让用户看到已选章数不升反降
        assertEquals(setOf(0, 1, 2), toggleGroup(group, setOf(1)))
    }

    @Test
    fun `点已全选的组头是整组移除`() {
        val group = ChapterGroup(index = 0, first = 0, last = 2)

        assertEquals(setOf(9), toggleGroup(group, setOf(0, 1, 2, 9)))
    }

    @Test
    fun `下载软上限在五百章边界上放行`() {
        assertFalse(exceedsSelectionCap(0))
        assertFalse(exceedsSelectionCap(MAX_DOWNLOAD_SELECTION))
        assertTrue(exceedsSelectionCap(MAX_DOWNLOAD_SELECTION + 1))
    }
}
