package com.ebook.source.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ScriptExplore.entries] 的只读口径：纯解析零网络，条目形状与 ExploreUrlFormat 的切分一致 */
class ScriptExploreTest {

    @Test
    fun `JSON 形态的 exploreUrl 给出标题与 URL 规则串`() {
        val raw = """
            {"bookSourceName":"脚本站","bookSourceUrl":"https://s.example",
             "exploreUrl":"[{\"title\":\"玄幻\",\"url\":\"/xuanhuan/{{page}}\"}]"}
        """.trimIndent()

        val entries = ScriptExplore.entries(raw)

        assertEquals(listOf(ScriptExploreEntry("玄幻", "/xuanhuan/{{page}}")), entries)
    }

    @Test
    fun `文本形态按 名称双冒号URL 逐行给出`() {
        val raw = """
            {"bookSourceName":"脚本站","bookSourceUrl":"https://s.example",
             "exploreUrl":"玄幻::/xuanhuan/{{page}}\n都市::/dushi"}
        """.trimIndent()

        val entries = ScriptExplore.entries(raw)

        assertEquals(
            listOf(
                ScriptExploreEntry("玄幻", "/xuanhuan/{{page}}"),
                ScriptExploreEntry("都市", "/dushi"),
            ),
            entries,
        )
    }

    @Test
    fun `未配 exploreUrl 给空列表而不是异常`() {
        val entries = ScriptExplore.entries("""{"bookSourceName":"脚本站","bookSourceUrl":"https://s.example"}""")

        assertTrue(entries.isEmpty())
    }

    /**
     * `enabledExplore=false` 是「有发现配置但作者关掉了发现入口」，不是「没配」。
     * 规格 §8.2 要求它决定是否展示；书城分类胶囊必须跟它走，否则脚本源关掉发现后仍全出。
     */
    @Test
    fun `enabledExplore 为 false 时不给分类条目`() {
        val raw = """
            {"bookSourceName":"脚本站","bookSourceUrl":"https://s.example",
             "enabledExplore":false,
             "exploreUrl":"玄幻::/xuanhuan/{{page}}"}
        """.trimIndent()

        assertTrue(
            "enabledExplore=false 时即使配了 exploreUrl 也不能给分类入口",
            ScriptExplore.entries(raw).isEmpty(),
        )
    }

    /**
     * 手写配置里 `"False"` 是常见拼法：字符串形态按大小写不敏感解析才算「作者声明为 false」，
     * 严格小写会静默落回默认 true——正是本仓反复禁止的「声明了但不生效」。
     */
    @Test
    fun `enabledExplore 的字符串形态大小写不敏感`() {
        val raw = """
            {"bookSourceName":"脚本站","bookSourceUrl":"https://s.example",
             "enabledExplore":"False",
             "exploreUrl":"玄幻::/xuanhuan/{{page}}"}
        """.trimIndent()

        assertTrue(
            "字符串 \"False\" 也必须按关闭处理",
            ScriptExplore.entries(raw).isEmpty(),
        )
    }

    @Test
    fun `脚本程序形态的 exploreUrl 给空列表而不是把源码当条目`() {
        // 书城分类胶囊读面同一条口径：这条源只是「少一份发现入口」，不是「整条源解不动」，
        // 故返回空列表（与未配 exploreUrl 同形），不抛异常——搜索/详情/目录/正文照旧可用
        val raw = """
            {"bookSourceName":"脚本站","bookSourceUrl":"https://s.example",
             "exploreUrl":"<js>\nvar ui=[];\nui.push({title:\"榜\",type:\"select\",chars:[\"热\",\"新\"]});\nJSON.stringify(ui);\n</js>"}
        """.trimIndent()

        assertTrue(ScriptExplore.entries(raw).isEmpty())
    }

    @Test
    fun `坏 JSON 抛类型化装载失败且话术与求值链路一致`() {
        val failure = runCatching { ScriptExplore.entries("{ 这不是合法 JSON") }.exceptionOrNull()

        assertTrue(
            "消息要能直接进用户文案：${failure?.message}",
            failure?.message?.contains("脚本书源 JSON 无法解析") == true,
        )
    }
}
