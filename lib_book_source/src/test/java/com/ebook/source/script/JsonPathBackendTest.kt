package com.ebook.source.script

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 规格 §2.1 JSONPath 模式 + §3.3 错误语义。子集边界见 JsonPathBackend 的 KDoc 与规格 §11-17。 */
class JsonPathBackendTest {

    private val doc = Json.parseToJsonElement(
        """{"data":{"books":[
            {"name":"书一","author":"作者一","tags":["玄幻","完结"],"price":0},
            {"name":"书二","author":"作者二","tags":["都市"],"price":12.5},
            {"name":"书三","author":"作者三","tags":[],"price":-1}
        ]},"total":3,"list":[1,2,3,4,5]}""",
    )

    private fun eval(path: String, reverse: Boolean = false): List<String> =
        (JsonPathBackend.evaluate(path, RuleValue.Json(doc), reverse) as RuleResult.Jsons)
            .items.map { it.jsonText() }

    private fun evalJson(path: String): RuleResult =
        JsonPathBackend.evaluate(path, RuleValue.Json(doc), reverse = false)

    @Test
    fun `点分路径取叶子`() {
        assertEquals(listOf("3"), eval("$.total"))
        assertEquals(listOf("书二"), eval("$.data.books[1].name"))
    }

    @Test
    fun `方括号引用与带引号键`() {
        assertEquals(listOf("3"), eval("$['total']"))
        assertEquals(listOf("书一", "书二", "书三"), eval("""$["data"]['books'][*]['name']"""))
    }

    @Test
    fun `通配符打平数组与对象`() {
        assertEquals(listOf("1", "2", "3", "4", "5"), eval("$.list[*]"))
        assertEquals(listOf("书一", "书二", "书三"), eval("$.data.books[*].name"))
    }

    @Test
    fun `点号通配打平子节点`() {
        assertEquals(listOf("1", "2", "3", "4", "5"), eval("$.list.*"))
    }

    @Test
    fun `负数下标从尾数`() {
        assertEquals(listOf("书三"), eval("$.data.books[-1].name"))
    }

    @Test
    fun `切片按标准半开方言`() {
        // JSONPath 切片 end 排他——与 §4 链式索引的闭区间分属两个方言，这条用例钉住差异
        assertEquals(listOf("1", "2"), eval("$.list[0:2]"))
        assertEquals(listOf("4", "5"), eval("$.list[-2:]"))
    }

    @Test
    fun `带步长的切片`() {
        assertEquals(listOf("1", "3"), eval("$.list[0:4:2]"))
    }

    @Test
    fun `递归下探收集全部同名值`() {
        assertEquals(listOf("玄幻", "完结", "都市"), eval("$..tags[*]"))
    }

    @Test
    fun `联合取多个键`() {
        val r = evalJson("""$['data']['books'][0]['name','author']""")
        assertEquals(listOf("书一", "作者一"), (r as RuleResult.Jsons).items.map { it.jsonText() })
    }

    @Test
    fun `数字与对象结果字符串化`() {
        assertEquals(listOf("12.5"), eval("$.data.books[1].price"))
        // 数组结果保留节点形态，字符串化给紧凑 JSON（jsonText 对 JsonArray 的形态锁形）
        assertEquals(listOf("""["玄幻","完结"]"""), eval("$.data.books[0].tags"))
    }

    @Test
    fun `路径解不到值是 Miss 不是错误`() {
        assertEquals(RuleResult.Miss, evalJson("$.data.notexist"))
        assertEquals(RuleResult.Miss, evalJson("$.data.books[99].name"))
        assertEquals(RuleResult.Miss, evalJson("$.total.name"))
    }

    @Test
    fun `反序标志倒转条目`() {
        assertEquals(listOf("书三", "书二", "书一"), evalReverse())
    }

    private fun evalReverse(): List<String> =
        (JsonPathBackend.evaluate("$.data.books[*].name", RuleValue.Json(doc), reverse = true) as RuleResult.Jsons)
            .items.map { it.jsonText() }

    @Test
    fun `过滤器与脚本表达式按不支持拒绝`() {
        assertTrue(runCatching { eval("$.data.books[?(@.price=0)]") }.exceptionOrNull() is UnsupportedRuleFeatureException)
        assertTrue(runCatching { eval("$.data.books[(1+2)]") }.exceptionOrNull() is UnsupportedRuleFeatureException)
    }

    @Test
    fun `递归通配与残缺引号键类型化拒绝`() {
        assertTrue(runCatching { eval("$..*") }.exceptionOrNull() is UnsupportedRuleFeatureException)
        assertTrue(runCatching { eval("$['a':2]") }.exceptionOrNull() is RuleSyntaxException)
        assertTrue(runCatching { eval("$['a'b]") }.exceptionOrNull() is RuleSyntaxException)
    }

    @Test
    fun `节点上下文喂 JSONPath 按不支持拒绝`() {
        // Nodes 种子（HTML 上下文喂 JSONPath）是域不匹配：类型化拒绝而不是静默 Miss，
        // 与 ElementBackends/RegexBackend 拒 Json 种子同口径——钉形用例，防止 seed 分支被改回 Miss 无报警
        assertTrue(
            runCatching { JsonPathBackend.evaluate("$.total", RuleValue.Nodes(emptyList()), reverse = false) }
                .exceptionOrNull() is UnsupportedRuleFeatureException,
        )
    }

    @Test
    fun `语法错误抛类型化异常`() {
        assertTrue(runCatching { eval("data.books") }.exceptionOrNull() is RuleSyntaxException)
        assertTrue(runCatching { eval("$.data[") }.exceptionOrNull() is RuleSyntaxException)
        assertTrue(runCatching { eval("$.list[a]") }.exceptionOrNull() is RuleSyntaxException)
    }

    @Test
    fun `文本输入不是合法 JSON 时按 Miss 处理`() {
        // 响应不是规则串：坏响应是「没取到值」，交给 || 兜底，而不是把整条规则判死
        assertEquals(RuleResult.Miss, JsonPathBackend.evaluate("$.total", RuleValue.Texts(listOf("<html>404</html>")), reverse = false))
    }

    @Test
    fun `页面输入按 JSON 文本解析`() {
        // Page 种子是真实取文路径的入口形态：响应文本 → lenient 解析
        assertEquals(
            listOf("3"),
            (JsonPathBackend.evaluate("$.total", RuleValue.Page("""{"total":3}"""), reverse = false) as RuleResult.Jsons)
                .items.map { it.jsonText() },
        )
    }
}
