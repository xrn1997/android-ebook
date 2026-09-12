package com.ebook.source.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 规格 §8.1 两种格式、§8.3 花括号不透明、§11-13 行优先分隔。 */
class ExploreUrlFormatTest {

    @Test
    fun `文本格式按行与双与号切条目`() {
        val entries = ExploreUrlFormat.split("男生::/a/{{page}}&&女生::/b/{{page}}\n出版::/c")
        assertEquals(listOf("男生", "女生", "出版"), entries.map { it.title })
        assertEquals(listOf("/a/{{page}}", "/b/{{page}}", "/c"), entries.map { it.urlRule })
    }

    @Test
    fun `插值内容里的双与号不切条目`() {
        // §8.3：{{}}/{} 内的 && 是数据不是分隔符——腰斩的症状是「一条发现入口变两条坏的」
        val entries = ExploreUrlFormat.split("""分类::/x?k={{java.getString("a&&b")}}&&其他::/y""")
        assertEquals(2, entries.size)
        assertEquals("""/x?k={{java.getString("a&&b")}}""", entries[0].urlRule)
    }

    @Test
    fun `缺名称的条目标题为空`() {
        val entries = ExploreUrlFormat.split("/only-url")
        assertEquals(listOf(""), entries.map { it.title })
        assertEquals(listOf("/only-url"), entries.map { it.urlRule })
    }

    @Test
    fun `JSON 格式二只取 title 与 url`() {
        val raw = """[{"title":"玄幻","url":"/x/{{page}}","style":{"layout_flexGrow":1}},{"title":"控件","type":"toggle"},{"url":"/y"}]"""
        val entries = ExploreUrlFormat.split(raw)
        assertEquals(2, entries.size)
        assertEquals("玄幻", entries[0].title)
        assertEquals("/x/{{page}}", entries[0].urlRule)
        assertEquals("", entries[1].title)
        assertEquals("/y", entries[1].urlRule)
    }

    @Test
    fun `未闭合花括号后不再切条目`() {
        // 从宽取舍锁形：未闭合的 { 让余段保持不透明——少切、不炸、不抛
        val entries = ExploreUrlFormat.split("""分类::/x?k={{a&&其他::/y""")
        assertEquals(1, entries.size)
        assertEquals("分类", entries[0].title)
        assertEquals("""/x?k={{a&&其他::/y""", entries[0].urlRule)
    }

    @Test
    fun `顶层双冒号取首个后续归 URL`() {
        val entries = ExploreUrlFormat.split("A::B::/url")
        assertEquals(listOf("A"), entries.map { it.title })
        assertEquals(listOf("B::/url"), entries.map { it.urlRule })
    }

    @Test
    fun `方括号开头的文本格式误判为 JSON 返回空清单`() {
        // 已知陷阱锁形：首条目标题以 [ 开头会误走 JSON 解析——日后修复应为
        // 「解析成 JsonArray 才采用、否则回落文本切分」，届时此测试同步反转
        assertTrue(ExploreUrlFormat.split("[书名]::/url").isEmpty())
    }

    @Test
    fun `空串与坏 JSON 都返回空清单`() {
        assertTrue(ExploreUrlFormat.split("").isEmpty())
        assertTrue(ExploreUrlFormat.split("   ").isEmpty())
        assertTrue(ExploreUrlFormat.split("[{broken").isEmpty())
    }

    @Test
    fun `脚本程序形态整串返回空清单而不是按行切成条目`() {
        // 真实事故锁形（2026-09-10 番茄（发现））：按行切会把 JS 源码逐行当 URL 拼到源根地址上发出去。
        // 这条源当时被切成 105 条空标题条目、发 105 次 404，再撞死书城的 LazyColumn
        val raw = "<js>\nvar ui = [];\nui.push({title:\"榜\",url:\"/a\"});\nJSON.stringify(ui);\n</js>"
        assertTrue(ExploreUrlFormat.split(raw).isEmpty())
        assertTrue(ExploreUrlFormat.isScriptProgram(raw))
        // 前导空白不影响判定（装载器传进来的串不保证无缩进）
        assertTrue(ExploreUrlFormat.isScriptProgram("\n  <js>1</js>"))
    }

    @Test
    fun `文本格式里 url 含 js 段仍照常切条目`() {
        // 判据只看**整串开头**，不扫内容：`名称::<js>…</js>` 是合法的文本格式一，
        // 那段 JS 是待求值的目标表达式，不是发现页程序。放宽成 contains 会误伤这一类
        val entries = ExploreUrlFormat.split("分类::<js>1+1</js>")
        assertEquals(listOf("分类"), entries.map { it.title })
        assertEquals(listOf("<js>1+1</js>"), entries.map { it.urlRule })
        assertTrue(!ExploreUrlFormat.isScriptProgram("分类::<js>1+1</js>"))
    }
}
