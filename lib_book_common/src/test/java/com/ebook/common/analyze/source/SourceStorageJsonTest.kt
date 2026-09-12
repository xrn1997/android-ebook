package com.ebook.common.analyze.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SourceStorageJson] 的解码契约（纯 JVM：只碰 kotlinx-serialization，不碰 Android 框架）。
 *
 * 这个解码器存在的唯一理由是**接受集只能有一份**：书源管理页的导入预览用它判「这一条解不解得开」，
 * [BookSourceManager.addScriptSource] 用同一个 [storageJson] 实例决定「这一条存不存得下」。
 * 两边各开一个 `Json { }` 就会漂移，出现「预览说能导、点确认才发现存不下」——
 * 那种故障没有报错，只有统计里的一个「失败」数字，用户与开发者都追不到根因。
 * 因此这里钉的就是**接受集的边界形态**，而不是模型字段本身（字段定义见 [com.ebook.api.entity.ScriptSourceRule]）。
 *
 * 落库侧同形态的行为由 `BookSourceManagerImplTest` 的 addScriptSource 用例锁
 * （它给同样的输入，断言返回的 failure 消息）。
 */
class SourceStorageJsonTest {

    /** 社区整包里的一条：顶层既有本模型声明的键，也有一堆模型看不见的规则块 */
    private val communitySource = """
        {
          "bookSourceName": "测试书源",
          "bookSourceUrl": "https://www.community.com",
          "bookSourceGroup": "azyk",
          "bookSourceType": 0,
          "enabled": true,
          "enabledCookieJar": true,
          "bookSourceComment": "作者：X\n版本：1.2",
          "lastUpdateTime": 1700000000000,
          "customOrder": 0,
          "weight": 0,
          "searchUrl": "https://www.community.com/so.html?key={{key}}",
          "ruleSearch": {"bookList": ".bookbox", "checkKeyWord": ""},
          "ruleContent": {"content": "@js:result = '<div>' + result + '</div>'"}
        }
    """.trimIndent()

    @Test
    fun `社区原文按顶层键解出最小模型且忽略未声明键`(): Unit {
        val rule = SourceStorageJson.parseOrNull(communitySource)

        assertEquals("测试书源", rule?.bookSourceName)
        assertEquals("https://www.community.com", rule?.bookSourceUrl)
        assertEquals("azyk", rule?.bookSourceGroup)
        assertEquals(0, rule?.bookSourceType)
        assertEquals("", rule?.loginUrl)
        // enabledCookieJar / bookSourceComment / lastUpdateTime / customOrder / ruleSearch / ruleContent
        // 这些键模型里都没有：ignoreUnknownKeys 必须放行，否则社区包基本一条都解不出来
    }

    /**
     * 顶层键装着「读不出值的值」时整条解不出。
     *
     * 钉这一条是为了说清预览的判据边界：`bookSourceType` 是 Int，装了非数值（社区手改 JSON 的常态）
     * 就整块解不出；但**带引号的数字**（`"0"`）当前放过——解码器按 token 文本读 Int，引号不算类型不符。
     * 两个方向都要写明，否则人会以为「预览标不通过 = 这份 JSON 不合规」，或反过来以为
     * 「预览放过 = 每个字段都写对了类型」。关键是**预览与落库共用本解码器**，
     * 所以这两种结论在两边完全一致，不会出现「点了确认才发现存不下」。
     */
    @Test
    fun `键装着非数值时解不出而带引号的数字放过`(): Unit {
        assertNull(
            SourceStorageJson.parseOrNull(
                """{"bookSourceName": "A", "bookSourceUrl": "https://a.com", "bookSourceType": "abc"}"""
            )
        )
        // 顶层键装对象这类形态当前也不报错（宽松地读出内层字符串），那是三方库的实现细节，不锁进用例
        val quoted = SourceStorageJson.parseOrNull(
            """{"bookSourceName": "A", "bookSourceUrl": "https://a.com", "bookSourceType": "0"}"""
        )
        assertEquals("带引号的数字仍解得开：所以「预览放过」不等于「类型全对」", 0, quoted?.bookSourceType)
    }

    @Test
    fun `不是JSON对象时解不出而不是抛异常`(): Unit {
        // 预览层是按条目收敛的（一条坏不动整包），所以这里必须是 null 而不是异常外溢
        listOf("", "not json", "[1,2,3]", "{\"bookSourceUrl\": ").forEach { text ->
            assertNull("「$text」应解不出", SourceStorageJson.parseOrNull(text))
        }
    }

    /**
     * 声明过的键带着显式 `null`：整条判为解不开（社区包确实会写 `"loginUrl": null`）。
     *
     * 根因是 [storageJson] **没开** `coerceInputValues`，于是 null 不会退到属性默认值，
     * 而是当成类型不符直接抛错。这条断言钉的是「现在的事实」：预览与落库共用同一个解码器，
     * 所以同一份原文在两边要么都放行、要么都拒收，不会出现预览说能导、点确认才发现存不下。
     * 真要放宽接受集，改的是 [storageJson] 那一个实例 + 本用例 + `BookSourceManagerImplTest`
     * 里 addScriptSource 的对应断言——三处一起动才算改完（少改一处就是两边各说一套）。
     */
    @Test
    fun `声明键带显式null时整条判为解不开`(): Unit {
        assertNull(
            SourceStorageJson.parseOrNull(
                """{"bookSourceName": "A", "bookSourceUrl": "https://a.com", "loginUrl": null}"""
            )
        )
    }
}
