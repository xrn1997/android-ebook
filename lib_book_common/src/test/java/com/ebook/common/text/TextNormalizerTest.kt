package com.ebook.common.text

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TextNormalizer] 单测。锁两件事：
 * 1) 行内空白折叠为一个空格而**不是删光**——旧实现 `.replace(" ", "")` 配合
 *    `.replace("\\s*".toRegex(), "")`（BookImportManager（已删除）:159-161）会永久销毁
 *    `1 000`、英文书名、代码里的空格；
 * 2) 段落缩进只出现在 toDisplayText，不出现在段落数据里——这是"存储层不清洗"的前提，
 *    也让段评锚点（spec §9.1）不被表现层字符污染。
 *
 * BOM 一律以 `\uFEFF` 转义书写，不用字面字符：BOM 在源文件里不可见，字面写法在编辑与
 * review 时都可能被吞掉而测试静默失去覆盖。
 */
class TextNormalizerTest {

    @Test
    fun cleanParagraphKeepsSingleSpaceBetweenWords() {
        assertEquals("Sherlock 1 000", TextNormalizer.cleanParagraph("Sherlock   1 000"))
    }

    @Test
    fun cleanParagraphStripsLeadingIndentAndTrailingSpace() {
        assertEquals("正文开头", TextNormalizer.cleanParagraph("　　正文开头   "))
    }

    @Test
    fun cleanParagraphStripsBom() {
        assertEquals("第一段", TextNormalizer.cleanParagraph("\uFEFF第一段"))
    }

    @Test
    fun unifyNewlinesNormalisesCrlfAndCr() {
        assertEquals(listOf("a", "b", "c"), TextNormalizer.unifyNewlines("a\r\nb\rc").split("\n"))
    }

    @Test
    fun cleanParagraphsDropsBlankLines() {
        assertEquals(listOf("甲", "乙"), TextNormalizer.cleanParagraphs(listOf("甲", "   ", "乙")))
    }

    @Test
    fun toDisplayTextIndentsEveryParagraph() {
        assertEquals("　　甲\n　　乙", TextNormalizer.toDisplayText(listOf("甲", "乙")))
    }

    @Test
    fun cleanParagraphAbsorbsLegacyFullWidthIndent() {
        // 历史章文件若已带缩进，读取时先吸收再统一补，避免出现四个全角空格
        assertEquals("老数据", TextNormalizer.cleanParagraph("　　　　老数据"))
    }
}
