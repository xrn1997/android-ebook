package com.ebook.common.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [FileNameMetadata] 单测。模式集合覆盖本地书文件名的四种真实写法与两种落空情况。
 *
 * 解析不出作者是常态而非异常：返回 null，由显示层填占位词；占位词绝不参与键计算
 * （见 CommentKeyTest 的对应断言）。
 */
class FileNameMetadataTest {

    @Test
    fun parsesTitleInBookMarksWithAuthorPrefix() {
        val r = FileNameMetadata.parse("网络小说《星辰变》作者：我吃西红柿")
        assertEquals("星辰变", r.title)
        assertEquals("我吃西红柿", r.author)
    }

    @Test
    fun parsesBareTitleWithAuthorPrefix() {
        val r = FileNameMetadata.parse("斗破苍穹 作者：天蚕土豆")
        assertEquals("斗破苍穹", r.title)
        assertEquals("天蚕土豆", r.author)
    }

    @Test
    fun parsesEnglishByForm() {
        val r = FileNameMetadata.parse("The Hobbit by Tolkien")
        assertEquals("The Hobbit", r.title)
        assertEquals("Tolkien", r.author)
    }

    @Test
    fun parsesBookMarksOnly() {
        val r = FileNameMetadata.parse("《凡人修仙传》")
        assertEquals("凡人修仙传", r.title)
        assertNull(r.author)
    }

    @Test
    fun fallsBackToWholeNameWhenNothingMatches() {
        val r = FileNameMetadata.parse("星辰变 全文 无删减")
        assertEquals("星辰变 全文 无删减", r.title)
        assertNull(r.author)
    }

    @Test
    fun stripsExtensionCaseInsensitively() {
        assertEquals("书名", FileNameMetadata.parse("书名.TXT").title)
        assertEquals("书名", FileNameMetadata.parse("书名.txt").title)
    }

    @Test
    fun stripsTrailingBracketedNoise() {
        assertEquals("剑来", FileNameMetadata.parse("剑来 (起点小说 2024-01-01)").title)
        assertEquals("剑来", FileNameMetadata.parse("剑来【完结】").title)
    }

    @Test
    fun authorWithHalfwidthColonIsParsed() {
        val r = FileNameMetadata.parse("赘婿 作者: 愤怒的香蕉")
        assertEquals("赘婿", r.title)
        assertEquals("愤怒的香蕉", r.author)
    }
}
