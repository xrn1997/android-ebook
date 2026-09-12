package com.ebook.me.domain

import com.ebook.api.entity.BookSourceRule
import com.ebook.api.entity.ContentRule
import com.ebook.api.entity.FindRule
import com.ebook.api.entity.ScriptSourceRule
import com.ebook.api.entity.SearchRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BookSourceValidator] 四条结构规则的分支测试。
 *
 * 每条规则都要钉两件事：**缺它时确实报出来**（否则坏书源会被放进来，用户导入后打不开又不知道为什么）、
 * **只有它缺时不牵连别条**（否则预览层会把一条好源上的其它字段也报成错，用户改无可改）。
 * 另锁「同时违反多条时一次报全」——这是预览层不逼用户改一次导一次的前提。
 *
 * [BookSourceValidator.validateScript]（脚本书源）也在本文件里锁，两件事各占一半：
 * - **有效性**只沿用原生四条里的名称/地址两条，且报的是**同一批** [ValidationReason] 值——
 *   预览层于是能拿一条 `when` 把两种出身的失败都翻成文案，不会长出第二套词汇；
 * - **警示**三项（可执行代码 / 依赖登录 / 非文本源）只要求「该说的说了、不该报的没报」，
 *   它们**从不影响** [com.ebook.me.domain.ScriptValidation.isValid]（警示不拦导入）。
 *
 * 纯 JVM：入参是普通数据类，校验器不碰 Android 框架，故不需要 Robolectric。
 */
class BookSourceValidatorTest {

    /**
     * 四条全过的基准规则。
     *
     * 各测试只改要破坏的那一条，其余字段保持有效——这样「报出的原因就是这一个」才可断言。
     */
    private fun validRule(
        name: String = "测试站",
        url: String = "https://www.test.com",
        searchUrl: String = "https://www.test.com/so/{{keyword}}",
        findUrl: String = "https://www.test.com/{{kind}}/{{page}}",
        searchList: String = "div.book",
        content: String = "#content",
    ) = BookSourceRule(
        name = name,
        url = url,
        searchUrl = searchUrl,
        ruleFind = FindRule(url = findUrl),
        ruleSearch = SearchRule(list = searchList),
        ruleContent = ContentRule(content = content),
    )

    private fun reasonsOf(result: ValidationResult): List<ValidationReason> =
        (result as ValidationResult.Invalid).reasons

    @Test
    fun `四条规则齐备时判定为通过`(): Unit {
        assertEquals(ValidationResult.Valid, BookSourceValidator.validate(validRule()))
    }

    @Test
    fun `名称为空白时报名称为空`(): Unit {
        listOf("", "   ", "\t").forEach { blank ->
            val result = BookSourceValidator.validate(validRule(name = blank))
            assertEquals("名称「$blank」应只报出名称为空这一条", listOf(ValidationReason.NAME_BLANK), reasonsOf(result))
        }
    }

    @Test
    fun `地址不是 http 开头时报地址不合法`(): Unit {
        listOf("", "www.test.com", "ftp://www.test.com", "htp://www.test.com").forEach { bad ->
            val result = BookSourceValidator.validate(validRule(url = bad))
            assertEquals(
                "地址「$bad」不是 http(s) 开头，应只报出地址这一条",
                listOf(ValidationReason.URL_NOT_HTTP),
                reasonsOf(result),
            )
        }
    }

    @Test
    fun `http 明文与大小写混排的地址都算合法`(): Unit {
        // 第三方站点仍有只开 80 端口的，判 https-only 会把好源拦在门外；
        // 社区 JSON 里也有 HtTpS 这种手改痕迹，协议前缀按不区分大小写处理
        assertTrue(BookSourceValidator.validate(validRule(url = "http://www.test.com")) is ValidationResult.Valid)
        assertTrue(BookSourceValidator.validate(validRule(url = "HTTPS://www.test.com")) is ValidationResult.Valid)
    }

    @Test
    fun `搜索地址与发现页地址都为空时才报缺入口`(): Unit {
        val bothBlank = BookSourceValidator.validate(validRule(searchUrl = "", findUrl = "  "))
        assertEquals(listOf(ValidationReason.NO_ENTRY), reasonsOf(bothBlank))

        // 只留搜索地址、或只留发现页地址，都算「有路能找到书」，不该报缺入口
        assertTrue(
            "只有搜索地址时应通过",
            BookSourceValidator.validate(validRule(searchUrl = "https://x/so/{{keyword}}", findUrl = ""))
                is ValidationResult.Valid,
        )
        assertTrue(
            "只有发现页地址时应通过",
            BookSourceValidator.validate(
                validRule(searchUrl = "", findUrl = "https://x/{{kind}}/{{page}}")
            ) is ValidationResult.Valid,
        )
    }

    @Test
    fun `搜索结果列表或正文选择器为空都报缺解析规则`(): Unit {
        assertEquals(
            "缺结果列表选择器",
            listOf(ValidationReason.NO_PARSE_RULE),
            reasonsOf(BookSourceValidator.validate(validRule(searchList = ""))),
        )
        assertEquals(
            "缺正文容器选择器",
            listOf(ValidationReason.NO_PARSE_RULE),
            reasonsOf(BookSourceValidator.validate(validRule(content = " "))),
        )
    }

    @Test
    fun `同时违反多条时一次报出全部原因`(): Unit {
        // 空壳规则：名称、地址、入口、解析规则四条全缺
        val result = BookSourceValidator.validate(BookSourceRule())

        assertEquals(
            "原因要按判定顺序列全，预览层才能一次看完改一次；顺序与 validate 的实现一致",
            listOf(
                ValidationReason.NAME_BLANK,
                ValidationReason.URL_NOT_HTTP,
                ValidationReason.NO_ENTRY,
                ValidationReason.NO_PARSE_RULE,
            ),
            reasonsOf(result),
        )
    }

    // region 脚本书源（validateScript：两条有效性 + 三项警示）

    /**
     * 便捷入口：一条模型 + 它对应的原文（[rawJson] 默认只带名称与地址两个顶层键）。
     *
     * 与原生侧 [validRule] 同构：各测试只改要破坏的那一条，其余字段保持有效，
     * 这样「报出的原因就是这一个」才可断言。
     *
     * 可执行代码警示的扫描只看这份原文、不看模型字段——规则块根本不在最小模型里
     * （见 [ScriptSourceRule] 的类注释），所以原文必须由用例自己给，不能让校验器去猜
     * （[BookSourceValidator.validateScript] 的 rawJson 因此没有默认值）。
     */
    private fun scriptCase(
        name: String = "脚本测试站",
        url: String = "https://www.script.com",
        type: Int = 0,
        loginUrl: String = "",
        rawJson: String = """{"bookSourceName":"$name","bookSourceUrl":"$url"}""",
    ) = BookSourceValidator.validateScript(
        ScriptSourceRule(
            bookSourceName = name,
            bookSourceUrl = url,
            bookSourceType = type,
            loginUrl = loginUrl,
        ),
        rawJson,
    )

    @Test
    fun `干净的脚本书源判通过且无警示`(): Unit {
        val result = scriptCase()
        assertTrue("名称与地址都在，就该让导进来", result.isValid)
        assertTrue("文本源、无登录、原文里没有代码标记 → 一句警示都不该有", result.warnings.isEmpty())
    }

    @Test
    fun `脚本书源缺名称或URL判无效`(): Unit {
        listOf("", "   ", "\t").forEach { blank ->
            val result = scriptCase(name = blank, url = "https://a.com")
            assertTrue(result.reasons.contains(ValidationReason.NAME_BLANK))
            assertEquals(
                "名称「$blank」只该报名称这一条，地址是好的就不该牵连它",
                listOf(ValidationReason.NAME_BLANK),
                result.reasons,
            )
        }
    }

    @Test
    fun `脚本书源URL非http判无效`(): Unit {
        listOf("", "www.script.com", "ftp://www.script.com", "htp://www.script.com").forEach { bad ->
            val result = scriptCase(url = bad)
            assertEquals(
                "地址「$bad」不是 http(s) 开头，应只报出地址这一条",
                listOf(ValidationReason.URL_NOT_HTTP),
                result.reasons,
            )
        }
        // 与原生侧同口径：http 明文与大小写混排都算合法（社区 JSON 里手改出 HtTpS 是常态）
        assertTrue(scriptCase(url = "http://www.script.com").isValid)
        assertTrue(scriptCase(url = "HTTPS://www.script.com").isValid)
    }

    @Test
    fun `脚本书源名称与地址同时缺时一次报全`(): Unit {
        // 原文这里给一份空对象：本用例只锁「两条原因一次报全」，扫描确实跑过、里面没有代码标记
        val result = BookSourceValidator.validateScript(ScriptSourceRule(), rawJson = "{}")
        assertEquals(
            "顺序与原生侧一致（先名称后地址），两种出身在预览层才长得一样",
            listOf(ValidationReason.NAME_BLANK, ValidationReason.URL_NOT_HTTP),
            result.reasons,
        )
    }

    @Test
    fun `脚本书源不因缺选择器判无效`(): Unit {
        // 原生四查里的「缺入口」「缺解析规则」对脚本书源不成立：
        // 那两样住在原始 JSON 的规则块里，最小模型看不到（社区格式的规则块还能是纯脚本），
        // 拿原生那两条去判会把大量好源误拦在门外（语料里 62% 的源规则就是整段脚本）。
        val result = scriptCase()
        assertEquals(
            "只查名称与地址两条，原生那两条不得牵连进来",
            listOf<ValidationReason>(),
            result.reasons.filter {
                it == ValidationReason.NO_ENTRY || it == ValidationReason.NO_PARSE_RULE
            },
        )
        assertTrue(result.isValid)
    }

    @Test
    fun `含js代码的脚本书源给可执行代码警示`(): Unit {
        val result = BookSourceValidator.validateScript(
            source = ScriptSourceRule(bookSourceName = "A", bookSourceUrl = "https://a.com"),
            rawJson = """{"bookSourceName":"A","bookSourceUrl":"https://a.com","ruleContent":{"content":"<js>1</js>"}}""",
        )
        assertTrue(result.warnings.contains(ScriptWarning.HAS_EXECUTABLE_CODE))
        assertTrue("警示不是错误：含可执行代码照样允许导入，只是要求用户知情", result.isValid)
    }

    @Test
    fun `js前缀取值的脚本书源也给可执行代码警示`(): Unit {
        // `@js:` 是规则字段以脚本取值（而非整段 HTML 里嵌 <js>）的写法，两种形态都要扫到；
        // 扫描对象是原文 rawJson，模型里没有这些规则字段
        val result = scriptCase(rawJson = """{"bookSourceUrl":"https://a.com","searchUrl":"@js:baseUrl+'so'"}""")
        assertEquals(listOf(ScriptWarning.HAS_EXECUTABLE_CODE), result.warnings)
    }

    @Test
    fun `依赖登录的脚本书源给登录警示`(): Unit {
        val result = scriptCase(loginUrl = "https://www.script.com/login")
        assertEquals(
            "loginUrl 非空即依赖登录流程（v1 不支持登录，只明示不拦）",
            listOf(ScriptWarning.NEEDS_LOGIN),
            result.warnings,
        )
        assertTrue(result.isValid)
    }

    @Test
    fun `漫画或音频源给非文本警示`(): Unit {
        // 1 = 图片（漫画）、2 = 音频：本项目是文字阅读器，这两类能存进来但读不出文字
        listOf(1, 2).forEach { type ->
            val result = scriptCase(type = type)
            assertEquals(
                "bookSourceType = $type 应报非文本源",
                listOf(ScriptWarning.NOT_TEXT_SOURCE),
                result.warnings,
            )
            assertTrue("非文本源同样只警示不拦导入", result.isValid)
        }
    }

    @Test
    fun `多项警示同时命中时一次报全`(): Unit {
        val result = scriptCase(
            type = 1,
            loginUrl = "https://www.script.com/login",
            rawJson = """{"bookSourceUrl":"https://www.script.com","ruleContent":{"content":"<js>1</js>"}}""",
        )
        assertEquals(
            "三项按判定顺序列全，预览层一次说完",
            listOf(
                ScriptWarning.HAS_EXECUTABLE_CODE,
                ScriptWarning.NEEDS_LOGIN,
                ScriptWarning.NOT_TEXT_SOURCE,
            ),
            result.warnings,
        )
    }
}
