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
