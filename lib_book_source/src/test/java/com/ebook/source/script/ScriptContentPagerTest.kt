package com.ebook.source.script

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脚本书源正文翻页链（§7.2/§7.3）的纯逻辑契约：fetch 闭包喂假页表，无需 transport。
 *
 * 锁的是与目录链（§7.1）刻意不同的一套终止判据：**没有**「零新增即到底」（正文没有
 * 去重语义），停止只认空/`null`、回环访问集拦截与 50 页上限；`nextContentUrl` 是作者
 * 显式给出的下一页，不套用 URL 形状启发式（§7.3 明令）。同时锁 content 字段的收敛
 * 例外（各形态值集按 `\n` 连接）、`replaceRegex` 以净化形态跑在拼接后的整章串上
 * （含净化残留的 trim 收边分工：边角换行在本层收掉，段落之间的空行留给读取层）。
 */
class ScriptContentPagerTest {

    private fun pagerOf(contentJson: String, pages: Map<String, String>): ScriptContentPager {
        val set = ScriptRuleSet.load("""{"bookSourceUrl":"https://a.com","ruleContent":$contentJson}""")
        val ctx = EvalContext(baseUrl = "https://a.com/c1.html")
        val evaluator = ScriptRuleEvaluator(ctx)
        val extractor = ScriptFieldExtractor(evaluator, "https://a.com")
        return ScriptContentPager(extractor, set, ctx) { url ->
            ScriptPage(url, pages[ScriptUrlOption.splitTail(url)?.first ?: url] ?: "")
        }
    }

    @Test
    fun `单章多页按 nextContentUrl 拼接并以换行分段`() = runTest {
        val pager = pagerOf(
            """{"content":"id.content@textNodes","nextContentUrl":"class.next@href"}""",
            mapOf(
                "https://a.com/c1.html" to """<div id="content">第一页正文<br/>第二段</div><a class="next" href="c1_2.html">下一页</a>""",
                "https://a.com/c1_2.html" to """<div id="content">第二页正文</div>""",  // 无 next：本章末页
            ),
        )
        val text = pager.collect("https://a.com/c1.html")
        assertEquals("第一页正文\n第二段\n第二页正文", text)
    }

    @Test
    fun `replaceRegex 以净化形态跑在拼接后的整章串上`() = runTest {
        val pager = pagerOf(
            """{"content":"id.content@textNodes","replaceRegex":"##本章未完.*继续阅读##"}""",
            mapOf("https://a.com/c1.html" to """<div id="content">正文甲<br/>本章未完，点击继续阅读</div>"""),
        )
        assertEquals("正文甲", pager.collect("https://a.com/c1.html"))
    }

    @Test
    fun `跨段净化正则只在拼后整章上生效`() = runTest {
        // 判别性用例：正则的两半分居两页——第 1 页只有「广告开始」、第 2 页只有「广告结束」，
        // 逐页净化时谁都凑不齐一次完整命中（两页原样保留），只有拼接后的整章串上才删得掉；
        // 若实现被改成逐页净化，本用例即红。正则额外吃掉「广告结束」后的换行：只删两行广告
        // 文本会留下连续两个换行（净化残留的段落间空行留给读取层 TextNormalizer，本层不碰），
        // 把边界收进正则让断言落在单一确定形态上。JSON 源里写 \\n 使规则串收到字面 \n
        // 转义序列，由 Java 正则解释为换行（跨段命中的桥梁）。
        val pager = pagerOf(
            """{"content":"id.content@textNodes","nextContentUrl":"class.next@href","replaceRegex":"##广告开始\\n广告结束\\n##"}""",
            mapOf(
                "https://a.com/c1.html" to
                    """<div id="content">第一页尾部<br/>广告开始</div><a class="next" href="c1_2.html">下一页</a>""",
                "https://a.com/c1_2.html" to """<div id="content">广告结束<br/>第二页正文</div>""",  // 无 next：本章末页
            ),
        )
        assertEquals("第一页尾部\n第二页正文", pager.collect("https://a.com/c1.html"))
    }

    @Test
    fun `回环的 nextContentUrl 被访问集拦下不无限循环`() = runTest {
        // 规则配错：next 指向本章自己（§7.3 反例——显式规则配错时回环拦截是唯一防线）
        val html = """<div id="content">正文</div><a class="next" href="c1.html">自己</a>"""
        val pager = pagerOf(
            """{"content":"id.content@textNodes","nextContentUrl":"class.next@href"}""",
            mapOf("https://a.com/c1.html" to html),
        )
        assertEquals("正文", pager.collect("https://a.com/c1.html"))
    }

    @Test
    fun `数组形态 nextContentUrl 按固定页序拼接`() = runTest {
        val pager = pagerOf(
            """{"content":"id.content@textNodes","nextContentUrl":["https://a.com/c1.html","https://a.com/c1_2.html"]}""",
            mapOf(
                "https://a.com/c1.html" to """<div id="content">页一</div>""",
                "https://a.com/c1_2.html" to """<div id="content">页二</div>""",
            ),
        )
        assertEquals("页一\n页二", pager.collect("https://a.com/c1.html"))
    }

    @Test
    fun `content 含 js 段时类型化异常原样穿透`() = runTest {
        // 本机未装配沙箱（夹具直接建 EvalContext，`ctx.js` 为 null）：Plan 3 之后仍期望「待执行」
        val pager = pagerOf(
            """{"content":"@js:result"}""",
            mapOf("https://a.com/c1.html" to "x"),
        )
        val error = runCatching { pager.collect("https://a.com/c1.html") }.exceptionOrNull()
        assertTrue(error is JsEvaluationPendingException)
    }

    @Test
    fun `content 规则未配置时空串返回由读取层判失败`() = runTest {
        val pager = pagerOf("""{}""", mapOf("https://a.com/c1.html" to "x"))
        assertEquals("", pager.collect("https://a.com/c1.html"))
    }

    // ===== 防御上限（可选加分项，任务实现提示授权补齐）=====

    @Test
    fun `触顶防御上限按截断处理且不再发请求`() = runTest {
        // 52 页经数组形态 nextContentUrl 驱动（不依赖字符串规则求值）：触顶 50 后停止抓取、
        // 不再发请求——与目录链触顶用例（ScriptTocPagerTest）同构，判型与原生
        // `JsoupSourceReader` 的 `visited.size <= MAX_CONTENT_PAGES` 同一条
        val urls = (1..52).map { "https://a.com/c1_$it.html" }
        val pages = urls.mapIndexed { i, url -> url to """<div id="content">页${i + 1}</div>""" }.toMap()
        var fetchCalls = 0
        val set = ScriptRuleSet.load(
            """{"bookSourceUrl":"https://a.com","ruleContent":{"content":"id.content@textNodes",""" +
                """"nextContentUrl":${urls.joinToString(",", "[", "]") { "\"$it\"" }}}}""",
        )
        val ctx = EvalContext(baseUrl = "https://a.com/c1_1.html")
        val evaluator = ScriptRuleEvaluator(ctx)
        val extractor = ScriptFieldExtractor(evaluator, "https://a.com")
        val pager = ScriptContentPager(extractor, set, ctx) { url ->
            fetchCalls++
            ScriptPage(url, pages[ScriptUrlOption.splitTail(url)?.first ?: url] ?: "")
        }

        val text = pager.collect("https://a.com/c1_1.html")

        assertEquals(ScriptContentPager.MAX_CONTENT_PAGES, fetchCalls)  // 52 页可用只发 50 次请求
        assertTrue(text.endsWith("页50"))  // 截断在恰好第 50 页
        assertTrue(!text.contains("页51"))  // 触顶后的页不再进入拼接
    }
}
