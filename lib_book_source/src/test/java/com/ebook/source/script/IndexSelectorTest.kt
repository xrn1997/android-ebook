package com.ebook.source.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 规格 §4 索引语法表。 */
class IndexSelectorTest {

    @Test
    fun `单个非负索引`() {
        assertEquals(IndexSelector.At(listOf(0)), IndexSelector.parse("[0]"))
    }

    @Test
    fun `负索引保留原值由求值时换算`() {
        assertEquals(IndexSelector.At(listOf(-1)), IndexSelector.parse("[-1]"))
    }

    @Test
    fun `逗号索引集`() {
        assertEquals(IndexSelector.At(listOf(1, 3)), IndexSelector.parse("[1,3]"))
    }

    @Test
    fun `排除式索引在方括号形态下以逗号分隔`() {
        assertEquals(IndexSelector.Excluding(listOf(1, 3)), IndexSelector.parse("[!1,3]"))
    }

    @Test
    fun `排除式序号在位置段里以冒号分隔`() {
        // 规格 §2.5：!0:2:-1（排除第 1 个、第 3 个、最后一个）
        assertEquals(IndexSelector.Excluding(listOf(0, 2, -1)), IndexSelector.parse("!0:2:-1"))
    }

    @Test
    fun `闭区间`() {
        assertEquals(IndexSelector.Slice(2, 4, null), IndexSelector.parse("[2:4]"))
    }

    @Test
    fun `带步长的区间`() {
        assertEquals(IndexSelector.Slice(1, 9, 2), IndexSelector.parse("[1:9:2]"))
    }

    @Test
    fun `省略端点的区间`() {
        assertEquals(IndexSelector.Slice(null, 3, null), IndexSelector.parse("[:3]"))
        assertEquals(IndexSelector.Slice(2, null, null), IndexSelector.parse("[2:]"))
        assertEquals(IndexSelector.All, IndexSelector.parse("[:]"))
        assertEquals(IndexSelector.All, IndexSelector.parse("[]"))
    }

    @Test
    fun `反序区间`() {
        assertEquals(IndexSelector.Slice(-1, 0, null), IndexSelector.parse("[-1:0]"))
    }

    @Test
    fun `无方括号的裸位置`() {
        assertEquals(IndexSelector.At(listOf(1)), IndexSelector.parse("1"))
        assertEquals(IndexSelector.At(listOf(-1)), IndexSelector.parse("-1"))
    }

    @Test
    fun `非索引文本返回 null`() {
        assertNull(IndexSelector.parse("odd"))
        assertNull(IndexSelector.parse("class.odd"))
        assertNull(IndexSelector.parse(""))
        assertNull(IndexSelector.parse("[a]"))
        assertNull(IndexSelector.parse("[1:2:3:4]"))
    }

    @Test
    fun `端点带空格仍能解析`() {
        assertEquals(IndexSelector.Slice(1, 2, null), IndexSelector.parse("[ 1 : 2 ]"))
    }

    // ---- 裁剪语义（规格 §4 的「本仓规定」：端点裁剪、越界跳过、不抛） ----

    private val five = listOf("a", "b", "c", "d", "e")

    @Test
    fun `闭区间含两端`() {
        assertEquals(
            listOf("c", "d", "e"),
            five.selectIndices(IndexSelector.Slice(2, 4, null)),
        )
    }

    @Test
    fun `省略 end 即取到末尾`() {
        assertEquals(
            listOf("c", "d", "e"),
            five.selectIndices(IndexSelector.Slice(2, null, null)),
        )
    }

    @Test
    fun `负索引从尾数`() {
        assertEquals(listOf("e"), five.selectIndices(IndexSelector.At(listOf(-1))))
    }

    @Test
    fun `区间端点越界按边界裁剪`() {
        assertEquals(
            listOf("c", "d", "e"),
            five.selectIndices(IndexSelector.Slice(2, 999, null)),
        )
    }

    @Test
    fun `单个越界索引被跳过而不抛`() {
        assertEquals(
            listOf("a"),
            five.selectIndices(IndexSelector.At(listOf(0, 9, -9))),
        )
    }

    @Test
    fun `start 大于 end 表达反序`() {
        assertEquals(
            five.reversed(),
            five.selectIndices(IndexSelector.Slice(-1, 0, null)),
        )
    }

    @Test
    fun `步长按绝对值走`() {
        assertEquals(
            listOf("a", "c", "e"),
            five.selectIndices(IndexSelector.Slice(0, 4, 2)),
        )
    }

    @Test
    fun `排除式去掉指定项`() {
        assertEquals(
            listOf("b", "d"),
            five.selectIndices(IndexSelector.Excluding(listOf(0, 2, -1))),
        )
    }

    @Test
    fun `全部越界时返回空列表`() {
        assertEquals(emptyList<String>(), five.selectIndices(IndexSelector.At(listOf(7, 8))))
        assertEquals(emptyList<String>(), emptyList<String>().selectIndices(IndexSelector.All))
    }
}
