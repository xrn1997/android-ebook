package com.ebook.source.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 白名单导出的口径测试。它锁的不是「实现能不能跑」，而是**哪些 host 会被算进本源**——
 * 那同时决定了两件相反的事：真实源会不会被误拒、以及脚本能借道去哪里。两头都有代价，逐形态锁死。
 */
class SourceHostAllowlistTest {

    @Test
    fun `书源 URL 与规则文本里的 host 一起进白名单`() {
        val allowlist = SourceHostAllowlist.of(
            sourceUrl = "https://Manager.Example.com/api",
            ruleTexts = listOf(
                "https://www.baidu.com/s?wd={{key}}",
                "http://img.cdn.com/cover.jpg",
                "##[|第]|章",             // 正则替换段：没有 URL，不该产出 host
                "novel.example.com/path", // 漏协议的裸 host：不是绝对 URL，同样不产出
            ),
        )
        assertTrue(allowlist.allows("manager.example.com"))
        assertTrue(allowlist.allows("www.baidu.com"))
        assertTrue(allowlist.allows("img.cdn.com"))
        assertFalse(allowlist.allows("evil.com"))
        assertFalse(allowlist.allows("novel.example.com"))
    }

    @Test
    fun `导出时按 host 归一化：大小写、端口、userinfo、根点、IPv6 方括号`() {
        val allowlist = SourceHostAllowlist.of(
            sourceUrl = "",
            ruleTexts = listOf(
                "HTTPS://User:Pass@Novel.Example:8443/list",
                "https://Biquge.Example./toc",
                "https://[2001:DB8::1]:8080/x",
            ),
        )
        assertTrue(allowlist.allows("novel.example"))
        assertTrue(allowlist.allows("biquge.example"))
        assertTrue(allowlist.allows("2001:db8::1"))
        assertEquals(3, allowlist.entries.size)
    }

    @Test
    fun `子域不自动放行——近似域是主要风险形态`() {
        val allowlist = SourceHostAllowlist.of("", listOf("https://example.com"))
        assertTrue(allowlist.allows("example.com"))
        assertFalse(allowlist.allows("cdn.example.com"))
        assertFalse(allowlist.allows("evil-example.com"))
    }

    @Test
    fun `拼接与 JS 片段里的伪 URL 不误进白名单`() {
        val allowlist = SourceHostAllowlist.of(
            sourceUrl = "",
            ruleTexts = listOf(
                "var u = \"http://\" + host + \"/chapter\"",
                "{{baseUrl}}/list",
                "@js:result.replace(/https?:\\\\/\\\\//, '')",
            ),
        )
        assertTrue(
            "「://」后紧跟引号、或压根没有绝对协议：三条都该什么都不产出",
            allowlist.entries.isEmpty(),
        )
    }

    @Test
    fun `normalize 对无 host 的授权段回 null`() {
        listOf("", ":8080", "/", "[", "user@").forEach { authority ->
            assertNull("授权段「$authority」不该解出 host", SourceHostAllowlist.normalize(authority))
        }
        // zone id 只在归一化这一层剥；守门器的 check() 更早一步就把含 % 的授权段整体拒了
        assertEquals("fe80::1", SourceHostAllowlist.normalize("[fe80::1%eth0]"))
    }

    /**
     * 白名单的键是**字面量 host**，导出时一次都不做地址解析。
     *
     * 这条口径决定了 SSRF 判定的位置：`2130706433` 就是六个字符的 host，与 127.0.0.1 同不同意
     * 是地址层的事，只能在 [GuardedDns] 那次真解析上判（地址归属见 JsNetworkGuardTest）。
     * 若在导出侧顺手解析一次，白名单就长出第二个事实源，还会把「作者写了个 IP 字面量」变成
     * 「导入时就按内网拒掉」——后者是本仓明确不支持的判断（同一 host 在不同网络里归属不同）。
     */
    @Test
    fun `IP 字面量原样作 host 进白名单，导出侧不做地址解析`() {
        val allowlist = SourceHostAllowlist.of(
            sourceUrl = "",
            ruleTexts = listOf(
                "http://2130706433/x",          // 十进制形态的 127.0.0.1
                "http://0177.0.0.1/x",          // 前导 0（本仓不预测平台按十进制还是八进制解）
                "http://0x7f000001/x",          // 十六进制形态
                "http://anything.127.0.0.1.nip.io/x", // 泛解析域名：字面量里没有内网痕迹
            ),
        )
        assertTrue(allowlist.allows("2130706433"))
        assertTrue(allowlist.allows("0177.0.0.1"))
        assertTrue(allowlist.allows("0x7f000001"))
        assertTrue(allowlist.allows("anything.127.0.0.1.nip.io"))
        assertEquals(4, allowlist.entries.size)
    }
}
