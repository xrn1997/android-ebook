package com.ebook.source.script

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 规格 §1 顶层字段与六类规则对象。未知键忽略（§1.1 末段），不支持项必须如实登记。 */
class ScriptRuleSetTest {

    private val communityJson = """
    {
      "bookSourceUrl": "https://abc.example.com",
      "bookSourceName": "测试脚本源",
      "bookSourceGroup": "书源群",
      "bookSourceType": 0,
      "enabled": true,
      "searchUrl": "/search/{key},POST,{\"body\":\"key={{key}}&page={{page}}\",\"charset\":\"gbk\"}",
      "exploreUrl": "男生::/nan/{{page}}\n女生::/nv",
      "ruleSearch": {
        "bookList": "class.bookbox",
        "name": "tag.a.0@text",
        "author": "class.author@text",
        "bookUrl": "tag.a.0@href",
        "checkKeyWord": "凡人"
      },
      "ruleToc": { "chapterList": "-:<li><a[^\"]+\"([^\"]*)\">([^<]*)", "chapterName": "$2", "chapterUrl": "$1", "nextTocUrl": [] },
      "ruleContent": { "content": "@css:#content@html", "nextContentUrl": "text.下一页@href" },
      "ruleBookInfo": { "init": ":<div>(.*)</div>", "tocUrl": "" },
      "unknownFutureKey": { "whatever": 1 },
      "customOrder": 7
    }
    """.trimIndent()

    @Test
    fun `装载出顶层 URL 与六类规则对象的字段表`() {
        val set = ScriptRuleSet.load(communityJson)
        assertEquals("https://abc.example.com", set.sourceUrl)
        assertEquals("测试脚本源", set.name)
        assertTrue(set.searchUrl!!.startsWith("/search/"))
        assertEquals("class.bookbox", set.rule(RuleObjectKind.SEARCH, "bookList"))
        assertEquals("@css:#content@html", set.rule(RuleObjectKind.CONTENT, "content"))
    }

    @Test
    fun `非字符串的规则字段被忽略并登记为不支持`() {
        // 语料里 nextTocUrl 常写成 []（空数组），它不是规则串，装载不得当成空规则混过校验
        val set = ScriptRuleSet.load(communityJson)
        assertTrue(
            "nextTocUrl 是数组形态，应被记为非常规字段",
            set.irregularFields.contains("ruleToc.nextTocUrl"),
        )
        // 「没配」与「配了多个 URL」必须可区分：非字符串字段不进规则表，rule() 返回 null
        assertNull(set.rule(RuleObjectKind.TOC, "nextTocUrl"))
    }

    @Test
    fun `未知顶层键被忽略而不报错`() {
        val set = ScriptRuleSet.load(communityJson)
        assertEquals("https://abc.example.com", set.sourceUrl)
    }

    @Test
    fun `声明了登录与 webJs 与 XPath 的源登记对应不支持项`() {
        val withUnsupported = """
        {
          "bookSourceUrl": "https://x.example", "bookSourceName": "x",
          "loginUrl": "https://x.example/login",
          "ruleContent": { "webJs": "1+1", "content": "//div/@text()" }
        }
        """.trimIndent()
        val set = ScriptRuleSet.load(withUnsupported)
        assertTrue(set.unsupported.contains(ScriptUnsupported.LOGIN))
        assertTrue(set.unsupported.contains(ScriptUnsupported.WEB_JS))
    }

    @Test
    fun `规则串里的 js 与 XPath 分别登记`() {
        val set = ScriptRuleSet.load(
            """
            {"bookSourceUrl":"https://y","bookSourceName":"y",
             "ruleSearch":{"bookList":"tag.li<js>1</js>","name":"@js:result","author":"//a/@text()"}}
            """.trimIndent()
        )
        assertTrue(set.unsupported.contains(ScriptUnsupported.JS))
        assertTrue(set.unsupported.contains(ScriptUnsupported.XPATH))
    }

    @Test
    fun `延后能力按键名登记而非按规则串内容猜`() {
        // §1.4/§1.6：downloadUrls、contentBatch、coverDecodeJs 都是**键名**层面的能力，
        // 规则串里出现同名字样不算数；反过来键值为空串就是未配置，不得凑成不支持项。
        val set = ScriptRuleSet.load(
            """
            {"bookSourceUrl":"https://w","bookSourceName":"w",
             "jsLib":"","coverDecodeJs":"https://x/y.js",
             "ruleBookInfo":{"downloadUrls":["a"],"name":"webJs 写在字段值里也只是文本"},
             "ruleContent":{"contentBatch":"","content":"tag.p@text"}}
            """.trimIndent()
        )
        assertTrue("downloadUrls 键存在即延后", set.unsupported.contains(ScriptUnsupported.DOWNLOAD_URLS))
        assertTrue(set.unsupported.contains(ScriptUnsupported.COVER_DECODE))
        assertTrue("jsLib 为空串即未配置，不该登记", !set.unsupported.contains(ScriptUnsupported.JS_LIB))
        assertTrue("规则串里的 webJs 字样不构成能力登记", !set.unsupported.contains(ScriptUnsupported.WEB_JS))
        assertTrue(set.irregularFields.contains("ruleBookInfo.downloadUrls"))
        assertTrue(set.irregularFields.contains("ruleContent.contentBatch"))
    }

    @Test
    fun `装载出沙箱 source 面要的源级字段`() {
        // `source.getVariable()` 读的就是 `variable` 整串（语料 262 次调用全为零参，取的是整串而不是
        // 单个键），`source.header` 读的是 582 条源声明的 `header` JSON 串：这两个键过去压根没进装载器。
        val set = ScriptRuleSet.load(
            """
            {"bookSourceUrl":"https://s","bookSourceName":"s","bookSourceGroup":"群",
             "bookSourceComment":"注释正文","variable":"{\"api\":\"https://api.example\"}",
             "variableComment":"api=接口地址","header":"{\"User-Agent\":\"UA\"}",
             "loginUrl":"https://s/login","lastUpdateTime":1700000000000}
            """.trimIndent(),
        )
        assertEquals("""{"api":"https://api.example"}""", set.variable)
        assertEquals("api=接口地址", set.variableComment)
        assertEquals("""{"User-Agent":"UA"}""", set.header)
        assertEquals("注释正文", set.bookSourceComment)
        assertEquals("https://s/login", set.loginUrl)
        // lastUpdateTime 在语料里有数字与日期字符串两种形态：装载取原文文本，翻译成本仓的 Long 会把
        // 「配成日期」的源静默变成 0（脚本里的时间差判断跟着一起错），原文给脚本至少还能参与比较
        assertEquals("1700000000000", set.lastUpdateTime)
    }

    @Test
    fun `源级字段缺失时装成空串而不是 null`() {
        val set = ScriptRuleSet.load(communityJson)
        assertEquals("", set.variable)
        assertEquals("", set.variableComment)
        assertEquals("", set.header)
        assertEquals("", set.bookSourceComment)
        assertEquals("", set.loginUrl)
        assertEquals("", set.lastUpdateTime)
    }

    @Test
    fun `source 绑定只带声明过的键与书名和源地址`() {
        val binding = ScriptRuleSet.load(communityJson).sourceBindingJson()
        val obj = ScriptJson.parseToJsonElement(binding) as JsonObject
        assertEquals("https://abc.example.com", (obj["bookSourceUrl"] as JsonPrimitive).content)
        assertEquals("测试脚本源", (obj["bookSourceName"] as JsonPrimitive).content)
        assertEquals("书源群", (obj["bookSourceGroup"] as JsonPrimitive).content)
        // communityJson 没配 variable/header/comment：空串不占键位，脚本读到的是 undefined
        // （`if (!source.variableComment)` 这类判空在语料里真实存在，空串会让它恒假）
        assertTrue(obj.keys.containsAll(setOf("bookSourceUrl", "bookSourceName", "bookSourceGroup")))
        assertEquals(
            "未声明的键不得以空串冒充配置：${obj.keys}",
            setOf("bookSourceUrl", "bookSourceName", "bookSourceGroup"),
            obj.keys,
        )
    }

    @Test
    fun `source 绑定带出声明过的变量整串与头域`() {
        val obj = ScriptRuleSet.load(
            """
            {"bookSourceUrl":"https://s","bookSourceName":"s","variable":"{\"a\":1}","header":"{}"}
            """.trimIndent(),
        ).sourceBindingJson().let { ScriptJson.parseToJsonElement(it) as JsonObject }
        assertEquals("""{"a":1}""", (obj["variable"] as JsonPrimitive).content)
        assertEquals("{}", (obj["header"] as JsonPrimitive).content)
    }

    @Test
    fun `exploreUrl 的脚本程序形态登记为发现面缺口而不是会执行的 js`() {
        // 与「含可执行代码」分开记的理由：这一形态本仓**不执行**（缺的是发现页 UI 宿主与
        // infoMap/refreshExplore 一族宿主回调），并进 JS 会让报告对一条
        // 「整源可用、只少发现面」的源喊出错误话术
        val set = ScriptRuleSet.load(
            """
            {"bookSourceUrl":"https://f","bookSourceName":"f",
             "exploreUrl":"<js>\nvar ui=[];\nJSON.stringify(ui);\n</js>"}
            """.trimIndent()
        )
        assertTrue(set.unsupported.contains(ScriptUnsupported.EXPLORE_URL_SCRIPT))
        assertTrue("这段 JS 本仓不执行，不得并入 JS 项", !set.unsupported.contains(ScriptUnsupported.JS))
    }

    @Test
    fun `exploreUrl 文本形态里含 js 段照旧登记 JS`() {
        // 判据只看整串开头：`名称::<js>…</js>` 里那段 JS 是待求值的目标表达式，
        // 与脚本程序形态是两件事，不能一起被划走
        val set = ScriptRuleSet.load(
            """
            {"bookSourceUrl":"https://g","bookSourceName":"g",
             "exploreUrl":"分类::<js>1+1</js>"}
            """.trimIndent()
        )
        assertTrue(set.unsupported.contains(ScriptUnsupported.JS))
        assertTrue(!set.unsupported.contains(ScriptUnsupported.EXPLORE_URL_SCRIPT))
    }

    @Test
    fun `没有任何规则对象时装载成功`() {
        val set = ScriptRuleSet.load("""{"bookSourceUrl":"https://z","bookSourceName":"z"}""")
        assertEquals("https://z", set.sourceUrl)
        assertTrue(set.rule(RuleObjectKind.SEARCH, "name") == null)
    }

    @Test
    fun `非法 JSON 抛类型化异常而非序列化细节`() {
        val e = runCatching { ScriptRuleSet.load("{not json") }.exceptionOrNull()
        assertTrue("实际：${e?.let { it::class.simpleName }}", e is ScriptRuleParseException)
        assertTrue(e!!.message!!.contains("脚本书源"))
    }

    @Test
    fun `数组形态整包拒收并说明整包在导入侧已拆开`() {
        // 规格 §1.1：一个书源文件是书源对象的数组；导入链路逐条处理后把单个对象交给本装载器
        val e = runCatching { ScriptRuleSet.load("[{\"bookSourceUrl\":\"https://a\"}]") }.exceptionOrNull()
        assertTrue("数组应被拒：${e?.message}", e is ScriptRuleParseException)
    }

    @Test
    fun `原始 JSON 整块保留供求值层按需重读`() {
        val set = ScriptRuleSet.load(communityJson)
        assertTrue(set.raw === communityJson || set.raw == communityJson)
    }

    @Test
    fun `jsonField 展开数组形态字段供翻页链消费`() {
        val raw = """{"bookSourceUrl":"https://a.com","ruleToc":{"nextTocUrl":["https://a.com/toc1","https://a.com/toc2"]}}"""
        val set = ScriptRuleSet.load(raw)
        val node = set.jsonField(RuleObjectKind.TOC, "nextTocUrl")
        assertTrue(node is kotlinx.serialization.json.JsonArray)
        // 字符串规则形态下 rule 仍是 null（数组不进规则表，见类 KDoc），jsonField 拿到的是原始节点
        assertTrue(set.rule(RuleObjectKind.TOC, "nextTocUrl") == null)
    }
}
