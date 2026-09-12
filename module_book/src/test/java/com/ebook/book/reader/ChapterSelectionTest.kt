package com.ebook.book.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ChapterSelection.kt` 的纯逻辑用例：分组切分、组头三态、整组勾选、下载软上限、
 * 倒序索引换算与组头行号。
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

    @Test
    fun `倒序换算与正序换算互为逆运算`() {
        val count = 10

        for (position in 0 until count) {
            assertEquals(position, originalIndexAt(count, descending = false, position = position))
        }
        for (index in 0 until count) {
            assertEquals(
                index,
                displayPositionOf(count = count, descending = false, originalIndex = index),
            )
        }
        for (index in 0 until count) {
            assertEquals(
                index,
                originalIndexAt(
                    count = count,
                    descending = true,
                    position = displayPositionOf(count, descending = true, originalIndex = index),
                ),
            )
        }
    }

    @Test
    fun `倒序时首行是最新章`() {
        assertEquals(2999, originalIndexAt(count = 3000, descending = true, position = 0))
        assertEquals(0, originalIndexAt(count = 3000, descending = true, position = 2999))
    }

    @Test
    fun `组头行号等于排在它前面的组头行与已展开组的章行之和`() {
        val groups = chapterGroups(300) // 3 组，每组恰好 100 章

        // 无展开：组 2 的组头紧跟在组 0、组 1 的组头之后
        assertEquals(2, rowIndexOfGroup(groups, emptySet(), 2))
        // 组 0 展开：组 2 的组头前多了组 0 的 100 行章行
        assertEquals(102, rowIndexOfGroup(groups, setOf(0), 2))
        // 组 0、1 都展开
        assertEquals(202, rowIndexOfGroup(groups, setOf(0, 1), 2))
        // 只展开自己、或只展开排在后面的组，都不影响自己的组头行号
        assertEquals(2, rowIndexOfGroup(groups, setOf(2), 2))
    }

    @Test
    fun `非默认粒度下组序号仍按序号推导而不是列表下标`() {
        // 现有用例都走默认粒度 100，index 恰好等于列表下标，掩盖了 `index = start / size`
        // 这条推导——粒度非整除时两者仍相等，但这条断言把推导本身钉住
        val groups = chapterGroups(total = 20, size = 7)

        assertEquals(3, groups.size)
        assertEquals(listOf(0, 1, 2), groups.map { it.index })
        assertEquals(ChapterGroup(index = 2, first = 14, last = 19), groups.last())
        assertEquals(6, groups.last().count)
    }

    @Test
    fun `分组粒度非正数时拒绝`() {
        assertThrows(IllegalArgumentException::class.java) { chapterGroups(10, size = 0) }
    }
}
