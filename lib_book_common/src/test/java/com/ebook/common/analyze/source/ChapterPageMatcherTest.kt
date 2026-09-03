package com.ebook.common.analyze.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChapterPageMatcher] 的单元测试（纯 JVM，无 Android 依赖）。
 *
 * 覆盖分页判定的四类关键形态：
 * - 常规「基准 + 分页后缀」（内置书源笔趣阁的实际形态），含扩展名不一致的混合形态；
 * - 「章节号写在连字符后」的站点——对基准也剥离会让相邻章同形、误判为同章而串章；
 * - 查询参数分页（不在后缀规则内）；
 * - 结构上无法区分的形态（第 1 页也带后缀），固化当前「宁可漏页不可串章」的取舍，
 *   防止后续改动在无人察觉时把行为翻过来。
 */
class ChapterPageMatcherTest {

    // ===== stripPageSuffix =====

    @Test
    fun `剥离末尾的连字符分页后缀`() {
        assertEquals(
            "https://x.com/5/3943720",
            ChapterPageMatcher.stripPageSuffix("https://x.com/5/3943720-2")
        )
    }

    @Test
    fun `剥离分页后缀时保留扩展名`() {
        // 归一为 X.html 而不是 X：否则「入口带扩展名 + 分页带扩展名」的站点永远对不上
        assertEquals(
            "https://x.com/5/3943720.html",
            ChapterPageMatcher.stripPageSuffix("https://x.com/5/3943720_2.html")
        )
    }

    @Test
    fun `无分页后缀的 URL 原样返回`() {
        assertEquals(
            "https://x.com/5/3943720",
            ChapterPageMatcher.stripPageSuffix("https://x.com/5/3943720")
        )
    }

    @Test
    fun `数字前不是连字符或下划线时不剥离`() {
        // 章节号由斜杠分隔（笔趣阁形态）：`/3943720` 不匹配 `[-_]\d+$`，
        // 否则整章 URL 会被裁成 `/5`，所有章节同形
        assertEquals(
            "https://x.com/5/3943720.html",
            ChapterPageMatcher.stripPageSuffix("https://x.com/5/3943720.html")
        )
    }

    // ===== isSameChapterPage：应判为同章 =====

    @Test
    fun `本章分页链接判定为同章`() {
        assertTrue(
            ChapterPageMatcher.isSameChapterPage(
                url = "https://x.com/5/3943720-2",
                chapterBaseUrl = "https://x.com/5/3943720"
            )
        )
    }

    @Test
    fun `入口无扩展名而分页带扩展名时仍判定为同章`() {
        assertTrue(
            ChapterPageMatcher.isSameChapterPage(
                url = "https://x.com/5/3943720-2.html",
                chapterBaseUrl = "https://x.com/5/3943720"
            )
        )
    }

    @Test
    fun `入口与分页都带扩展名时判定为同章`() {
        assertTrue(
            ChapterPageMatcher.isSameChapterPage(
                url = "https://x.com/5/3943720_2.html",
                chapterBaseUrl = "https://x.com/5/3943720.html"
            )
        )
    }

    @Test
    fun `章节号写在连字符后时本章分页仍判定为同章`() {
        assertTrue(
            ChapterPageMatcher.isSameChapterPage(
                url = "https://x.com/book/1234-15-2.html",
                chapterBaseUrl = "https://x.com/book/1234-15.html"
            )
        )
    }

    // ===== isSameChapterPage：必须拦下 =====

    @Test
    fun `下一章首页不被判定为同章`() {
        // 末页的「下一页」常指向下一章首页，跟进会把后续章节拼进本章
        assertFalse(
            ChapterPageMatcher.isSameChapterPage(
                url = "https://x.com/5/3943721",
                chapterBaseUrl = "https://x.com/5/3943720"
            )
        )
    }

    @Test
    fun `章节号写在连字符后时相邻章不被判定为同章`() {
        // 基准是目录页给出的原始 URL（未剥离）：相邻章剥后为 /1234.html，与基准不等 → 拦下。
        // 若像旧实现那样对基准也剥离，两者同为 /1234 会一路跟进后续章节直到页数上限（串章）
        assertFalse(
            ChapterPageMatcher.isSameChapterPage(
                url = "https://x.com/book/1234-16.html",
                chapterBaseUrl = "https://x.com/book/1234-15.html"
            )
        )
    }

    @Test
    fun `扩展名兜底不会把相邻章放进来`() {
        // 兜底只去扩展名、不再剥数字后缀：否则 /1234-16.html 会被削成 /1234 而与基准同形
        assertFalse(
            ChapterPageMatcher.isSameChapterPage(
                url = "https://x.com/book/1234-16",
                chapterBaseUrl = "https://x.com/book/1234-15.html"
            )
        )
    }

    @Test
    fun `查询参数分页不被判定为同章`() {
        // 已知取舍：?page= 形态不在后缀规则内，视为本章最后一页（宁漏页不串章），
        // 真要支持需在书源规则里声明分页模板（ADR-0016）
        assertFalse(
            ChapterPageMatcher.isSameChapterPage(
                url = "https://x.com/5/3943720?page=2",
                chapterBaseUrl = "https://x.com/5/3943720"
            )
        )
    }

    @Test
    fun `第 1 页也带后缀的站点会漏后续页（固化当前取舍）`() {
        // 基准 /ch/100-1、分页 /ch/100-2 与「章节号写在连字符后」结构同形，无法靠 URL 区分。
        // 本断言固化当前行为：判为不同章（漏页）而非同章（串章）。
        // 若将来引入书源分页模板让此形态可跟进，本测试需随实现一起更新
        assertFalse(
            ChapterPageMatcher.isSameChapterPage(
                url = "https://x.com/ch/100-2",
                chapterBaseUrl = "https://x.com/ch/100-1"
            )
        )
    }
}
